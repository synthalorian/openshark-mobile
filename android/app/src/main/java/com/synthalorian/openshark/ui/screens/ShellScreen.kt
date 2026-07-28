package com.synthalorian.openshark.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.synthalorian.openshark.service.GatewayManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

private enum class ShellMode(val label: String) {
    GATEWAY("🦈 Gateway"),
    DEVICE("📱 Device")
}

private data class TermLine(val kind: Kind, val text: String) {
    enum class Kind { CMD, OUT, ERR, SYS }
}

/**
 * Embedded terminal.
 *
 * Gateway mode: commands execute through the embedded OpenShark gateway's
 * shell tool (POST /v1/tools/execute) — the agent's own exec path.
 * Device mode: commands run directly in /system/bin/sh.
 * Both keep a persistent cwd; cd/pwd are built-ins.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShellScreen(onNavigateBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val lines = remember { mutableStateListOf<TermLine>() }
    var input by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(if (GatewayManager.running) ShellMode.GATEWAY else ShellMode.DEVICE) }
    var running by remember { mutableStateOf(false) }
    var cwd by remember { mutableStateOf(File("/data/local/tmp").let { if (it.isDirectory) it.absolutePath else "/" }) }
    val history = remember { mutableStateListOf<String>() }
    val listState = rememberLazyListState()

    fun push(kind: TermLine.Kind, text: String) {
        lines.add(TermLine(kind, text))
        if (lines.size > 2000) lines.removeRange(0, lines.size - 2000)
        scope.launch { listState.animateScrollToItem(maxOf(0, lines.size - 1)) }
    }

    suspend fun execGateway(cmd: String) {
        try {
            val body = JSONObject()
                .put("name", "shell")
                .put("args", cmd)
                .toString()
            val req = okhttp3.Request.Builder()
                .url("${GatewayManager.BASE_URL}/v1/tools/execute")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            val client = okhttp3.OkHttpClient.Builder()
                .callTimeout(70, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            client.newCall(req).execute().use { resp ->
                val json = JSONObject(resp.body?.string() ?: "{}")
                val result = json.optString("result")
                val error = json.optString("error").takeIf { it.isNotEmpty() && it != "null" }
                if (result.isNotEmpty()) push(TermLine.Kind.OUT, result.trimEnd('\n'))
                if (error != null) push(TermLine.Kind.ERR, error)
            }
        } catch (e: Exception) {
            push(TermLine.Kind.ERR, "gateway exec failed: ${e.message}")
        }
    }

    suspend fun execDevice(cmd: String) {
        val trimmed = cmd.trim()
        // Builtins (persistent cwd lives here in device mode)
        if (trimmed == "pwd") { push(TermLine.Kind.OUT, cwd); return }
        if (trimmed == "cd" || trimmed.startsWith("cd ")) {
            val target = trimmed.removePrefix("cd").trim()
            val newDir = when {
                target.isEmpty() || target == "~" -> File(cwd)
                target.startsWith("/") -> File(target)
                else -> File(cwd, target)
            }
            if (newDir.isDirectory) {
                cwd = newDir.canonicalPath
            } else {
                push(TermLine.Kind.ERR, "cd: no such directory: $target")
            }
            return
        }
        try {
            val proc = ProcessBuilder("/system/bin/sh", "-c", cmd)
                .directory(File(cwd))
                .redirectErrorStream(true)
                .apply {
                    environment()["PATH"] = "/system/bin:/system/xbin:/product/bin:/vendor/bin"
                }
                .start()
            val output = proc.inputStream.bufferedReader().readText()
            val done = proc.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)
            if (output.isNotEmpty()) push(TermLine.Kind.OUT, output.trimEnd('\n'))
            if (!done) {
                proc.destroyForcibly()
                push(TermLine.Kind.ERR, "command timed out after 60s")
            } else if (proc.exitValue() != 0) {
                push(TermLine.Kind.ERR, "exit code ${proc.exitValue()}")
            }
        } catch (e: Exception) {
            push(TermLine.Kind.ERR, "exec failed: ${e.message}")
        }
    }

    fun run() {
        val cmd = input.trim()
        if (cmd.isEmpty() || running) return
        input = ""
        history.add(0, cmd)
        push(TermLine.Kind.CMD, cmd)
        running = true
        scope.launch {
            withContext(Dispatchers.IO) {
                if (mode == ShellMode.GATEWAY) execGateway(cmd) else execDevice(cmd)
            }
            running = false
        }
    }

    LaunchedEffect(Unit) {
        push(TermLine.Kind.SYS, "OpenShark embedded shell")
        push(TermLine.Kind.SYS, "Gateway mode = exec through the embedded gateway (agent tool path)")
        push(TermLine.Kind.SYS, "Device mode = direct /system/bin/sh. cwd persists; cd/pwd built-ins.")
        if (!GatewayManager.running) {
            push(TermLine.Kind.SYS, "⚠ gateway not running — device mode only")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Shell") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    Row(
                        modifier = Modifier.padding(end = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        ShellMode.entries.forEach { m ->
                            FilterChip(
                                selected = mode == m,
                                onClick = {
                                    if (m == ShellMode.GATEWAY && !GatewayManager.running) {
                                        push(TermLine.Kind.SYS, "⚠ gateway not running")
                                    } else {
                                        mode = m
                                    }
                                },
                                label = { Text(m.label, fontSize = 12.sp) }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFF0B0E14))
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                items(lines) { line ->
                    when (line.kind) {
                        TermLine.Kind.CMD -> Text(
                            "$ ${line.text}",
                            color = Color(0xFF00E5FF),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp
                        )
                        TermLine.Kind.OUT -> Text(
                            line.text,
                            color = Color(0xFFC8D0E0),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        )
                        TermLine.Kind.ERR -> Text(
                            line.text,
                            color = Color(0xFFFF5F6D),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp
                        )
                        TermLine.Kind.SYS -> Text(
                            line.text,
                            color = Color(0xFF5C667A),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    }
                }
                if (running) {
                    item {
                        Text("…", color = Color(0xFF5C667A), fontFamily = FontFamily.Monospace)
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("$ ", fontFamily = FontFamily.Monospace) },
                    textStyle = LocalTextStyle.current.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp
                    ),
                    singleLine = true,
                    enabled = !running,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { run() })
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(onClick = { run() }, enabled = !running && input.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Run")
                }
            }
        }
    }
}
