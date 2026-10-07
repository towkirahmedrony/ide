package com.agentx.app.settings

import android.content.Context
import android.content.SharedPreferences
import com.agentx.app.tools.ToolPreferenceStore

/**
 * Persists the set of tools the user turned off in Settings → Tools, in
 * app-private [SharedPreferences] — the same mechanism the rest of the IDE's
 * settings already use, so tool choices survive screen recreation and restarts
 * without a second database.
 *
 * Only disabled ids are stored; an empty set means "every tool is on", so a
 * cleared entry always falls back to the working default. Nothing here is a
 * secret and nothing reads the environment.
 */
class SharedPreferencesToolPreferenceStore(
    context: Context,
    preferencesName: String = DEFAULT_PREFERENCES_NAME,
) : ToolPreferenceStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    override suspend fun load(): Set<String> =
        prefs.getStringSet(KEY_DISABLED, emptySet()).orEmpty().toSet()

    override suspend fun save(disabled: Set<String>) {
        // A copy is stored: SharedPreferences retains the instance it is given and
        // must not alias the caller's live set.
        prefs.edit().putStringSet(KEY_DISABLED, disabled.toSet()).apply()
    }

    companion object {
        const val DEFAULT_PREFERENCES_NAME: String = "forge.tools"

        private const val KEY_DISABLED = "tools.disabled"
    }
}
