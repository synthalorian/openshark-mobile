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
import com.synthalorian.openshark.command.Command
import com.synthalorian.openshark.data.model.Agent
import com.synthalorian.openshark.data.remote.*
import com.synthalorian.openshark.data.repository.AgentRepository
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
    val agentRepository = AgentRepository(application)

    private val baseUrl: String
        get() = prefs.getString("server_url", "http://127.0.0.1:9876") ?: "http://127.0.0.1:9876"

    val agents = agentRepository.agents
    val activeAgent = agentRepository.activeAgent

    private var cachedBaseUrl: String? = null
    private var cachedApi: OpenSharkApi? = null
    private var cachedSseClient: SseClient? = null

    private val api: OpenSharkApi
        get() {
            val currentUrl = baseUrl
            if (cachedApi == null || cachedBaseUrl != currentUrl) {
                cachedBaseUrl = currentUrl
                val retrofit = Retrofit.Builder()
                    .baseUrl(currentUrl)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                cachedApi = retrofit.create(OpenSharkApi::class.java)
            }
            return cachedApi!!
        }

    private val sseClient: SseClient
        get() {
            val currentUrl = baseUrl
            if (cachedSseClient == null || cachedBaseUrl != currentUrl) {
                cachedSseClient = SseClient(currentUrl)
            }
            return cachedSseClient!!
        }

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

    fun handleInput(content: String) {
        if (content.isBlank()) return

        // Show the user's command/message in chat
        val userMessage = Message(role = "user", content = content)
        _messages.value = _messages.value + userMessage

        // Check if it's a command
        val parsed = Command.parse(content)
        if (parsed != null) {
            executeCommand(parsed.first, parsed.second)
        } else {
            sendMessage(content)
        }
    }

    private fun executeCommand(command: Command, args: String) {
        when (command) {
            is Command.Connect -> {
                if (args.isBlank()) {
                    addSystemMessage("❌ Usage: `/connect <url>` (e.g., `/connect http://192.168.1.42:9876`)")
                    return
                }
                if (!args.startsWith("http://") && !args.startsWith("https://")) {
                    addSystemMessage("❌ URL must start with `http://` or `https://`")
                    return
                }
                setServerUrl(args)
                addSystemMessage("🔗 Connecting to `$args`...")
            }

            is Command.Status -> {
                val status = when (val cs = _connectionStatus.value) {
                    is ConnectionStatus.Connected -> "🟢 Connected to `${getServerUrl()}`"
                    is ConnectionStatus.Connecting -> "🟡 Connecting..."
                    is ConnectionStatus.Error -> "🔴 Disconnected: ${cs.message}"
                }
                val model = "🤖 Model: `${_currentModel.value}`"
                val mode = "🛡️ Mode: `${_agentMode.value.name.lowercase()}`"
                val agent = activeAgent.value?.let { "${it.emoji} Agent: **${it.displayName}**" } ?: "👤 Agent: None"
                addSystemMessage("$status\n$model\n$mode\n$agent")
            }

            is Command.Config -> {
                val config = buildString {
                    appendLine("⚙️ **Configuration**")
                    appendLine("Server: `${getServerUrl()}`")
                    appendLine("Model: `${_currentModel.value}`")
                    appendLine("Mode: `${_agentMode.value.name.lowercase()}`")
                    appendLine("Models cached: `${_availableModels.value.size}`")
                }
                addSystemMessage(config)
            }

            is Command.Model -> {
                if (args.isBlank()) {
                    addSystemMessage("Current model: `${_currentModel.value}`\nUse `/models` to see available models.")
                    return
                }
                val modelName = args.trim()
                val exists = _availableModels.value.any { it.name == modelName }
                if (!exists) {
                    addSystemMessage("⚠️ Model `$modelName` not found. Use `/models` to list available models.")
                    return
                }
                switchModel(modelName)
            }

            is Command.Models -> {
                val models = _availableModels.value
                if (models.isEmpty()) {
                    addSystemMessage("No models available. Check your server connection with `/status`.")
                    return
                }
                val list = models.joinToString("\n") { m ->
                    val prefix = if (m.name == _currentModel.value) "▸ " else "  "
                    val type = if (m.isLocal) "🏠" else "☁️"
                    "$prefix`${m.name}` $type ${m.provider}"
                }
                addSystemMessage("**Available Models**\n$list")
            }

            is Command.Local -> {
                val local = _availableModels.value.filter { it.isLocal }
                if (local.isEmpty()) {
                    addSystemMessage("No local models available.")
                    return
                }
                switchModel(local.first().name)
                addSystemMessage("🏠 Switched to local model: `${local.first().name}`")
            }

            is Command.Cloud -> {
                val cloud = _availableModels.value.filter { !it.isLocal }
                if (cloud.isEmpty()) {
                    addSystemMessage("No cloud models available.")
                    return
                }
                switchModel(cloud.first().name)
                addSystemMessage("☁️ Switched to cloud model: `${cloud.first().name}`")
            }

            is Command.New -> {
                clearChat()
                addSystemMessage("🆕 New chat started. Session ID: `$sessionId`")
            }

            is Command.Clear -> {
                clearChat()
                addSystemMessage("🧹 Chat history cleared.")
            }

            is Command.Export -> {
                exportChat()
                addSystemMessage("📋 Chat copied to clipboard.")
            }

            is Command.Memory -> {
                if (args.isBlank()) {
                    addSystemMessage("❌ Usage: `/memory <query>` (e.g., `/memory auth refactor`)")
                    return
                }
                searchMemory(args)
            }

            is Command.Remember -> {
                if (args.isBlank()) {
                    addSystemMessage("❌ Usage: `/remember <text>`")
                    return
                }
                viewModelScope.launch {
                    try {
                        val response = api.saveMemory(MemorySaveRequest(args, sessionId))
                        if (response.isSuccessful) {
                            addSystemMessage("🧠 Saved to memory.")
                        } else {
                            addSystemMessage("❌ Failed to save memory: ${response.code()}")
                        }
                    } catch (e: Exception) {
                        addSystemMessage("❌ Error: ${e.message}")
                    }
                }
            }

            is Command.Safe -> {
                setAgentMode(AgentMode.SAFE)
                addSystemMessage("🛡️ Safe mode enabled. I'll ask before executing tools.")
            }

            is Command.FullSend -> {
                setAgentMode(AgentMode.FULL_SEND)
                addSystemMessage("🚀 Full send mode enabled. I'll execute tools automatically.")
            }

            is Command.Tools -> {
                // TODO: Fetch tools from server
                addSystemMessage("🔧 **Available Tools**\n- `shell` — Execute shell commands\n- `file_read` — Read files\n- `file_write` — Write files\n\nUse `/exec <name> <args>` to run a tool.")
            }

            is Command.Exec -> {
                val parts = args.split(" ", limit = 2)
                if (parts.size < 1 || parts[0].isBlank()) {
                    addSystemMessage("❌ Usage: `/exec <name> <args>` (e.g., `/exec shell ls -la`)")
                    return
                }
                val toolName = parts[0]
                val toolArgs = parts.getOrElse(1) { "" }
                executeTool(toolName, toolArgs)
            }

            is Command.Agent -> {
                if (args.isBlank()) {
                    val current = activeAgent.value
                    if (current != null) {
                        addSystemMessage("Current agent: ${current.emoji} **${current.displayName}**\nUse `/agentlist` to see all agents.")
                    } else {
                        addSystemMessage("No active agent. Use `/agentlist` to see available agents.")
                    }
                    return
                }
                val agentName = args.trim().lowercase()
                val agent = agentRepository.getAgentByName(agentName)
                if (agent != null) {
                    agentRepository.setActiveAgent(agent)
                    addSystemMessage("${agent.emoji} Switched to **${agent.displayName}**\n_${agent.tagline}_")
                } else {
                    addSystemMessage("❌ Agent `$agentName` not found. Use `/agentlist` to see available agents.")
                }
            }

            is Command.AgentList -> {
                val allAgents = agents.value
                val currentId = activeAgent.value?.id
                if (allAgents.isEmpty()) {
                    addSystemMessage("No agents configured.")
                    return
                }
                val list = allAgents.joinToString("\n") { a ->
                    val prefix = if (a.id == currentId) "▸ " else "  "
                    val type = if (a.isDefault) "🔒" else "✏️"
                    "$prefix${a.emoji} **${a.displayName}** $type\n     _${a.tagline}_"
                }
                addSystemMessage("**Agents**\n$list\n\nUse `/agent <name>` to switch.")
            }

            is Command.Soul -> {
                val agent = activeAgent.value
                if (agent == null) {
                    addSystemMessage("No active agent.")
                    return
                }
                val soulText = if (agent.soul.isNotBlank()) {
                    agent.soul
                } else {
                    "${agent.displayName} has no soul defined yet. Edit the agent to add a persona."
                }
                addSystemMessage("${agent.emoji} **${agent.displayName}**\n\n$soulText")
            }

            is Command.Help -> {
                if (args.isNotBlank()) {
                    val cmd = Command.ALL.find { it.name == args || it.aliases.contains(args) }
                    if (cmd != null) {
                        addSystemMessage(Command.getHelpText(cmd))
                    } else {
                        addSystemMessage("Unknown command: `$args`. Type `/help` for all commands.")
                    }
                } else {
                    addSystemMessage(Command.getHelpText())
                }
            }
        }
    }

    private fun addSystemMessage(content: String) {
        _messages.value = _messages.value + Message(
            role = "assistant",
            content = content
        )
    }

    fun sendMessage(content: String) {
        if (content.isBlank()) return
        _isLoading.value = true

        viewModelScope.launch {
            try {
                val assistantMessage = Message(role = "assistant", content = "", isStreaming = true)
                _messages.value = _messages.value + assistantMessage

                val request = ChatRequest(
                    message = content,
                    model = _currentModel.value,
                    stream = true,
                    session_id = sessionId,
                    system_prompt = activeAgent.value?.systemPrompt?.takeIf { it.isNotBlank() }
                )

                var fullResponse = ""

                sseClient.streamChat(request).collect { chunk ->
                    when {
                        chunk.error != null -> {
                            updateLastMessage("Error: ${chunk.error}", isStreaming = false)
                        }
                        chunk.tool_calls != null -> {
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
