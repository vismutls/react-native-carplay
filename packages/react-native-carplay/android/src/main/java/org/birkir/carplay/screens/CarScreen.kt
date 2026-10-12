package org.birkir.carplay.screens

import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.model.TabTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MapTemplate
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.PlaceListNavigationTemplate
import androidx.car.app.navigation.model.RoutePreviewNavigationTemplate
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.facebook.react.bridge.ReadableMap
import org.birkir.carplay.utils.VirtualRenderer
import android.os.Handler
import android.os.Looper

class CarScreen(carContext: CarContext) : Screen(carContext) {

  var template: Template? = null
    set(value) {
      field = value
      Log.d(TAG, "Template set: ${value?.javaClass?.simpleName}")
      cancelLoadingTimeout()
      invalidate()
    }
  private var virtualRenderer: VirtualRenderer? = null
  private var loadingTimeoutHandler: Handler? = null
  private var hasTimedOut = false

  init {
    lifecycle.addObserver(object : LifecycleEventObserver {
      override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        if (event == Lifecycle.Event.ON_DESTROY && virtualRenderer != null) {
          Log.d(TAG, "onStateChanged: got $event, removing virtual renderer")
          cancelLoadingTimeout()
          virtualRenderer = null
        }
      }
    })
    // Start timeout for React Native initialization
    startLoadingTimeout()
  }

  private fun startLoadingTimeout() {
    loadingTimeoutHandler = Handler(Looper.getMainLooper())
    loadingTimeoutHandler?.postDelayed({
      if (template == null && !hasTimedOut) {
        Log.w(TAG, "React Native initialization timed out after 15 seconds")
        hasTimedOut = true
        invalidate()
      }
    }, 15000)
  }

  private fun cancelLoadingTimeout() {
    loadingTimeoutHandler?.removeCallbacksAndMessages(null)
    loadingTimeoutHandler = null
  }

  fun setTemplate(template: Template?, templateId: String, props: ReadableMap?) {
    // allow MapTemplate, NavigationTemplate and PlaceListMapTemplate
    val isSurfaceTemplate = template is MapTemplate
      || template is NavigationTemplate
      || template is PlaceListMapTemplate
      || template is PlaceListNavigationTemplate
      || template is RoutePreviewNavigationTemplate

    if (isSurfaceTemplate && virtualRenderer == null) {
      Log.d(TAG, "setTemplate: received navigation template with args: $templateId")
      virtualRenderer = VirtualRenderer(carContext, templateId)
    }
    this.template = template
    if (template is TabTemplate) {
      invalidate()
    }
  }

  override fun onGetTemplate(): Template {
    Log.d(TAG, "onGetTemplate for $marker")
    val currentTemplate = template
    if (currentTemplate == null) {
      if (hasTimedOut) {
        Log.e(TAG, "Timeout reached - React Native failed to initialize")
        return MessageTemplate.Builder("Android Auto App Initialization Error. Please Try Again.")
          .setTitle("Connection Error")
          .setHeaderAction(Action.APP_ICON)
          .addAction(
            Action.Builder()
              .setTitle("Retry")
              .setOnClickListener {
                hasTimedOut = false
                startLoadingTimeout()
                invalidate()
              }
              .build()
          )
          .build()
      }
      Log.d(TAG, "Template is null, returning loading template")
      return PaneTemplate.Builder(Pane.Builder().setLoading(true).build())
        .setTitle("Loading...")
        .build()
    }
    Log.d(TAG, "Returning template: ${currentTemplate::class.simpleName}")
    return currentTemplate
  }

  companion object {
    const val TAG = "CarScreen"
  }
}
