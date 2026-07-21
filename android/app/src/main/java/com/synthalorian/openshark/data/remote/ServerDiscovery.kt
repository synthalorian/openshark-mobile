package com.synthalorian.openshark.data.remote

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Auto-discovers OpenShark server URLs, similar to how KimiClaw auto-connects.
 * 
 * Tries URLs in priority order:
 * 1. User's saved preference
 * 2. 127.0.0.1:9876 (Termux localhost)
 * 3. localhost:9876
 * 4. 10.0.2.2:9876 (Android emulator host loopback)
 * 5. Auto-detected local network IP
 */
class ServerDiscovery {
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
    
    companion object {
        const val DEFAULT_PORT = 9876
        const val TAG = "ServerDiscovery"
        
        val DISCOVERY_URLS = listOf(
            "http://127.0.0.1:9876",
            "http://localhost:9876",
            "http://10.0.2.2:9876"  // Android emulator
        )
    }
    
    /**
     * Try to find a working server. Returns the first responsive URL or null.
     */
    suspend fun discover(savedUrl: String? = null): String? = withContext(Dispatchers.IO) {
        val candidates = buildList {
            // User's saved URL first (if valid)
            savedUrl?.let { 
                if (it.startsWith("http")) add(it)
            }
            // Standard discovery URLs
            addAll(DISCOVERY_URLS)
            // Try to auto-detect local IP
            detectLocalIp()?.let { ip ->
                add("http://$ip:$DEFAULT_PORT")
            }
        }.distinct()
        
        Log.d(TAG, "Trying ${candidates.size} URLs: $candidates")
        
        // Try all candidates concurrently, return first success
        val deferreds = candidates.map { url ->
            async {
                if (checkUrl(url)) {
                    Log.i(TAG, "Found server at $url")
                    url
                } else null
            }
        }
        
        deferreds.mapNotNull { it.await() }.firstOrNull()
    }
    
    /**
     * Quick health check on a URL.
     */
    suspend fun checkUrl(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$url/v1/health")
                .build()
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Try to detect the device's local IP address for LAN connections.
     */
    private fun detectLocalIp(): String? {
        return try {
            InetAddress.getLocalHost().hostAddress?.takeIf { 
                !it.startsWith("127.") && !it.startsWith("0.")
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Returns a list of all discovery URLs for display/manual selection.
     */
    fun getDiscoveryUrls(savedUrl: String? = null): List<String> {
        return buildList {
            savedUrl?.let { add(it) }
            addAll(DISCOVERY_URLS)
            detectLocalIp()?.let { add("http://$it:$DEFAULT_PORT") }
        }.distinct()
    }
}
