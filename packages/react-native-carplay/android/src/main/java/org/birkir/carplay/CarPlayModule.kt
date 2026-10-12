package org.birkir.carplay

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.OnBackPressedCallback
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.ScreenManager
import androidx.car.app.SessionInfo
import androidx.car.app.model.Alert
import androidx.car.app.model.AlertCallback
import androidx.car.app.model.CarText
import androidx.car.app.model.Distance
import androidx.car.app.model.TabTemplate
import androidx.car.app.model.Template
import com.facebook.react.ReactApplication
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Callback
import com.facebook.react.bridge.LifecycleEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableNativeMap
import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.modules.debug.DevSettingsModule
import org.birkir.carplay.parser.Parser
import org.birkir.carplay.parser.TemplateParser
import org.birkir.carplay.screens.CarScreen
import org.birkir.carplay.screens.CarScreenContext
import org.birkir.carplay.utils.EventEmitter


@ReactModule(name = CarPlayModule.NAME)
class CarPlayModule internal constructor(private val reactContext: ReactApplicationContext) :
  ReactContextBaseJavaModule(reactContext) {

  private lateinit var carContext: CarContext
  private lateinit var parser: Parser;

  private var currentCarScreen: CarScreen? = null
  private var screenManager: ScreenManager? = null
  private val carScreens: HashMap<String, CarScreen> = HashMap()
  private val carTemplates: HashMap<String, ReadableMap> = HashMap()
  private val carScreenContexts: HashMap<CarScreen, CarScreenContext> =
    HashMap()
  private val handler: Handler = Handler(Looper.getMainLooper())


  // Global event emitter (no templateId's)
  private var eventEmitter: EventEmitter? = null
  private var backPressCallbackRegistered = false
  private var hasCarContext = false
  private var didConnectEmitted = false

  init {
    reactContext.addLifecycleEventListener(object : LifecycleEventListener {
      override fun onHostResume() {
        eventEmitter = EventEmitter(reactContext)
        reactContext.getNativeModule(DevSettingsModule::class.java)
          ?.addMenuItem("Reload Android Auto")
      }

      override fun onHostPause() {
        reactivateLifecycleForAndroidAuto()
      }
      override fun onHostDestroy() {
        reactivateLifecycleForAndroidAuto()
      }
    })
  }

  /**
   * When the phone Activity is paused/destroyed while Android Auto is connected,
   * the React Native host lifecycle goes to PAUSED/DESTROYED state. This causes
   * native modules (including Networking) to stop processing requests, making
   * fetch() hang indefinitely from the JS thread.
   *
   * Re-calling onHostResume keeps the lifecycle active so Android Auto can
   * continue making network requests.
   */
  private fun reactivateLifecycleForAndroidAuto() {
    if (!hasCarContext) return
    Log.d(TAG, "reactivateLifecycleForAndroidAuto: Phone app closed, re-activating lifecycle for Android Auto")

    Handler(Looper.getMainLooper()).postDelayed({
      try {
        val reactInstanceManager = (reactContext.applicationContext as ReactApplication)
          .reactNativeHost.reactInstanceManager

        reactInstanceManager.onHostResume(null, null)
        Log.d(TAG, "reactivateLifecycleForAndroidAuto: Lifecycle re-activated successfully")
      } catch (e: Exception) {
        Log.e(TAG, "reactivateLifecycleForAndroidAuto: Failed to re-activate lifecycle", e)
      }
    }, 100)
  }

  override fun getName(): String {
    return NAME
  }

  fun setCarContext(carContext: CarContext, currentCarScreen: CarScreen) {
    if (eventEmitter == null) {
      eventEmitter = EventEmitter(reactContext)
    }

    hasCarContext = true
    parser = Parser(carContext, CarScreenContext("", eventEmitter!!, carScreens));

    this.carContext = carContext
    this.currentCarScreen = currentCarScreen

    screenManager = currentCarScreen.screenManager
    carScreens["root"] = currentCarScreen

    // Only register back press callback once
    if (!backPressCallbackRegistered) {
      carContext.onBackPressedDispatcher.addCallback(object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
          Log.d(TAG, "handleOnBackPressed: Back button pressed, stack size: ${screenManager?.stackSize}")
          eventEmitter?.backButtonPressed(screenManager?.top?.marker)
        }
      })

      backPressCallbackRegistered = true
    }

    didConnectEmitted = true
    eventEmitter?.didConnect()
  }

  private fun parseTemplate(
    config: ReadableMap,
    carScreenContext: CarScreenContext
  ): Template {
    val factory = TemplateParser(carContext, carScreenContext)
    return factory.parse(config)
  }

  @ReactMethod
  fun checkForConnection() {
    if (hasCarContext && !didConnectEmitted) {
      didConnectEmitted = true
      eventEmitter?.didConnect()
    }
  }

  @ReactMethod
  fun createTemplate(templateId: String, config: ReadableMap, callback: Callback?) {
    handler.post {
      Log.d(TAG, "Creating template $templateId")

      // Store the template
      carTemplates[templateId] = config;

      try {
        createScreen(templateId);
        callback?.invoke()
      } catch (err: IllegalArgumentException) {
        val args = Arguments.createMap()
        args.putString("error", "Failed to parse template '$templateId': ${err.message}")
        callback?.invoke(args)
      }
    }
  }

  @ReactMethod
  fun updateTemplate(templateId: String, config: ReadableMap) {
    handler.post {
      carTemplates[templateId] = config;
      val screen = carScreens[templateId]
      if (screen != null) {
        val carScreenContext = carScreenContexts[screen];
        if (carScreenContext != null) {
          val template = parseTemplate(config, carScreenContext);
          screen.setTemplate(template, templateId, config);
          screen.invalidate()
          // If this is a tab template, we need to update the main tab screen as well
          if (template is TabTemplate) {
            carScreens[carScreenContext.screenMarker]?.setTemplate(template, carScreenContext.screenMarker, config)
            carScreens[carScreenContext.screenMarker]?.invalidate()
          }
        }
      }
    }
  }

  @ReactMethod
  fun setRootTemplate(templateId: String, animated: Boolean?) {
    Log.d(TAG, "set Root Template for $templateId")
    handler.post {
      // Reuse the initial root CarScreen instead of pushing a new one on top.
      // This ensures the tab template is the root of the ScreenManager,
      // so popping back from pushed screens returns to it correctly.
      val rootScreen = carScreens["root"]

      if (rootScreen != null) {
        val config = carTemplates[templateId]
        if (config != null) {
          screenManager?.popToRoot()

          rootScreen.marker = templateId
          carScreens[templateId] = rootScreen

          carScreenContexts.remove(rootScreen)
          val carScreenContext = createCarScreenContext(rootScreen)
          carScreenContexts[rootScreen] = carScreenContext

          val template = parseTemplate(config, carScreenContext)
          rootScreen.setTemplate(template, templateId, config)
          currentCarScreen = rootScreen
        }
      } else {
        // Fallback: create and push a new screen
        val screen = getScreen(templateId)
        if (screen != null) {
          currentCarScreen = screen
          screenManager?.popToRoot()
          screenManager?.push(screen)
        }
      }
    }
  }

  @ReactMethod
  fun pushTemplate(templateId: String, animated: Boolean?) {
    handler.post {
      val screen = getScreen(templateId)
      if (screen != null) {
        currentCarScreen = screen;
        screenManager?.push(screen)
      }
    }
  }

  @ReactMethod
  fun popToTemplate(templateId: String, animated: Boolean?) {
    handler.post {
      screenManager?.popTo(templateId);
    }
  }

  @ReactMethod
  fun popTemplate(animated: Boolean?) {
    handler.post {
      if ((screenManager?.stackSize ?: 0) <= 1) {
        return@post
      }

      screenManager!!.pop()
      removeScreen(currentCarScreen)
      currentCarScreen = screenManager!!.top as CarScreen
      currentCarScreen?.invalidate()
    }
  }

  @ReactMethod
  fun presentTemplate(templateId: String?, animated: Boolean?) {
    // void
  }

  @ReactMethod
  fun dismissTemplate(templateId: String?, animated: Boolean?) {
    // void
  }

  // pragma: Android Auto only stuff

  @ReactMethod
  fun toast(text: String, duration: Int) {
    if (!::carContext.isInitialized) {
      Log.e(TAG, "carContext is not initialized. Cannot show toast.")
      return
    }
    CarToast.makeText(carContext, text, duration).show()
  }

  @ReactMethod
  fun alert(props: ReadableMap) {
    handler.post {
      val id = props.getInt("id");
      val title = parser.parseCarText(props.getString("title")!!, props);
      val duration = props.getInt("duration").toLong();
      val alert = Alert.Builder(id, title, duration).apply {
        setCallback(object : AlertCallback {
          override fun onCancel(reason: Int) {
            val reasonString = when (reason) {
              AlertCallback.REASON_TIMEOUT -> "timeout"
              AlertCallback.REASON_USER_ACTION -> "userAction"
              AlertCallback.REASON_NOT_SUPPORTED -> "notSupported"
              else -> "unknown"
            }
            eventEmitter?.alertActionPressed("cancel", reasonString);
          }
          override fun onDismiss() {
            eventEmitter?.alertActionPressed("dismiss" );
          }
        })
        props.getString("subtitle")?.let { setSubtitle(parser.parseCarText(it, props)) }
        props.getMap("icon")?.let { setIcon(parser.parseCarIcon(it)) }
        props.getArray("actions")?.let {
          for (i in 0 until it.size()) {
            addAction(parser.parseAction(it.getMap(i)));
          }
        }
      }.build()
      carContext.getCarService(AppManager::class.java).showAlert(alert)
    }
  }

  @ReactMethod
  fun dismissAlert(alertId: Int) {
    carContext.getCarService(AppManager::class.java).dismissAlert(alertId)
  }

  @ReactMethod
  fun invalidate(templateId: String) {
    handler.post {
      val screen = getScreen(templateId)
      if (screen === screenManager!!.top) {
        Log.d(TAG, "Invalidated screen $templateId")
        screen.invalidate()
      }
    }
  }

  @ReactMethod
  fun reload() {
    val intent = Intent("org.birkir.carplay.APP_RELOAD")
    reactContext.sendBroadcast(intent)
  }

  @ReactMethod
  fun getHostInfo(promise: Promise) {
    return promise.resolve(Arguments.createMap().apply {
      carContext.hostInfo?.packageName?.let { putString("packageName", it) }
      carContext.hostInfo?.uid?.let { putInt("uid", it) }
    });
  }

  // Others

  @ReactMethod
  fun addListener(eventName: String) {
    Log.d(TAG, "listener added $eventName")
  }

  @ReactMethod
  fun removeListeners(count: Int) {
    Log.d(TAG, "remove listeners $count")
  }

  private fun createCarScreenContext(screen: CarScreen): CarScreenContext {
    val templateId = screen.marker!!
    return CarScreenContext(templateId, EventEmitter(reactContext, templateId), carScreens)
  }

  private fun createScreen(templateId: String): CarScreen? {
    if (!::carContext.isInitialized) {
      Log.e(TAG, "carContext is not initialized.")
      return null
    }
    val config = carTemplates[templateId];
    if (config != null) {
      val screen = CarScreen(carContext)
      screen.marker = templateId;

      // context
      carScreenContexts.remove(screen)
      val carScreenContext = createCarScreenContext(screen)
      carScreenContexts[screen] = carScreenContext

      val template = parseTemplate(config, carScreenContext);
      screen.setTemplate(template, templateId, config)
      carScreens[templateId] = screen;

      return screen;
    }
    return null;
  }

  private fun getScreen(name: String): CarScreen? {
    return carScreens[name] ?: createScreen(name);
  }

  private fun removeScreen(screen: CarScreen?) {
    val params = WritableNativeMap()
    params.putString("screen", screen!!.marker)
    carScreens.values.remove(screen)
  }

  companion object {
    const val NAME = "RNCarPlay"
    const val TAG = "CarPlay"
  }
}
