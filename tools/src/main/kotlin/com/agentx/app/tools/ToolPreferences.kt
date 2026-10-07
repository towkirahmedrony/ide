package com.agentx.app.tools

/**
 * The user-owned enablement of the tool system.
 *
 * This is the single source of truth for "may this tool be offered to a model and
 * run at all". It is deliberately separate from [ToolAvailability]: availability
 * answers "does this build implement the tool?", enablement answers "has the user
 * kept it turned on?".
 *
 * It is read live by the execution path — [DefaultToolRouter] refuses a disabled
 * tool and the agent tool bridge keeps it out of what the model is told exists —
 * so a change made in Settings takes effect on the next call without rebuilding
 * the agent core. It never widens anything: it can only take a tool away.
 */
interface ToolPreferences {
    /** False when the user turned [toolId] off in Settings. */
    fun isEnabled(toolId: String): Boolean

    /** Every tool id the user has turned off; empty means "all enabled". */
    fun disabledToolIds(): Set<String>
}

/** Prefers every tool. The default, so a caller that passes nothing is unchanged. */
object AllowAllToolPreferences : ToolPreferences {
    override fun isEnabled(toolId: String): Boolean = true

    override fun disabledToolIds(): Set<String> = emptySet()
}

/**
 * Where the disabled tool ids are persisted between runs. Mirrors the project's
 * other settings stores; the app backs it with `SharedPreferences` and tests use
 * the in-memory one.
 */
interface ToolPreferenceStore {
    suspend fun load(): Set<String>

    suspend fun save(disabled: Set<String>)
}

/** Non-persistent store used by tests and headless callers. */
class InMemoryToolPreferenceStore(initial: Set<String> = emptySet()) : ToolPreferenceStore {

    private var disabled: Set<String> = initial.toSet()

    override suspend fun load(): Set<String> = disabled

    override suspend fun save(disabled: Set<String>) {
        this.disabled = disabled.toSet()
    }
}

/**
 * Mutable, persisted preference set. The Tool System and the runtime read it
 * through the [ToolPreferences] interface; Settings writes it through
 * [setEnabled] and [reset].
 *
 * The disabled set is small (one id per tool a user turned off), so it is kept in
 * memory and re-read on every check instead of being cached per tool. [load] is
 * called once at boot, off the main thread.
 */
class DefaultToolPreferences(
    private val store: ToolPreferenceStore = InMemoryToolPreferenceStore(),
) : ToolPreferences {

    private val lock = Any()

    @Volatile
    private var disabled: Set<String> = emptySet()

    override fun isEnabled(toolId: String): Boolean = toolId !in disabled

    override fun disabledToolIds(): Set<String> = disabled

    /**
     * Restores the persisted set; a failed read leaves the previous set intact.
     * `synchronized` is inline, so the suspend [store] read is still allowed here.
     */
    suspend fun load() {
        synchronized(lock) {
            disabled = store.load()
        }
    }

    /** Enables or disables [toolId] and persists the whole set. */
    suspend fun setEnabled(toolId: String, enabled: Boolean) {
        synchronized(lock) {
            val updated = if (enabled) disabled - toolId else disabled + toolId
            disabled = updated
            store.save(updated)
        }
    }

    /** Turns every tool back on. */
    suspend fun reset() {
        synchronized(lock) {
            disabled = emptySet()
            store.save(disabled)
        }
    }
}
