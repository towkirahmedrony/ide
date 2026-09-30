package com.agentx.app.settings

import android.content.Context
import android.content.SharedPreferences
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.prompt.AgentPromptConfig
import com.agentx.app.agent.prompt.AgentPromptStore

/**
 * Persists user-customized agent prompts in app-private [SharedPreferences], the
 * same mechanism the rest of the IDE's settings already use — no second
 * database.
 *
 * Only overrides are written; the shipped defaults live in code, so a cleared
 * entry falls back to a working prompt. Prompts are user instructions, never
 * secrets, and nothing here reads the environment.
 */
class SharedPreferencesAgentPromptStore(
    context: Context,
    preferencesName: String = DEFAULT_PREFERENCES_NAME,
) : AgentPromptStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    override suspend fun loadAll(): List<AgentPromptConfig> =
        AgentRole.entries.mapNotNull { read(it) }

    override suspend fun upsert(config: AgentPromptConfig) {
        val key = config.role.name
        prefs.edit()
            .putBoolean(field(key, FIELD_CUSTOM), true)
            .putString(field(key, FIELD_PROMPT), config.prompt)
            .putBoolean(field(key, FIELD_ENABLED), config.enabled)
            .putString(field(key, FIELD_DESCRIPTION), config.description.orEmpty())
            .putString(field(key, FIELD_VERSION), config.version.orEmpty())
            .putLong(field(key, FIELD_UPDATED), config.updatedAtMillis)
            .apply()
    }

    override suspend fun delete(role: AgentRole) {
        val key = role.name
        prefs.edit()
            .remove(field(key, FIELD_CUSTOM))
            .remove(field(key, FIELD_PROMPT))
            .remove(field(key, FIELD_ENABLED))
            .remove(field(key, FIELD_DESCRIPTION))
            .remove(field(key, FIELD_VERSION))
            .remove(field(key, FIELD_UPDATED))
            .apply()
    }

    private fun read(role: AgentRole): AgentPromptConfig? {
        val key = role.name
        if (!prefs.getBoolean(field(key, FIELD_CUSTOM), false)) return null
        val prompt = prefs.getString(field(key, FIELD_PROMPT), null) ?: return null
        return AgentPromptConfig(
            role = role,
            prompt = prompt,
            enabled = prefs.getBoolean(field(key, FIELD_ENABLED), true),
            description = prefs.getString(field(key, FIELD_DESCRIPTION), null)?.takeIf { it.isNotBlank() },
            version = prefs.getString(field(key, FIELD_VERSION), null)?.takeIf { it.isNotBlank() },
            updatedAtMillis = prefs.getLong(field(key, FIELD_UPDATED), 0L),
            isCustom = true,
        )
    }

    private fun field(role: String, name: String): String = "prompts.$role.$name"

    companion object {
        const val DEFAULT_PREFERENCES_NAME: String = "forge.agent-prompts"

        private const val FIELD_CUSTOM = "custom"
        private const val FIELD_PROMPT = "prompt"
        private const val FIELD_ENABLED = "enabled"
        private const val FIELD_DESCRIPTION = "description"
        private const val FIELD_VERSION = "version"
        private const val FIELD_UPDATED = "updated"
    }
}
