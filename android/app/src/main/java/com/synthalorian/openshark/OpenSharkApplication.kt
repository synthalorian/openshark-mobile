package com.synthalorian.openshark

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import com.synthalorian.openshark.service.AndroidBridgeService
import com.synthalorian.openshark.service.GatewayManager
import com.synthalorian.openshark.service.GatewayService
import com.synthalorian.openshark.service.OpenSharkAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class OpenSharkApplication : Application() {

    companion object {
        const val TAG = "OpenSharkApp"
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** True once GatewayService has accepted the foreground start. */
    @Volatile
    private var gatewayServiceStarted = false

    override fun onCreate() {
        super.onCreate()

        // Start the embedded gateway inside a foreground service so Android
        // doesn't kill it when the activity leaves the foreground
        startGatewayService()

        // Start the Android Bridge Service (files, SMS, contacts, etc.)
        startBridgeService()

        // Log accessibility service status
        checkAccessibilityService()

        // If the FGS start was rejected (app launched while not visible,
        // e.g. adb/monkey with the screen off), the gateway runs in-process
        // for now. Promote it into the foreground service as soon as any
        // activity is actually resumed — GatewayManager.start is idempotent,
        // so the service adopts the already-running gateway.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (!gatewayServiceStarted) startGatewayService()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun startGatewayService() {
        try {
            val intent = Intent(this, GatewayService::class.java)
            startForegroundService(intent)
            gatewayServiceStarted = true
            Log.i(TAG, "Started GatewayService (foreground)")
        } catch (e: Exception) {
            val fgsRejected = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e is android.app.ForegroundServiceStartNotAllowedException
            if (fgsRejected) {
                Log.w(
                    TAG,
                    "FGS start not allowed from background — starting gateway " +
                        "in-process; will promote to service on next foreground"
                )
                gatewayServiceStarted = false
                appScope.launch {
                    val ok = GatewayManager.start(applicationContext)
                    Log.i(TAG, "In-process gateway start: $ok")
                }
            } else {
                Log.e(TAG, "Failed to start GatewayService", e)
            }
        }
    }

    private fun startBridgeService() {
        try {
            val intent = Intent(this, AndroidBridgeService::class.java)
            startService(intent)
            Log.i(TAG, "Started AndroidBridgeService")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AndroidBridgeService", e)
        }
    }

    private fun checkAccessibilityService() {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""

        val serviceName = "${packageName}/${OpenSharkAccessibilityService::class.java.canonicalName}"
        val isEnabled = enabledServices.contains(serviceName)

        if (isEnabled) {
            Log.i(TAG, "AccessibilityService is enabled")
        } else {
            Log.w(TAG, "AccessibilityService NOT enabled. UI automation unavailable.")
            Log.w(TAG, "Enable it in: Settings > Accessibility > OpenShark")
        }
    }
}
