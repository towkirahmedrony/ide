package com.agentx.app.ui.theme

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The theme modes AgentX supports. The architecture has one palette per
 * brightness plus the device setting, so these three are exactly the modes the
 * app can genuinely render.
 */
enum class ThemeMode(val stored: String, val title: String, val description: String) {
    /** Follow the device's dark-mode setting. */
    SYSTEM("system", "System default", "Follows your device's dark mode setting"),
    /** Always light, regardless of the device. */
    LIGHT("light", "Light", "Bright surfaces with dark text"),
    /** Always dark, regardless of the device. The AgentX default. */
    DARK("dark", "Dark", "Dark surfaces with light text");

    companion object {
        /**
         * Reads a persisted value. An unknown or missing entry falls back to
         * [DARK], so a damaged preference degrades to the appearance AgentX has
         * always shipped with instead of an unreadable mixture.
         */
        fun fromStored(stored: String?): ThemeMode =
            entries.firstOrNull { it.stored == stored } ?: DARK
    }
}

/**
 * Where the selected [ThemeMode] is persisted between runs. The app backs this
 * with `SharedPreferences` — the same mechanism the rest of the IDE's settings
 * use — and tests use the in-memory implementation. It holds no secret.
 */
interface ThemeModeStore {
    suspend fun load(): ThemeMode

    suspend fun save(mode: ThemeMode)
}

/** Non-persistent store used by previews and tests; [saved] mimics disk state. */
class InMemoryThemeModeStore(initial: ThemeMode = ThemeMode.DARK) : ThemeModeStore {

    var saved: ThemeMode = initial
        private set

    override suspend fun load(): ThemeMode = saved

    override suspend fun save(mode: ThemeMode) {
        saved = mode
    }
}

/**
 * The single source of truth for the app's theme mode.
 *
 * One instance exists per process, created by the composition root:
 *
 * - [ForgeTheme] collects [mode] at the very top of the tree, so [select]
 *   applies immediately to every screen — no screen holds its own copy.
 * - Settings → Appearance writes through [select], which updates the flow
 *   first and then persists, so the UI reacts without waiting on disk.
 * - [restore] runs once at boot, off the main thread; until it completes the
 *   app shows the default dark appearance, so a slow or failed read never
 *   delays the first frame.
 *
 * This is deliberately not a second settings system: it owns exactly one value
 * and delegates persistence to [ThemeModeStore].
 */
class AppearanceController(
    private val store: ThemeModeStore = InMemoryThemeModeStore(),
) {

    private val _mode = MutableStateFlow(ThemeMode.DARK)

    /** The selected theme mode. Collected by the theme and shown by Settings. */
    val mode: StateFlow<ThemeMode> = _mode.asStateFlow()

    /** Applies [mode] immediately and persists it; a failed write keeps the selection in memory. */
    suspend fun select(mode: ThemeMode) {
        _mode.value = mode
        runCatching { store.save(mode) }
    }

    /** Restores the persisted mode; a failed read keeps the current mode. */
    suspend fun restore() {
        runCatching { store.load() }.onSuccess { stored -> _mode.value = stored }
    }
}
