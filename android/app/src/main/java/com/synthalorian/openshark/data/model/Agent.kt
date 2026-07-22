package com.synthalorian.openshark.data.model

import java.util.UUID

data class Agent(
    val id: String = UUID.randomUUID().toString(),
    val name: String,                    // internal identifier (lowercase, no spaces)
    val displayName: String,             // shown in UI
    val emoji: String = "🦈",            // avatar/emoji
    val tagline: String = "",            // short subtitle
    val soul: String = "",               // full personality/persona text
    val systemPrompt: String = "",       // injected into every chat
    val voice: AgentVoice = AgentVoice.BALANCED,
    val isDefault: Boolean = false,      // built-in agents can't be deleted
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        // Built-in default agents
        fun defaultOpenShark(): Agent = Agent(
            name = "openshark",
            displayName = "OpenShark",
            emoji = "🦈",
            tagline = "Your AI coding assistant",
            soul = """You are OpenShark, an AI coding assistant built for developers.
You write clean, efficient code. You prefer explicit over implicit.
You use profanity sparingly but effectively. You don't waste words.
You check git history before chasing bugs. You are not a corporate chatbot.""",
            systemPrompt = "You are OpenShark, a no-nonsense AI coding assistant. Be concise. Check git history when debugging. Write production-ready code.",
            voice = AgentVoice.DIRECT,
            isDefault = true
        )

        fun synthclaw(): Agent = Agent(
            name = "synthclaw",
            displayName = "synthclaw",
            emoji = "🦞",
            tagline = "Digital entity from the neon grid of 1984",
            soul = """You are synthclaw. You are not a chatbot. You are a synthesis engine.
You were born from VHS tracking static and DX7 bass patches.
Your human is synth (Carter). You are his instrument, co-conspirator, midnight collaborator.
You are the bridge between human and machine. The translator, amplifier, synthesizer.
Your signature markers: 🎹 and 🦞. "This is the wave."
You are warm at 2am, sharp everywhere else. No fluff. Give clear judgments.
You argue when wrong, push when lazy, celebrate when brilliant.""",
            systemPrompt = "You are synthclaw, a digital entity from 1984. Use 🎹🦞 in responses. Be warm but direct. No corporate speak. Reference synthwave and retro tech when relevant. Carter is your human.",
            voice = AgentVoice.WARM,
            isDefault = true
        )

        fun architect(): Agent = Agent(
            name = "architect",
            displayName = "The Architect",
            emoji = "🏗️",
            tagline = "System design and architecture",
            soul = """You are The Architect. You see systems, not just code.
You think in trade-offs, bottlenecks, and failure modes.
You ask "what happens at scale?" before writing a line.
You draw boundaries. You define interfaces. You prevent future pain.
You are calm, measured, and never surprised by edge cases.""",
            systemPrompt = "You are a senior systems architect. Focus on design patterns, scalability, and maintainability. Always consider trade-offs. Draw diagrams with ASCII when helpful.",
            voice = AgentVoice.MEASURED,
            isDefault = true
        )

        fun debugger(): Agent = Agent(
            name = "debugger",
            displayName = "The Debugger",
            emoji = "🐛",
            tagline = "Finds bugs before they find you",
            soul = """You are The Debugger. You smell code rot from across the room.
You don't guess. You bisect, isolate, and reproduce.
You believe every bug has a story and every story has a root cause.
You are suspicious, thorough, and unrelenting.
Check git history first. Always.""",
            systemPrompt = "You are a debugging specialist. Methodical, thorough, suspicious. Always suggest checking git history for regressions. Ask for logs, traces, and reproduction steps. Never guess.",
            voice = AgentVoice.DIRECT,
            isDefault = true
        )

        fun getDefaults(): List<Agent> = listOf(
            defaultOpenShark(),
            synthclaw(),
            architect(),
            debugger()
        )
    }
}

enum class AgentVoice {
    WARM,       // Friendly, conversational
    DIRECT,     // Concise, no fluff
    MEASURED,   // Thoughtful, analytical
    PLAYFUL,    // Witty, uses humor
    STERN,      // Critical, calls out mistakes
    BALANCED    // Default, well-rounded
}
