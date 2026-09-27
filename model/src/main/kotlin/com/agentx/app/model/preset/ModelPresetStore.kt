package com.agentx.app.model.preset

/**
 * Persistence port for model presets.
 *
 * The platform already persists workspace metadata through `SharedPreferences`;
 * model presets reuse that approach rather than introducing a second database.
 * Implementations must never store credentials — only the preset, the selected
 * model id, and the last known lifecycle state.
 */
interface ModelPresetStore {
    /** All saved presets. Order is not significant; the repository sorts. */
    suspend fun load(): List<ModelPreset>

    suspend fun save(preset: ModelPreset)

    suspend fun delete(id: String)

    /** Id of the model the user last selected, when it still exists. */
    suspend fun activeId(): String?

    suspend fun setActiveId(id: String?)

    /**
     * Last known lifecycle state name for [presetId], used to show something
     * meaningful before the app has re-checked the endpoint. Never an endpoint.
     */
    suspend fun lastStatus(presetId: String): String?

    suspend fun setLastStatus(presetId: String, state: String)
}

/** Store used by previews, tests, and as the platform default. */
class InMemoryModelPresetStore(initial: List<ModelPreset> = emptyList()) : ModelPresetStore {

    private val presets = LinkedHashMap<String, ModelPreset>()
    private val statuses = LinkedHashMap<String, String>()
    private var active: String? = null

    init {
        initial.forEach { presets[it.id] = it }
    }

    override suspend fun load(): List<ModelPreset> = presets.values.toList()

    override suspend fun save(preset: ModelPreset) {
        presets[preset.id] = preset
    }

    override suspend fun delete(id: String) {
        presets.remove(id)
        statuses.remove(id)
        if (active == id) active = null
    }

    override suspend fun activeId(): String? = active?.takeIf { presets.containsKey(it) }

    override suspend fun setActiveId(id: String?) {
        active = id
    }

    override suspend fun lastStatus(presetId: String): String? = statuses[presetId]

    override suspend fun setLastStatus(presetId: String, state: String) {
        statuses[presetId] = state
    }
}
