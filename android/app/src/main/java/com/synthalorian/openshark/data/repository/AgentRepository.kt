package com.synthalorian.openshark.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.synthalorian.openshark.data.model.Agent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AgentRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("openshark_agents", Context.MODE_PRIVATE)
    private val gson = Gson()

    private val _agents = MutableStateFlow<List<Agent>>(emptyList())
    val agents: StateFlow<List<Agent>> = _agents.asStateFlow()

    private val _activeAgent = MutableStateFlow<Agent?>(null)
    val activeAgent: StateFlow<Agent?> = _activeAgent.asStateFlow()

    init {
        loadAgents()
    }

    private fun loadAgents() {
        val json = prefs.getString("agents_list", null)
        val agents = if (json != null) {
            val type = object : TypeToken<List<Agent>>() {}.type
            gson.fromJson<List<Agent>>(json, type) ?: emptyList()
        } else {
            // First run — seed with defaults
            val defaults = Agent.getDefaults()
            saveAgents(defaults)
            defaults
        }
        _agents.value = agents

        // Load active agent
        val activeId = prefs.getString("active_agent_id", null)
        _activeAgent.value = if (activeId != null) {
            agents.find { it.id == activeId } ?: agents.firstOrNull()
        } else {
            agents.firstOrNull()
        }
    }

    private fun saveAgents(agents: List<Agent>) {
        val json = gson.toJson(agents)
        prefs.edit().putString("agents_list", json).apply()
        _agents.value = agents
    }

    fun setActiveAgent(agent: Agent) {
        prefs.edit().putString("active_agent_id", agent.id).apply()
        _activeAgent.value = agent
    }

    fun addAgent(agent: Agent) {
        val current = _agents.value.toMutableList()
        current.add(agent)
        saveAgents(current)
    }

    fun updateAgent(updated: Agent) {
        val current = _agents.value.map { agent ->
            if (agent.id == updated.id) updated else agent
        }
        saveAgents(current)
        if (_activeAgent.value?.id == updated.id) {
            _activeAgent.value = updated
        }
    }

    fun deleteAgent(agentId: String) {
        val current = _agents.value.filter { it.id != agentId && !it.isDefault }
        saveAgents(current)
        if (_activeAgent.value?.id == agentId) {
            _activeAgent.value = current.firstOrNull()
            current.firstOrNull()?.let { prefs.edit().putString("active_agent_id", it.id).apply() }
        }
    }

    fun getAgentByName(name: String): Agent? {
        return _agents.value.find { it.name.equals(name, ignoreCase = true) }
    }

    fun resetToDefaults() {
        val defaults = Agent.getDefaults()
        saveAgents(defaults)
        _activeAgent.value = defaults.firstOrNull()
        prefs.edit().putString("active_agent_id", defaults.firstOrNull()?.id).apply()
    }

    fun duplicateAgent(agent: Agent, newName: String): Agent {
        val copy = agent.copy(
            id = java.util.UUID.randomUUID().toString(),
            name = newName.lowercase().replace(" ", "_"),
            displayName = newName,
            isDefault = false,
            createdAt = System.currentTimeMillis()
        )
        addAgent(copy)
        return copy
    }
}
