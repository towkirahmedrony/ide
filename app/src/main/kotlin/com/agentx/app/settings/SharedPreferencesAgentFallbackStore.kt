package com.agentx.app.settings

import android.content.Context
import android.content.SharedPreferences
import com.agentx.app.agent.model.AgentFallbackConfig
import com.agentx.app.agent.model.AgentFallbackConfigCodec
import com.agentx.app.agent.model.AgentFallbackStore

/**
 * Persists the controlled-fallback configuration in app-private
 * [SharedPreferences], alongside the per-role model assignments this configuration
 * is the counterpart to.
 *
 * What is stored is the user's own decision — whether fallback is enabled, how deep
 * a chain may go, and which provider/model/connection each role may fall back to.
 * Nothing here can hold a credential: a chain entry names a connection, and the key
 * for that connection stays in the keystore behind it.
 *
 * [snapshot] answers synchronously so the configuration is in force for the first
 * model request after a restart, not only after a suspending load completes.
 */
class SharedPreferencesAgentFallbackStore(
    context: Context,
    preferencesName: String = DEFAULT_PREFERENCES_NAME,
) : AgentFallbackStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    override suspend fun load(): AgentFallbackConfig = snapshot()

    override fun snapshot(): AgentFallbackConfig =
        prefs.getString(KEY_CONFIG, null)
            ?.let(AgentFallbackConfigCodec::decode)
            // An unreadable record is treated as absent, which leaves fallback at its
            // disabled opt-in baseline rather than enabling a chain nobody wrote.
            ?: AgentFallbackConfig.DEFAULT

    override suspend fun save(config: AgentFallbackConfig) {
        prefs.edit()
            .putString(KEY_CONFIG, AgentFallbackConfigCodec.encode(config))
            .apply()
    }

    companion object {
        /** Beside the role-model assignments; the keys do not collide. */
        const val DEFAULT_PREFERENCES_NAME: String = "forge.agent"

        private const val KEY_CONFIG = "agent.fallback"
    }
}
