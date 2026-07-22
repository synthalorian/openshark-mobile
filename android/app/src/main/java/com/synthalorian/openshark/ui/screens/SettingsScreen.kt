package com.synthalorian.openshark.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.synthalorian.openshark.data.remote.ServerDiscovery
import com.synthalorian.openshark.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: ChatViewModel,
    onNavigateBack: () -> Unit,
) {
    var serverUrl by remember { mutableStateOf(viewModel.getServerUrl()) }
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val isDiscovering by viewModel.isDiscovering.collectAsState()
    val discoveredUrls by viewModel.discoveredUrls.collectAsState()
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf("") }
    var testSuccess by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Observe real connection status changes from the ViewModel
    LaunchedEffect(connectionStatus) {
        when (connectionStatus) {
            is ChatViewModel.ConnectionStatus.Connected -> {
                if (isTesting) {
                    testResult = "Connected successfully"
                    testSuccess = true
                    isTesting = false
                }
            }
            is ChatViewModel.ConnectionStatus.Error -> {
                if (isTesting) {
                    testResult = (connectionStatus as ChatViewModel.ConnectionStatus.Error).message
                    testSuccess = false
                    isTesting = false
                }
            }
            else -> {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            // Auto-discovery card
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Text(
                            "Auto-Connect",
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                    Text(
                        "The app automatically discovers your OpenShark server. " +
                        "If auto-connect fails, you can set a custom URL below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Button(
                        onClick = { viewModel.discoverAndConnect() },
                        enabled = !isDiscovering,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isDiscovering) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Discovering...")
                        } else {
                            Text("↻ Rediscover Server")
                        }
                    }
                }
            }

            // Discovered URLs (if any)
            if (discoveredUrls.isNotEmpty()) {
                Text(
                    "Discovered URLs:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                discoveredUrls.forEach { url ->
                    val isCurrent = url == serverUrl
                    Surface(
                        color = if (isCurrent)
                            MaterialTheme.colorScheme.secondaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                serverUrl = url
                                viewModel.setServerUrl(url)
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                url,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            if (isCurrent) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "Active",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            Divider()

            // Server URL
            OutlinedTextField(
                value = serverUrl,
                onValueChange = {
                    serverUrl = it
                    testResult = "" // Clear test result when user edits
                },
                label = { Text("OpenShark Server URL") },
                placeholder = { Text("http://192.168.1.42:9876") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done
                ),
                modifier = Modifier.fillMaxWidth(),
                isError = serverUrl.isNotBlank() && !isValidUrl(serverUrl),
                supportingText = {
                    if (serverUrl.isNotBlank() && !isValidUrl(serverUrl)) {
                        Text("URL must start with http:// or https://")
                    }
                }
            )

            // Help card
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null)
                        Text(
                            "Finding your server URL",
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "• Server on this phone (Termux): http://127.0.0.1:9876\n" +
                        "• Server on your computer: http://COMPUTER_IP:9876\n" +
                        "• Find computer IP: ip addr or ifconfig",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            // Test Connection
            Button(
                onClick = {
                    if (!isValidUrl(serverUrl)) return@Button
                    isTesting = true
                    testResult = ""
                    viewModel.setServerUrl(serverUrl)
                },
                enabled = !isTesting && serverUrl.isNotBlank() && isValidUrl(serverUrl),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isTesting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(if (isTesting) "Testing..." else "Test Connection")
            }

            // Test result
            if (testResult.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (testSuccess)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (testSuccess) Icons.Default.Check else Icons.Default.Clear,
                            contentDescription = null,
                            tint = if (testSuccess)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.error
                        )
                        Text(
                            testResult,
                            color = if (testSuccess)
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else
                                MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            // Current connection status
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val statusText = when (connectionStatus) {
                    is ChatViewModel.ConnectionStatus.Connected -> "Connected"
                    is ChatViewModel.ConnectionStatus.Connecting -> "Connecting..."
                    is ChatViewModel.ConnectionStatus.Waiting -> "Waiting for server..."
                    is ChatViewModel.ConnectionStatus.Error -> "Disconnected"
                }
                val statusColor = when (connectionStatus) {
                    is ChatViewModel.ConnectionStatus.Connected ->
                        MaterialTheme.colorScheme.primary
                    is ChatViewModel.ConnectionStatus.Waiting ->
                        MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.error
                }
                Text("Status:", style = MaterialTheme.typography.bodyMedium)
                Text(statusText, color = statusColor, style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(modifier = Modifier.weight(1f))

            // Save button
            Button(
                onClick = {
                    viewModel.setServerUrl(serverUrl)
                    onNavigateBack()
                },
                enabled = isValidUrl(serverUrl),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save & Return")
            }
        }
    }
}

private fun isValidUrl(url: String): Boolean {
    return url.startsWith("http://") || url.startsWith("https://")
}
