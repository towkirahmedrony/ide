package dev.forge.ide.model.android

import android.content.Context
import android.content.SharedPreferences
import dev.forge.ide.model.preset.ModelPreset
import dev.forge.ide.model.preset.ModelPresetCodec
import dev.forge.ide.model.preset.ModelPresetStore

/**
 * Persists model presets in app-private [SharedPreferences], the same mechanism
 * the workspace runtime already uses — no second database is introduced.
 *
 * Only presets, the selected model id and the last known lifecycle state name are
 * stored. Presets contain no credentials (only a reference into
 * [KeystoreModelSecretStore]) and no endpoint URL, so nothing here is secret.
 */
class SharedPreferencesModelPresetStore(
    context: Context,
    preferencesName: String = DEFAULT_PREFERENCES_NAME,
) : ModelPresetStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    override suspend fun load(): List<ModelPreset> =
        ModelPresetCodec.decodeAll(prefs.getString(KEY_PRESETS, null).orEmpty())

    override suspend fun save(preset: ModelPreset) {
        val updated = load().filterNot { it.id == preset.id } + preset
        prefs.edit().putString(KEY_PRESETS, ModelPresetCodec.encodeAll(updated)).apply()
    }

    override suspend fun delete(id: String) {
        val updated = load().filterNot { it.id == id }
        val editor = prefs.edit().putString(KEY_PRESETS, ModelPresetCodec.encodeAll(updated))
        editor.remove(statusKey(id))
        if (prefs.getString(KEY_ACTIVE, null) == id) editor.remove(KEY_ACTIVE)
        editor.apply()
    }

    override suspend fun activeId(): String? =
        prefs.getString(KEY_ACTIVE, null)?.takeIf { it.isNotBlank() }

    override suspend fun setActiveId(id: String?) {
        if (id == null) {
            prefs.edit().remove(KEY_ACTIVE).apply()
        } else {
            prefs.edit().putString(KEY_ACTIVE, id).apply()
        }
    }

    override suspend fun lastStatus(presetId: String): String? =
        prefs.getString(statusKey(presetId), null)?.takeIf { it.isNotBlank() }

    override suspend fun setLastStatus(presetId: String, state: String) {
        prefs.edit().putString(statusKey(presetId), state).apply()
    }

    private fun statusKey(presetId: String): String = "$FIELD_STATUS_PREFIX$presetId"

    companion object {
        const val DEFAULT_PREFERENCES_NAME: String = "forge.models"

        private const val KEY_PRESETS = "models.presets"
        private const val KEY_ACTIVE = "models.active"
        private const val FIELD_STATUS_PREFIX = "models.status."
    }
}
