package com.synthalorian.openshark.command

sealed class Command(val name: String, val description: String, val usage: String = "") {
    abstract val aliases: List<String>

    // Connection & Config
    object Connect : Command(
        "connect",
        "Set server URL and test connection",
        "/connect <url>  (e.g., /connect http://192.168.1.42:9876)"
    ) {
        override val aliases = listOf("server", "url")
    }

    object Status : Command(
        "status",
        "Show connection and config status"
    ) {
        override val aliases = listOf("info", "whoami")
    }

    object Config : Command(
        "config",
        "Show current configuration"
    ) {
        override val aliases = listOf("settings", "prefs")
    }

    // Models
    object Model : Command(
        "model",
        "Switch active model",
        "/model <name>  (e.g., /model gemma4:e2b)"
    ) {
        override val aliases = listOf("m", "use")
    }

    object Models : Command(
        "models",
        "List available models"
    ) {
        override val aliases = listOf("list", "ls")
    }

    object Local : Command(
        "local",
        "Switch to local models only"
    ) {
        override val aliases = listOf("offline")
    }

    object Cloud : Command(
        "cloud",
        "Switch to cloud models"
    ) {
        override val aliases = listOf("online")
    }

    // Chat & Session
    object New : Command(
        "new",
        "Start a new chat session"
    ) {
        override val aliases = listOf("reset", "restart")
    }

    object Clear : Command(
        "clear",
        "Clear current chat history"
    ) {
        override val aliases = listOf("cls", "clean")
    }

    object Export : Command(
        "export",
        "Copy chat to clipboard"
    ) {
        override val aliases = listOf("copy", "save")
    }

    // Memory
    object Memory : Command(
        "memory",
        "Search conversation memory",
        "/memory <query>  (e.g., /memory auth refactor)"
    ) {
        override val aliases = listOf("search", "recall")
    }

    object Remember : Command(
        "remember",
        "Save text to memory",
        "/remember <text>"
    ) {
        override val aliases = listOf("mem", "note")
    }

    // Agent Mode
    object Safe : Command(
        "safe",
        "Enable safe mode (ask before tool execution)"
    ) {
        override val aliases = listOf("safe-mode")
    }

    object FullSend : Command(
        "fullsend",
        "Enable full send mode (auto-execute tools)"
    ) {
        override val aliases = listOf("full-send", "fs", "yolo")
    }

    // Tools
    object Tools : Command(
        "tools",
        "List available tools"
    ) {
        override val aliases = listOf("toolkit")
    }

    object Exec : Command(
        "exec",
        "Execute a tool directly",
        "/exec <name> <args>  (e.g., /exec shell ls -la)"
    ) {
        override val aliases = listOf("run", "tool")
    }

    // Agent / Identity
    object Agent : Command(
        "agent",
        "Switch active agent or manage agents",
        "/agent <name>  (e.g., /agent synthclaw)"
    ) {
        override val aliases = listOf("agents", "who", "identity")
    }

    object AgentList : Command(
        "agentlist",
        "List all available agents"
    ) {
        override val aliases = listOf("agents-list", "whoami-all")
    }

    object Soul : Command(
        "soul",
        "Show current agent's soul/persona"
    ) {
        override val aliases = listOf("persona", "whoareyou")
    }
    object Help : Command(
        "help",
        "Show available commands",
        "/help [command]  (e.g., /help model)"
    ) {
        override val aliases = listOf("h", "?", "commands")
    }

    companion object {
        val ALL = listOf(
            Connect, Status, Config,
            Model, Models, Local, Cloud,
            New, Clear, Export,
            Memory, Remember,
            Safe, FullSend,
            Tools, Exec,
            Agent, AgentList, Soul,
            Help
        )

        fun parse(input: String): Pair<Command, String>? {
            if (!input.startsWith("/")) return null
            
            val parts = input.trim().split(" ", limit = 2)
            val cmdName = parts[0].substring(1).lowercase()
            val args = parts.getOrElse(1) { "" }.trim()

            return ALL.find { cmd ->
                cmd.name == cmdName || cmd.aliases.any { it == cmdName }
            }?.let { it to args }
        }

        fun getHelpText(command: Command? = null): String {
            if (command != null) {
                return buildString {
                    appendLine("**/${command.name}** — ${command.description}")
                    if (command.usage.isNotBlank()) {
                        appendLine("Usage: `${command.usage}`")
                    }
                    if (command.aliases.isNotEmpty()) {
                        appendLine("Aliases: ${command.aliases.joinToString(", ") { "/$it" }}")
                    }
                }
            }

            return buildString {
                appendLine("🦈 **OpenShark Commands**")
                appendLine()
                
                appendLine("**Connection**")
                appendLine("`/connect <url>` — Set server URL")
                appendLine("`/status` — Connection status")
                appendLine("`/config` — Current configuration")
                appendLine()
                
                appendLine("**Models**")
                appendLine("`/model <name>` — Switch model")
                appendLine("`/models` — List available models")
                appendLine("`/local` — Use local models only")
                appendLine("`/cloud` — Use cloud models")
                appendLine()
                
                appendLine("**Chat**")
                appendLine("`/new` — New chat session")
                appendLine("`/clear` — Clear chat history")
                appendLine("`/export` — Copy chat to clipboard")
                appendLine()
                
                appendLine("**Memory**")
                appendLine("`/memory <query>` — Search memories")
                appendLine("`/remember <text>` — Save to memory")
                appendLine()
                
                appendLine("**Agent Mode**")
                appendLine("`/safe` — Ask before executing tools")
                appendLine("`/fullsend` — Auto-execute tools")
                appendLine()
                
                appendLine("**Tools**")
                appendLine("`/tools` — List available tools")
                appendLine("`/exec <name> <args>` — Execute tool")
                appendLine()
                
                appendLine("**Agent / Identity**")
                appendLine("`/agent <name>` — Switch active agent")
                appendLine("`/agentlist` — List all agents")
                appendLine("`/soul` — Show current agent's persona")
                appendLine()
                
                appendLine("**Help**")
                appendLine("`/help [command]` — Show command help")
                appendLine()
                
                appendLine("Type `/help <command>` for detailed usage.")
            }
        }
    }
}
