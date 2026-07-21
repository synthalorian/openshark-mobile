package com.synthalorian.openshark.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.synthalorian.openshark.data.model.Agent
import com.synthalorian.openshark.data.model.AgentVoice
import com.synthalorian.openshark.ui.viewmodel.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentsScreen(
    viewModel: ChatViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToEditor: (String?) -> Unit = {}
) {
    val agents by viewModel.agents.collectAsState()
    val activeAgent by viewModel.activeAgent.collectAsState()
    var showDeleteDialog by remember { mutableStateOf<Agent?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agents") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { onNavigateToEditor(null) }) {
                        Icon(Icons.Default.Add, contentDescription = "New Agent")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item {
                Text(
                    "Active Agent",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            item {
                activeAgent?.let { agent ->
                    ActiveAgentCard(agent = agent, onEdit = { onNavigateToEditor(agent.id) })
                }
            }

            item {
                Divider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    "All Agents",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            items(agents, key = { it.id }) { agent ->
                val isActive = agent.id == activeAgent?.id
                AgentCard(
                    agent = agent,
                    isActive = isActive,
                    onActivate = { viewModel.agentRepository.setActiveAgent(agent) },
                    onEdit = { onNavigateToEditor(agent.id) },
                    onDelete = { if (!agent.isDefault) showDeleteDialog = agent }
                )
            }
        }
    }

    showDeleteDialog?.let { agent ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("Delete Agent") },
            text = { Text("Are you sure you want to delete ${agent.displayName}? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.agentRepository.deleteAgent(agent.id)
                        showDeleteDialog = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun ActiveAgentCard(agent: Agent, onEdit: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = agent.emoji,
                style = MaterialTheme.typography.headlineMedium
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    agent.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    agent.tagline,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
                Text(
                    "Voice: ${agent.voice.name.lowercase().replaceFirstChar { it.uppercase() }}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Edit")
            }
        }
    }
}

@Composable
private fun AgentCard(
    agent: Agent,
    isActive: Boolean,
    onActivate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        onClick = onActivate,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive)
                MaterialTheme.colorScheme.secondaryContainer
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = agent.emoji,
                style = MaterialTheme.typography.headlineSmall
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    agent.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isActive)
                        MaterialTheme.colorScheme.onSecondaryContainer
                    else
                        MaterialTheme.colorScheme.onSurface
                )
                Text(
                    agent.tagline,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (agent.isDefault) {
                    Text(
                        "Built-in",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            if (isActive) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Active",
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            IconButton(onClick = onEdit) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "Edit",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!agent.isDefault) {
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentEditorScreen(
    viewModel: ChatViewModel,
    agentId: String?,
    onNavigateBack: () -> Unit
) {
    val agents by viewModel.agents.collectAsState()
    val existingAgent = agentId?.let { id -> agents.find { it.id == id } }

    var name by remember { mutableStateOf(existingAgent?.name ?: "") }
    var displayName by remember { mutableStateOf(existingAgent?.displayName ?: "") }
    var emoji by remember { mutableStateOf(existingAgent?.emoji ?: "🤖") }
    var tagline by remember { mutableStateOf(existingAgent?.tagline ?: "") }
    var soul by remember { mutableStateOf(existingAgent?.soul ?: "") }
    var systemPrompt by remember { mutableStateOf(existingAgent?.systemPrompt ?: "") }
    var voice by remember { mutableStateOf(existingAgent?.voice ?: AgentVoice.BALANCED) }
    var showVoicePicker by remember { mutableStateOf(false) }

    val isEditing = existingAgent != null
    val canSave = name.isNotBlank() && displayName.isNotBlank()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEditing) "Edit Agent" else "New Agent") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            val agent = if (isEditing && existingAgent != null) {
                                existingAgent.copy(
                                    name = name.lowercase().replace(" ", "_"),
                                    displayName = displayName,
                                    emoji = emoji,
                                    tagline = tagline,
                                    soul = soul,
                                    systemPrompt = systemPrompt,
                                    voice = voice
                                )
                            } else {
                                Agent(
                                    name = name.lowercase().replace(" ", "_"),
                                    displayName = displayName,
                                    emoji = emoji,
                                    tagline = tagline,
                                    soul = soul,
                                    systemPrompt = systemPrompt,
                                    voice = voice
                                )
                            }

                            if (isEditing) {
                                viewModel.agentRepository.updateAgent(agent)
                            } else {
                                viewModel.agentRepository.addAgent(agent)
                            }
                            onNavigateBack()
                        },
                        enabled = canSave
                    ) {
                        Text("Save")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Emoji picker (simple text field for now)
            OutlinedTextField(
                value = emoji,
                onValueChange = { if (it.length <= 2) emoji = it },
                label = { Text("Emoji") },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text("Display Name") },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Internal Name") },
                supportingText = { Text("Used in /agent command. Auto-formatted.") },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = tagline,
                onValueChange = { tagline = it },
                label = { Text("Tagline") },
                placeholder = { Text("Short description shown in agent list") },
                modifier = Modifier.fillMaxWidth()
            )

            // Voice picker
            OutlinedTextField(
                value = voice.name.lowercase().replaceFirstChar { it.uppercase() },
                onValueChange = {},
                label = { Text("Voice") },
                readOnly = true,
                trailingIcon = {
                    IconButton(onClick = { showVoicePicker = true }) {
                        Icon(Icons.Default.ArrowDropDown, contentDescription = "Select Voice")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = systemPrompt,
                onValueChange = { systemPrompt = it },
                label = { Text("System Prompt") },
                placeholder = { Text("Instructions sent with every message") },
                minLines = 3,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = soul,
                onValueChange = { soul = it },
                label = { Text("Soul / Persona") },
                placeholder = { Text("Full personality description. Shown with /soul command.") },
                minLines = 5,
                maxLines = 10,
                modifier = Modifier.fillMaxWidth()
            )

            if (showVoicePicker) {
                AlertDialog(
                    onDismissRequest = { showVoicePicker = false },
                    title = { Text("Select Voice") },
                    text = {
                        Column {
                            AgentVoice.entries.forEach { v ->
                                ListItem(
                                    headlineContent = { Text(v.name.lowercase().replaceFirstChar { it.uppercase() }) },
                                    modifier = Modifier.clickable {
                                        voice = v
                                        showVoicePicker = false
                                    }
                                )
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = {
                        TextButton(onClick = { showVoicePicker = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }
        }
    }
}
