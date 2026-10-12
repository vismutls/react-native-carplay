package org.birkir.carplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator
import com.facebook.react.ReactApplication
import com.facebook.react.ReactInstanceManager

class CarPlayService : CarAppService() {
  private lateinit var reactInstanceManager: ReactInstanceManager
  override fun onCreate() {
    super.onCreate()

    try {
      reactInstanceManager =
        (application as ReactApplication).reactNativeHost.reactInstanceManager

      // Only initialize React Native when Android Auto connects (not for regular app usage)
      if (!reactInstanceManager.hasStartedCreatingInitialContext()) {
        reactInstanceManager.createReactContextInBackground()
      }
    } catch (e: Exception) {
      Log.e(TAG, "onCreate: Error initializing ReactInstanceManager", e)
      throw e
    }
  }

  override fun createHostValidator(): HostValidator {
    return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
  }

  override fun onCreateSession(sessionInfo: SessionInfo): Session {
    Log.d(TAG, "onCreateSession: sessionId = ${sessionInfo.sessionId}, display = ${sessionInfo.displayType}")
    return CarPlaySession(reactInstanceManager)
  }

  companion object {
    var TAG = "CarPlayService"
  }
}
