package com.synthalorian.openshark.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.synthalorian.openshark.ui.viewmodel.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: ChatViewModel = viewModel()
) {
    var serverUrl by remember { mutableStateOf(viewModel.getServerUrl()) }
    var testStatus by remember { mutableStateOf("") }
    var isTesting by remember { mutableStateOf(false) }

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
            // Server URL
            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = { Text("OpenShark Server URL") },
                placeholder = { Text("http://127.0.0.1:9876") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done
                ),
                modifier = Modifier.fillMaxWidth()
            )

            // Test Connection
            Button(
                onClick = {
                    isTesting = true
                    testStatus = "Testing..."
                    viewModel.setServerUrl(serverUrl)
                    // In real app, you'd do an actual health check
                    isTesting = false
                    testStatus = "✅ Connected (simulated)"
                },
                enabled = !isTesting && serverUrl.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isTesting) "Testing..." else "Test Connection")
            }

            if (testStatus.isNotEmpty()) {
                Text(
                    text = testStatus,
                    color = if (testStatus.startsWith("✅")) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            }

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            // Info
            Text(
                text = "About OpenShark Mobile",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "OpenShark is an open-source AI coding harness. This mobile app connects to your local OpenShark server running in Termux.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.weight(1f))

            // Save button
            Button(
                onClick = {
                    viewModel.setServerUrl(serverUrl)
                    onNavigateBack()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save Settings")
            }
        }
    }
}
