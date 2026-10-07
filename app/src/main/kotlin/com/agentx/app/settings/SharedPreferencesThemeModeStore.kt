package com.agentx.app.settings

import android.content.Context
import android.content.SharedPreferences
import com.agentx.app.ui.theme.ThemeMode
import com.agentx.app.ui.theme.ThemeModeStore

/**
 * Persists the theme mode chosen in Settings → Appearance, in app-private
 * [SharedPreferences] — the same mechanism the rest of the IDE's settings
 * already use, so the selection survives screen recreation and restarts without
 * a second database.
 *
 * A missing or unrecognized value decodes to [ThemeMode.DARK], the appearance
 * AgentX has always shipped with. Nothing here is a secret and nothing reads
 * the environment.
 */
class SharedPreferencesThemeModeStore(
    context: Context,
    preferencesName: String = DEFAULT_PREFERENCES_NAME,
) : ThemeModeStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    override suspend fun load(): ThemeMode =
        ThemeMode.fromStored(prefs.getString(KEY_THEME_MODE, null))

    override suspend fun save(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.stored).apply()
    }

    companion object {
        const val DEFAULT_PREFERENCES_NAME: String = "forge.appearance"

        private const val KEY_THEME_MODE = "appearance.themeMode"
    }
}
