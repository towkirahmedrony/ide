package com.agentx.app.settings

import android.content.Context
import android.content.SharedPreferences
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.model.AgentRoleModelStore
import com.agentx.app.agent.model.RoleModelConfig

/**
 * Persists the per-role model assignment in app-private [SharedPreferences], the
 * same mechanism the rest of the IDE's settings already use — no second database.
 *
 * Only the role's provider id, model id and the connection it was chosen from are
 * written. No credential, endpoint or other secret is ever stored here; those stay
 * with the provider connection. A role with no stored entry keeps the built-in
 * default, so an older installation without role-specific settings is unaffected.
 */
class SharedPreferencesAgentRoleModelStore(
    context: Context,
    preferencesName: String = DEFAULT_PREFERENCES_NAME,
) : AgentRoleModelStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    override suspend fun loadAll(): List<RoleModelConfig> =
        AgentRole.entries.mapNotNull { read(it) }

    override suspend fun upsert(config: RoleModelConfig) {
        val key = config.role.name
        prefs.edit()
            .putBoolean(field(key, FIELD_CUSTOM), true)
            .putString(field(key, FIELD_PROVIDER), config.providerId)
            .putString(field(key, FIELD_MODEL), config.model.orEmpty())
            .putString(field(key, FIELD_CONNECTION), config.connectionId.orEmpty())
            .putLong(field(key, FIELD_UPDATED), config.updatedAtMillis)
            .apply()
    }

    override suspend fun delete(role: AgentRole) {
        val key = role.name
        prefs.edit()
            .remove(field(key, FIELD_CUSTOM))
            .remove(field(key, FIELD_PROVIDER))
            .remove(field(key, FIELD_MODEL))
            .remove(field(key, FIELD_CONNECTION))
            .remove(field(key, FIELD_UPDATED))
            .apply()
    }

    private fun read(role: AgentRole): RoleModelConfig? {
        val key = role.name
        if (!prefs.getBoolean(field(key, FIELD_CUSTOM), false)) return null
        val providerId = prefs.getString(field(key, FIELD_PROVIDER), null)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return RoleModelConfig(
            role = role,
            providerId = providerId,
            model = prefs.getString(field(key, FIELD_MODEL), null)?.takeIf { it.isNotBlank() },
            connectionId = prefs.getString(field(key, FIELD_CONNECTION), null)?.takeIf { it.isNotBlank() },
            updatedAtMillis = prefs.getLong(field(key, FIELD_UPDATED), 0L),
        )
    }

    private fun field(role: String, name: String): String = "roleModels.$role.$name"

    companion object {
        const val DEFAULT_PREFERENCES_NAME: String = "forge.agent-models"

        private const val FIELD_CUSTOM = "custom"
        private const val FIELD_PROVIDER = "provider"
        private const val FIELD_MODEL = "model"
        private const val FIELD_CONNECTION = "connection"
        private const val FIELD_UPDATED = "updated"
    }
}
