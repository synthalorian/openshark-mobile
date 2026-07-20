package com.synthalorian.openshark.ui.viewmodel

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.synthalorian.openshark.data.remote.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

data class Message(
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: String,
    val content: String,
    val isStreaming: Boolean = false,
    val toolCall: ToolCallInfo? = null
)

data class ToolCallInfo(
    val name: String,
    val args: String,
    val status: ToolStatus
)

enum class ToolStatus { PENDING, EXECUTING, COMPLETED, FAILED }

enum class AgentMode { SAFE, FULL_SEND }

data class ModelInfo(
    val name: String,
    val provider: String,
    val contextLength: Int,
    val isLocal: Boolean = false,
    val costPer1k: Double = 0.0
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("openshark", Application.MODE_PRIVATE)
    private val clipboard = application.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private val baseUrl: String
        get() = prefs.getString("server_url", "http://127.0.0.1:9876") ?: "http://127.0.0.1:9876"

    private val retrofit: Retrofit
        get() = Retrofit.Builder()
            .baseUrl(baseUrl)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

    private val api: OpenSharkApi
        get() = retrofit.create(OpenSharkApi::class.java)

    private val sseClient: SseClient
        get() = SseClient(baseUrl)

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _currentModel = MutableStateFlow("gemma4:e2b")
    val currentModel: StateFlow<String> = _currentModel.asStateFlow()

    private val _agentMode = MutableStateFlow(AgentMode.SAFE)
    val agentMode: StateFlow<AgentMode> = _agentMode.asStateFlow()

    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Connecting)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _availableModels = MutableStateFlow<List<ModelInfo>>(emptyList())
    val availableModels: StateFlow<List<ModelInfo>> = _availableModels.asStateFlow()

    var showMenu by mutableStateOf(false)
    var showMemorySearch by mutableStateOf(false)

    private val sessionId = java.util.UUID.randomUUID().toString()

    sealed class ConnectionStatus {
        object Connected : ConnectionStatus()
        object Connecting : ConnectionStatus()
        data class Error(val message: String) : ConnectionStatus()
    }

    init {
        loadSettings()
        checkConnection()
        fetchModels()
    }

    private fun loadSettings() {
        _currentModel.value = prefs.getString("current_model", "gemma4:e2b") ?: "gemma4:e2b"
        _agentMode.value = when (prefs.getString("agent_mode", "safe")) {
            "full_send" -> AgentMode.FULL_SEND
            else -> AgentMode.SAFE
        }
    }

    private fun saveSettings() {
        prefs.edit().apply {
            putString("current_model", _currentModel.value)
            putString("agent_mode", when (_agentMode.value) {
                AgentMode.SAFE -> "safe"
                AgentMode.FULL_SEND -> "full_send"
            })
            apply()
        }
    }

    fun checkConnection() {
        viewModelScope.launch {
            _connectionStatus.value = ConnectionStatus.Connecting
            try {
                val response = api.healthCheck()
                if (response.isSuccessful) {
                    _connectionStatus.value = ConnectionStatus.Connected
                    fetchModels()
                } else {
                    _connectionStatus.value = ConnectionStatus.Error("Server error: ${response.code()}")
                }
            } catch (e: Exception) {
                _connectionStatus.value = ConnectionStatus.Error(e.message ?: "Connection failed")
            }
        }
    }

    fun fetchModels() {
        viewModelScope.launch {
            try {
                val response = api.getModels()
                if (response.isSuccessful) {
                    val models = response.body()?.map { model ->
                        ModelInfo(
                            name = model.name,
                            provider = model.provider,
                            contextLength = model.context_length,
                            isLocal = model.cost_per_1k_input == 0.0 && model.cost_per_1k_output == 0.0,
                            costPer1k = model.cost_per_1k_input + model.cost_per_1k_output
                        )
                    } ?: emptyList()
                    _availableModels.value = models
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Failed to fetch models", e)
            }
        }
    }

    fun sendMessage(content: String) {
        if (content.isBlank()) return

        val userMessage = Message(role = "user", content = content)
        _messages.value = _messages.value + userMessage
        _isLoading.value = true

        viewModelScope.launch {
            try {
                val assistantMessage = Message(role = "assistant", content = "", isStreaming = true)
                _messages.value = _messages.value + assistantMessage

                val request = ChatRequest(
                    message = content,
                    model = _currentModel.value,
                    stream = true,
                    session_id = sessionId
                )

                var fullResponse = ""

                sseClient.streamChat(request).collect { chunk ->
                    when {
                        chunk.error != null -> {
                            updateLastMessage("Error: ${chunk.error}", isStreaming = false)
                        }
                        chunk.tool_calls != null -> {
                            // Handle tool calls
                            chunk.tool_calls.forEach { toolCall ->
                                addToolCall(toolCall)
                            }
                        }
                        else -> {
                            fullResponse += chunk.chunk
                            updateLastMessage(fullResponse, isStreaming = !chunk.done)
                        }
                    }
                }

                _isLoading.value = false

            } catch (e: Exception) {
                Log.e("ChatViewModel", "Chat error", e)
                updateLastMessage("Error: ${e.message}", isStreaming = false)
                _isLoading.value = false
                _connectionStatus.value = ConnectionStatus.Error(e.message ?: "Unknown error")
            }
        }
    }

    private fun addToolCall(toolCall: ToolCall) {
        val currentMessages = _messages.value.toMutableList()
        currentMessages.add(
            Message(
                role = "assistant",
                content = "",
                toolCall = ToolCallInfo(
                    name = toolCall.name,
                    args = toolCall.args,
                    status = ToolStatus.PENDING
                )
            )
        )
        _messages.value = currentMessages
    }

    private fun updateLastMessage(content: String, isStreaming: Boolean) {
        val currentMessages = _messages.value.toMutableList()
        if (currentMessages.isNotEmpty()) {
            val lastIndex = currentMessages.size - 1
            if (currentMessages[lastIndex].role == "assistant") {
                currentMessages[lastIndex] = currentMessages[lastIndex].copy(
                    content = content,
                    isStreaming = isStreaming
                )
                _messages.value = currentMessages
            }
        }
    }

    fun clearChat() {
        _messages.value = emptyList()
    }

    fun searchMemory(query: String, semantic: Boolean = true) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                val response = api.searchMemory(query, semantic = semantic)
                if (response.isSuccessful) {
                    val results = response.body() ?: emptyList()
                    val resultText = if (results.isEmpty()) {
                        "No memories found for '$query'"
                    } else {
                        results.joinToString("\n\n") { msg ->
                            "[${msg.created_at.take(10)}] ${msg.role}: ${msg.content.take(200)}"
                        }
                    }

                    _messages.value = _messages.value + Message(
                        role = "assistant",
                        content = "🧠 **Memory Search: '$query'**\n\n$resultText"
                    )
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Memory search error", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun switchModel(modelName: String) {
        _currentModel.value = modelName
        saveSettings()
        viewModelScope.launch {
            _messages.value = _messages.value + Message(
                role = "assistant",
                content = "🔄 Switched to model: **$modelName**"
            )
        }
    }

    fun setAgentMode(mode: AgentMode) {
        _agentMode.value = mode
        saveSettings()
    }

    fun setServerUrl(url: String) {
        prefs.edit().putString("server_url", url).apply()
        checkConnection()
        fetchModels()
    }

    fun getServerUrl(): String = baseUrl

    fun exportChat() {
        val chatText = _messages.value.joinToString("\n\n") { msg ->
            "**${msg.role.uppercase()}**: ${msg.content}"
        }
        val clip = ClipData.newPlainText("OpenShark Chat", chatText)
        clipboard.setPrimaryClip(clip)
    }

    fun executeTool(name: String, args: String) {
        viewModelScope.launch {
            try {
                val response = api.executeTool(ToolExecuteRequest(name, args))
                if (response.isSuccessful) {
                    val result = response.body()
                    _messages.value = _messages.value + Message(
                        role = "assistant",
                        content = result?.let {
                            if (it.success) "✅ **$name**: ${it.result}"
                            else "❌ **$name**: ${it.error}"
                        } ?: "Tool execution completed"
                    )
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Tool execution error", e)
            }
        }
    }
}
