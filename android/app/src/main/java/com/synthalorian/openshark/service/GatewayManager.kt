package com.synthalorian.openshark.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Lifecycle manager for the embedded Rust gateway.
 *
 * Started once from [OpenSharkApplication.onCreate]. Config lives at
 * filesDir/openshark/config.toml (edited via Settings → Providers; after
 * edits call the gateway's POST /v1/config/reload).
 */
object GatewayManager {
    private const val TAG = "GatewayManager"
    const val PORT = 9876
    const val BASE_URL = "http://127.0.0.1:$PORT"

    @Volatile
    var running: Boolean = false
        private set

    val configDirName = "openshark"

    fun configDir(context: Context): File =
        File(context.filesDir, configDirName).apply { mkdirs() }

    fun configFile(context: Context): File = File(configDir(context), "config.toml")

    /** Start the embedded gateway (idempotent). Safe to call from any thread. */
    suspend fun start(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (running) return@withContext true
        try {
            val dir = configDir(context).absolutePath
            val bound = GatewayNative.nativeStart(dir, PORT)
            running = bound == PORT
            if (running) {
                Log.i(TAG, "Embedded gateway listening on 127.0.0.1:$bound (config: $dir)")
            } else {
                Log.e(TAG, "nativeStart returned $bound")
            }
            running
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Gateway native lib not available in this build", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start embedded gateway", e)
            false
        }
    }

    suspend fun stop(): Boolean = withContext(Dispatchers.IO) {
        val ok = GatewayNative.nativeStop() == 0
        if (ok) running = false
        ok
    }

    fun status(): Pair<Boolean, Int?> {
        return try {
            val json = JSONObject(GatewayNative.nativeStatus())
            val isRunning = json.optBoolean("running")
            running = isRunning
            isRunning to if (isRunning) json.optInt("port") else null
        } catch (e: Exception) {
            false to null
        }
    }
}
