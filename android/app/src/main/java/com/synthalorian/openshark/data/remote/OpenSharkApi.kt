package com.synthalorian.openshark.data.remote

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface OpenSharkApi {
    @POST("/v1/chat")
    suspend fun chat(@Body request: ChatRequest): Response<ResponseBody>

    @GET("/v1/models")
    suspend fun getModels(): Response<List<ModelInfo>>

    @GET("/v1/status")
    suspend fun getStatus(): Response<StatusResponse>

    @GET("/v1/memory")
    suspend fun searchMemory(
        @Query("query") query: String,
        @Query("limit") limit: Int = 10,
        @Query("semantic") semantic: Boolean = false
    ): Response<List<MemoryMessage>>

    @POST("/v1/memory")
    suspend fun saveMemory(@Body request: MemorySaveRequest): Response<Map<String, Any>>

    @POST("/v1/tools/execute")
    suspend fun executeTool(@Body request: ToolExecuteRequest): Response<ToolExecuteResponse>

    @GET("/v1/health")
    suspend fun healthCheck(): Response<Map<String, String>>
}

data class ChatRequest(
    val message: String,
    val model: String? = null,
    val stream: Boolean = true,
    val session_id: String? = null,
    val system_prompt: String? = null
)

data class ChatResponseChunk(
    val chunk: String,
    val done: Boolean = false,
    val tool_calls: List<ToolCall>? = null,
    val error: String? = null
)

data class ToolCall(
    val name: String,
    val args: String,
    val result: String? = null
)

data class ModelInfo(
    val name: String,
    val provider: String,
    val context_length: Int,
    val cost_per_1k_input: Double,
    val cost_per_1k_output: Double,
    val capabilities: List<String>
)

data class StatusResponse(
    val version: String,
    val default_model: String,
    val models_available: Int,
    val memory_enabled: Boolean,
    val uptime_seconds: Long
)

data class MemoryMessage(
    val id: String,
    val session_id: String,
    val role: String,
    val content: String,
    val created_at: String,
    val tokens_used: Int?
)

data class MemorySaveRequest(
    val content: String,
    val session_id: String? = null
)

data class ToolExecuteRequest(
    val name: String,
    val args: String
)

data class ToolExecuteResponse(
    val success: Boolean,
    val result: String,
    val error: String?
)
