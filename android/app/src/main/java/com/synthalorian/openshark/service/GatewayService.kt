package com.synthalorian.openshark.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.synthalorian.openshark.MainActivity
import com.synthalorian.openshark.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the embedded Rust gateway lifecycle.
 *
 * Without this, Android kills the app process (and the gateway with it)
 * as soon as the activity leaves the foreground. The persistent
 * notification keeps the process alive so the gateway keeps serving
 * 127.0.0.1:9876 — including for other apps on the device.
 */
class GatewayService : Service() {

    companion object {
        const val TAG = "GatewayService"
        const val CHANNEL_ID = "openshark_gateway"
        const val NOTIFICATION_ID = 1984
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundWithType()
        scope.launch {
            val ok = GatewayManager.start(applicationContext)
            Log.i(TAG, "embedded gateway start: $ok")
            if (!ok) {
                // Native lib missing or bind failed — no reason to stay foreground
                stopSelf()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // If Android kills us despite the foreground notification, restart
        // (and GatewayManager.start is idempotent).
        return START_STICKY
    }

    override fun onDestroy() {
        scope.launch { GatewayManager.stop() }
        super.onDestroy()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "OpenShark Gateway",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the embedded OpenShark gateway running"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startForegroundWithType() {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenShark gateway active")
            .setContentText("Serving 127.0.0.1:${GatewayManager.PORT}")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
