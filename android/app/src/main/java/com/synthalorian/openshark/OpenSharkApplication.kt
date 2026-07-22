package com.synthalorian.openshark

import android.app.Application
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.synthalorian.openshark.service.AndroidBridgeService
import com.synthalorian.openshark.service.OpenSharkAccessibilityService

class OpenSharkApplication : Application() {
    
    companion object {
        const val TAG = "OpenSharkApp"
    }
    
    override fun onCreate() {
        super.onCreate()
        
        // Start the Android Bridge Service (files, SMS, contacts, etc.)
        startBridgeService()
        
        // Log accessibility service status
        checkAccessibilityService()
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
