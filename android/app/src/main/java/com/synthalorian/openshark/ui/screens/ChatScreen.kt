package com.synthalorian.openshark.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.synthalorian.openshark.command.Command
import com.synthalorian.openshark.ui.viewmodel.AgentMode
import com.synthalorian.openshark.ui.viewmodel.ChatViewModel
import com.synthalorian.openshark.ui.viewmodel.Message
import com.synthalorian.openshark.ui.viewmodel.ModelInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateToModels: () -> Unit,
    onNavigateToAgents: () -> Unit,
) {
    val messages by viewModel.messages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val currentModel by viewModel.currentModel.collectAsState()
    val agentMode by viewModel.agentMode.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val isDiscovering by viewModel.isDiscovering.collectAsState()
    val activeAgent by viewModel.activeAgent.collectAsState()
    val listState = rememberLazyListState()
    val keyboardController = LocalSoftwareKeyboardController.current

    var showModelPicker by remember { mutableStateOf(false) }
    var showAgentModePicker by remember { mutableStateOf(false) }

    // Auto-scroll to bottom
    LaunchedEffect(messages.size, messages.lastOrNull()?.content) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        val titleText = activeAgent?.let { "${it.emoji} ${it.displayName}" } ?: "OpenShark 🦈"
                        Text(titleText)
                        Text(
                            text = when (connectionStatus) {
                                is ChatViewModel.ConnectionStatus.Connected -> 
                                    "● $currentModel"
                                is ChatViewModel.ConnectionStatus.Connecting -> 
                                    if (isDiscovering) "Auto-discovering..." else "Connecting..."
                                is ChatViewModel.ConnectionStatus.Waiting ->
                                    "⏳ Waiting for server..."
                                is ChatViewModel.ConnectionStatus.Error -> 
                                    "● Offline"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = when (connectionStatus) {
                                is ChatViewModel.ConnectionStatus.Connected -> 
                                    MaterialTheme.colorScheme.primary
                                is ChatViewModel.ConnectionStatus.Waiting ->
                                    MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.error
                            }
                        )
                    }
                },
                actions = {
                    // Agent Switcher
                    IconButton(onClick = onNavigateToAgents) {
                        Text(
                            text = activeAgent?.emoji ?: "🦈",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    
                    // Agent Mode Toggle
                    IconButton(onClick = { showAgentModePicker = true }) {
                        Icon(
                            imageVector = when (agentMode) {
                                AgentMode.SAFE -> Icons.Default.Lock
                                AgentMode.FULL_SEND -> Icons.Default.Star
                            },
                            contentDescription = "Agent Mode",
                            tint = when (agentMode) {
                                AgentMode.SAFE -> MaterialTheme.colorScheme.primary
                                AgentMode.FULL_SEND -> MaterialTheme.colorScheme.tertiary
                            }
                        )
                    }
                    
                    // Reconnect / Discover button
                    if (connectionStatus !is ChatViewModel.ConnectionStatus.Connected) {
                        IconButton(onClick = { viewModel.discoverAndConnect() }) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Reconnect",
                                tint = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                    
                    // Model Picker
                    IconButton(onClick = { showModelPicker = true }) {
                        Icon(Icons.Default.Build, contentDescription = "Switch Model")
                    }
                    
                    // Settings
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                    
                    // More Menu
                    IconButton(onClick = { viewModel.showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    
                    DropdownMenu(
                        expanded = viewModel.showMenu,
                        onDismissRequest = { viewModel.showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("🆕 New Chat") },
                            leadingIcon = { Icon(Icons.Default.Add, null) },
                            onClick = {
                                viewModel.clearChat()
                                viewModel.showMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("🧠 Memory Search") },
                            leadingIcon = { Icon(Icons.Default.Search, null) },
                            onClick = {
                                viewModel.showMemorySearch = true
                                viewModel.showMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("📊 Export Chat") },
                            leadingIcon = { Icon(Icons.Default.Share, null) },
                            onClick = {
                                viewModel.exportChat()
                                viewModel.showMenu = false
                            }
                        )
                    }
                }
            )
        },
        bottomBar = {
            ChatInputBar(
                onSend = { message ->
                    viewModel.handleInput(message)
                    keyboardController?.hide()
                },
                isLoading = isLoading,
                placeholder = when (connectionStatus) {
                    is ChatViewModel.ConnectionStatus.Connected -> "Message or /command…"
                    is ChatViewModel.ConnectionStatus.Waiting -> "Waiting for server…"
                    else -> "Connect to send messages…"
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                // Connection status banner
                if (connectionStatus !is ChatViewModel.ConnectionStatus.Connected) {
                    item {
                        ConnectionStatusBanner(
                            status = connectionStatus,
                            isDiscovering = isDiscovering,
                            onRetry = { viewModel.discoverAndConnect() }
                        )
                    }
                }
                
                items(messages, key = { it.id }) { message ->
                    MessageBubble(message = message)
                }

                if (isLoading) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start
                        ) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier.padding(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Text(
                                        "OpenShark is thinking…",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Model Picker Dialog
    if (showModelPicker) {
        ModelPickerDialog(
            models = viewModel.availableModels.collectAsState().value,
            currentModel = currentModel,
            onModelSelected = { model ->
                viewModel.switchModel(model)
                showModelPicker = false
            },
            onDismiss = { showModelPicker = false }
        )
    }

    // Agent Mode Picker Dialog
    if (showAgentModePicker) {
        AgentModePickerDialog(
            currentMode = agentMode,
            onModeSelected = { mode ->
                viewModel.setAgentMode(mode)
                showAgentModePicker = false
            },
            onDismiss = { showAgentModePicker = false }
        )
    }

    // Memory Search Dialog
    if (viewModel.showMemorySearch) {
        MemorySearchDialog(
            onDismiss = { viewModel.showMemorySearch = false },
            onSearch = { query -> viewModel.searchMemory(query) }
        )
    }
}

@Composable
fun ConnectionStatusBanner(
    status: ChatViewModel.ConnectionStatus,
    isDiscovering: Boolean,
    onRetry: () -> Unit
) {
    val (backgroundColor, textColor, icon, message) = when (status) {
        is ChatViewModel.ConnectionStatus.Connecting -> Quad(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            Icons.Default.Refresh,
            if (isDiscovering) "🔍 Auto-discovering OpenShark server..." else "Connecting..."
        )
        is ChatViewModel.ConnectionStatus.Waiting -> Quad(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            Icons.Default.Info,
            "⏳ Waiting for OpenShark server. Start it in Termux or tap to retry."
        )
        is ChatViewModel.ConnectionStatus.Error -> Quad(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            Icons.Default.Warning,
            "⚠️ ${(status as ChatViewModel.ConnectionStatus.Error).message}"
        )
        else -> Quad(
            MaterialTheme.colorScheme.surface,
            MaterialTheme.colorScheme.onSurface,
            Icons.Default.Check,
            ""
        )
    }
    
    if (message.isEmpty()) return
    
    Surface(
        color = backgroundColor,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clickable { onRetry() }
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isDiscovering || status is ChatViewModel.ConnectionStatus.Connecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = textColor
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = textColor,
                    modifier = Modifier.size(18.dp)
                )
            }
            Text(
                text = message,
                color = textColor,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            if (!isDiscovering) {
                Text(
                    text = "↻ Retry",
                    color = textColor,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

// Helper data class for banner styling
data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

@Composable
fun MessageBubble(message: Message) {
    val isUser = message.role == "user"
    val bubbleColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val textColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier.widthIn(max = 340.dp),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            // Tool call indicator
            if (message.toolCall != null) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier.padding(bottom = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Icons.Default.Build,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            "Tool: ${message.toolCall.name}",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
            
            Surface(
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = if (isUser) 16.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 16.dp
                ),
                color = bubbleColor,
                modifier = Modifier.padding(2.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = message.content,
                        color = textColor,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    
                    // Streaming indicator
                    if (message.isStreaming) {
                        Box(
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .size(6.dp)
                                .background(
                                    MaterialTheme.colorScheme.primary,
                                    RoundedCornerShape(50)
                                )
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ChatInputBar(
    onSend: (String) -> Unit,
    isLoading: Boolean,
    placeholder: String = "Message or /command…"
) {
    var text by remember { mutableStateOf("") }
    var showAutocomplete by remember { mutableStateOf(false) }

    // Filter commands when typing /
    val filteredCommands = remember(text) {
        if (!text.startsWith("/")) {
            emptyList()
        } else {
            val query = text.substring(1).lowercase()
            Command.ALL.filter { cmd ->
                cmd.name.contains(query) || cmd.aliases.any { it.contains(query) }
            }
        }
    }
    showAutocomplete = filteredCommands.isNotEmpty()

    Box(modifier = Modifier.fillMaxWidth()) {
        // Autocomplete popup
        if (showAutocomplete) {
            Surface(
                tonalElevation = 6.dp,
                shadowElevation = 6.dp,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 72.dp)
                    .align(Alignment.BottomStart)
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .padding(vertical = 8.dp)
                ) {
                    items(filteredCommands, key = { it.name }) { cmd ->
                        CommandAutocompleteItem(
                            command = cmd,
                            onSelect = {
                                text = if (cmd.usage.isNotBlank()) {
                                    // Command takes args — position cursor after name + space
                                    "/${cmd.name} "
                                } else {
                                    // No args — submit immediately
                                    onSend("/${cmd.name}")
                                    ""
                                }
                                showAutocomplete = false
                            }
                        )
                    }
                }
            }
        }

        Surface(
            tonalElevation = 3.dp,
            shadowElevation = 3.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(placeholder) },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (text.isNotBlank() && !isLoading) {
                                onSend(text)
                                text = ""
                                showAutocomplete = false
                            }
                        }
                    ),
                    singleLine = false,
                    maxLines = 5,
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface
                    )
                )

                FilledIconButton(
                    onClick = {
                        if (text.isNotBlank() && !isLoading) {
                            onSend(text)
                            text = ""
                            showAutocomplete = false
                        }
                    },
                    enabled = text.isNotBlank() && !isLoading,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CommandAutocompleteItem(
    command: Command,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.padding(end = 4.dp)
        ) {
            Text(
                text = "/${command.name}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = command.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (command.aliases.isNotEmpty()) {
                Text(
                    text = command.aliases.joinToString(", ") { "/$it" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun ModelPickerDialog(
    models: List<ModelInfo>,
    currentModel: String,
    onModelSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Model") },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(models) { model ->
                    val isSelected = model.name == currentModel
                    ListItem(
                        headlineContent = { Text(model.name) },
                        supportingContent = {
                            Text(
                                "${model.provider} • ${model.contextLength} ctx • ${
                                    if (model.isLocal) "Local 🏠" else "Cloud ☁️"
                                }"
                            )
                        },
                        leadingContent = {
                            if (isSelected) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        },
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface
                            )
                            .clickable { onModelSelected(model.name) }
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun AgentModePickerDialog(
    currentMode: AgentMode,
    onModeSelected: (AgentMode) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Agent Mode") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AgentModeOption(
                    mode = AgentMode.SAFE,
                    title = "Safe Mode",
                    description = "Ask before executing tools. Best for sensitive operations.",
                    icon = Icons.Default.Lock,
                    isSelected = currentMode == AgentMode.SAFE,
                    onSelected = { onModeSelected(AgentMode.SAFE) }
                )
                AgentModeOption(
                    mode = AgentMode.FULL_SEND,
                    title = "Full Send",
                    description = "Execute tools automatically. Maximum autonomy.",
                    icon = Icons.Default.Star,
                    isSelected = currentMode == AgentMode.FULL_SEND,
                    onSelected = { onModeSelected(AgentMode.FULL_SEND) }
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
fun AgentModeOption(
    mode: AgentMode,
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    onSelected: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelected)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isSelected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun MemorySearchDialog(
    onDismiss: () -> Unit,
    onSearch: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var isSemantic by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🧠 Search Memory") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("What did we do about…") },
                    placeholder = { Text("e.g., auth module refactor") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Switch(
                        checked = isSemantic,
                        onCheckedChange = { isSemantic = it }
                    )
                    Text("Semantic search")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (query.isNotBlank()) {
                        onSearch(query)
                    }
                    onDismiss()
                }
            ) {
                Text("Search")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
