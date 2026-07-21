package com.synthalorian.openshark.data.remote

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Auto-discovers AI model providers (Ollama local, Kimi proxy, etc.)
 * similar to how KimiClaw auto-connects to its gateway.
 */
class ProviderDiscovery {
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
    
    private val gson = Gson()
    
    companion object {
        const val TAG = "ProviderDiscovery"
        
        // Default provider endpoints to try
        val DEFAULT_PROVIDERS = listOf(
            ProviderEndpoint("ollama", "http://127.0.0.1:11434/v1", ProviderType.LOCAL),
            ProviderEndpoint("ollama-localhost", "http://localhost:11434/v1", ProviderType.LOCAL),
            ProviderEndpoint("kimi-proxy", "http://127.0.0.1:9000/v1", ProviderType.CLOUD),
            ProviderEndpoint("kimi-proxy-localhost", "http://localhost:9000/v1", ProviderType.CLOUD),
            ProviderEndpoint("openshark", "http://127.0.0.1:9876/v1", ProviderType.LOCAL),
        )
    }
    
    data class ProviderEndpoint(
        val id: String,
        val baseUrl: String,
        val type: ProviderType
    )
    
    enum class ProviderType {
        LOCAL,  // Ollama, local LLMs — free, offline-capable
        CLOUD   // Kimi, OpenAI, etc — requires internet/proxy
    }
    
    data class DiscoveredProvider(
        val id: String,
        val name: String,
        val baseUrl: String,
        val type: ProviderType,
        val models: List<DiscoveredModel>,
        val isHealthy: Boolean
    )
    
    data class DiscoveredModel(
        val id: String,
        val name: String,
        val contextLength: Int = 8192,
        val provider: String,
        val providerType: ProviderType,
        val isFree: Boolean = true
    )
    
    /**
     * Discover all available providers concurrently.
     */
    suspend fun discoverAll(): List<DiscoveredProvider> = withContext(Dispatchers.IO) {
        val deferreds = DEFAULT_PROVIDERS.map { endpoint ->
            async {
                discoverProvider(endpoint)
            }
        }
        
        deferreds.awaitAll().filter { it.isHealthy }
    }
    
    /**
     * Try to discover a single provider.
     */
    private suspend fun discoverProvider(endpoint: ProviderEndpoint): DiscoveredProvider {
        return try {
            val models = when (endpoint.id) {
                "ollama", "ollama-localhost" -> discoverOllamaModels(endpoint)
                "kimi-proxy", "kimi-proxy-localhost" -> discoverKimiModels(endpoint)
                else -> discoverGenericModels(endpoint)
            }
            
            DiscoveredProvider(
                id = endpoint.id,
                name = when (endpoint.type) {
                    ProviderType.LOCAL -> "Ollama Local"
                    ProviderType.CLOUD -> "Kimi Cloud"
                },
                baseUrl = endpoint.baseUrl,
                type = endpoint.type,
                models = models,
                isHealthy = models.isNotEmpty()
            )
        } catch (e: Exception) {
            Log.d(TAG, "Provider ${endpoint.id} not available: ${e.message}")
            DiscoveredProvider(
                id = endpoint.id,
                name = endpoint.id,
                baseUrl = endpoint.baseUrl,
                type = endpoint.type,
                models = emptyList(),
                isHealthy = false
            )
        }
    }
    
    /**
     * Discover Ollama models via its API.
     */
    private fun discoverOllamaModels(endpoint: ProviderEndpoint): List<DiscoveredModel> {
        // Try the Ollama tags endpoint
        val request = Request.Builder()
            .url("${endpoint.baseUrl.removeSuffix("/v1")}/api/tags")
            .build()
        
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            
            val body = response.body?.string() ?: return emptyList()
            val tagsResponse = gson.fromJson(body, OllamaTagsResponse::class.java)
            
            return tagsResponse?.models?.map { model ->
                val name = model.name ?: model.model ?: "unknown"
                val size = model.details?.parameter_size ?: ""
                // Extract context length from model info if available
                val ctxLen = when {
                    size.contains("4b", ignoreCase = true) -> 8192
                    size.contains("8b", ignoreCase = true) -> 8192
                    size.contains("2b", ignoreCase = true) -> 8192
                    else -> 8192
                }
                
                DiscoveredModel(
                    id = name,
                    name = name,
                    contextLength = ctxLen,
                    provider = endpoint.id,
                    providerType = ProviderType.LOCAL,
                    isFree = true
                )
            } ?: emptyList()
        }
    }
    
    /**
     * Discover Kimi models via the proxy.
     */
    private fun discoverKimiModels(endpoint: ProviderEndpoint): List<DiscoveredModel> {
        val request = Request.Builder()
            .url("${endpoint.baseUrl}/models")
            .build()
        
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            
            val body = response.body?.string() ?: return emptyList()
            
            // Try OpenAI-compatible format first
            try {
                val openaiResponse = gson.fromJson(body, OpenAIModelsResponse::class.java)
                return openaiResponse?.data?.map { model ->
                    DiscoveredModel(
                        id = model.id,
                        name = model.id,
                        contextLength = when {
                            model.id.contains("k3") -> 1_000_000
                            model.id.contains("k2.5") -> 256_000
                            model.id.contains("k2") -> 128_000
                            else -> 128_000
                        },
                        provider = endpoint.id,
                        providerType = ProviderType.CLOUD,
                        isFree = false
                    )
                } ?: emptyList()
            } catch (e: Exception) {
                // Fallback: assume standard Kimi models if proxy doesn't list them
                return listOf(
                    DiscoveredModel("kimi-k3", "kimi-k3", 1_000_000, endpoint.id, ProviderType.CLOUD, false),
                    DiscoveredModel("kimi-k2.5", "kimi-k2.5", 256_000, endpoint.id, ProviderType.CLOUD, false),
                    DiscoveredModel("kimi-k2", "kimi-k2", 128_000, endpoint.id, ProviderType.CLOUD, false)
                )
            }
        }
    }
    
    /**
     * Generic model discovery for OpenAI-compatible endpoints.
     */
    private fun discoverGenericModels(endpoint: ProviderEndpoint): List<DiscoveredModel> {
        val request = Request.Builder()
            .url("${endpoint.baseUrl}/models")
            .build()
        
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            
            val body = response.body?.string() ?: return emptyList()
            val openaiResponse = gson.fromJson(body, OpenAIModelsResponse::class.java)
            
            return openaiResponse?.data?.map { model ->
                DiscoveredModel(
                    id = model.id,
                    name = model.id,
                    contextLength = 128_000,
                    provider = endpoint.id,
                    providerType = endpoint.type,
                    isFree = true
                )
            } ?: emptyList()
        }
    }
    
    // Ollama API response models
    data class OllamaTagsResponse(
        val models: List<OllamaModel>? = null
    )
    
    data class OllamaModel(
        val name: String? = null,
        val model: String? = null,
        val modified_at: String? = null,
        val size: Long? = null,
        val digest: String? = null,
        val details: OllamaModelDetails? = null
    )
    
    data class OllamaModelDetails(
        val parent_model: String? = null,
        val format: String? = null,
        val family: String? = null,
        val families: List<String>? = null,
        val parameter_size: String? = null,
        val quantization_level: String? = null
    )
    
    // OpenAI-compatible API response models
    data class OpenAIModelsResponse(
        @SerializedName("object") val obj: String? = null,
        val data: List<OpenAIModel>? = null
    )
    
    data class OpenAIModel(
        val id: String,
        @SerializedName("object") val obj: String? = null,
        val created: Long? = null,
        @SerializedName("owned_by") val ownedBy: String? = null
    )
}
