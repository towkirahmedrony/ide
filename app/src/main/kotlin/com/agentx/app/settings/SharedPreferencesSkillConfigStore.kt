package com.agentx.app.settings

import android.content.Context
import android.content.SharedPreferences
import com.agentx.app.skills.SkillDefinition
import com.agentx.app.skills.SkillStore

/**
 * Persists skill configuration — explicit enabled/disabled choices and
 * per-agent role assignments — in app-private [SharedPreferences].
 *
 * Imported skill bodies are stored on the filesystem by
 * [FilesystemSkillFileStore]; this store deliberately does not keep a second
 * copy of them.
 */
class SharedPreferencesSkillConfigStore(
    context: Context,
    preferencesName: String = DEFAULT_PREFERENCES_NAME,
) : SkillStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    override suspend fun enabledOverrides(): Map<String, Boolean> =
        knownIds().mapNotNull { id ->
            val key = enabledKey(id)
            if (prefs.contains(key)) id to prefs.getBoolean(key, false) else null
        }.toMap()

    override suspend fun setEnabled(id: String, enabled: Boolean) {
        prefs.edit()
            .putStringSet(KEY_IDS, knownIds() + id)
            .putBoolean(enabledKey(id), enabled)
            .apply()
    }

    override suspend fun assignments(): Map<String, Set<String>> =
        knownIds().mapNotNull { id ->
            prefs.getString(assignKey(id), null)?.let { id to parseRoles(it) }
        }.toMap()

    override suspend fun setAssignment(id: String, roles: Set<String>) {
        prefs.edit()
            .putStringSet(KEY_IDS, knownIds() + id)
            .putString(assignKey(id), roles.map { it.uppercase() }.sorted().joinToString(","))
            .apply()
    }

    /** Imported skill bodies are filesystem-backed, not stored here. */
    override suspend fun imported(): List<SkillDefinition> = emptyList()

    override suspend fun saveImported(skill: SkillDefinition) = Unit

    override suspend fun removeImported(id: String): Boolean = false

    override suspend fun reset() {
        val editor = prefs.edit()
        knownIds().forEach { id ->
            editor.remove(enabledKey(id))
            editor.remove(assignKey(id))
        }
        editor.remove(KEY_IDS)
        editor.apply()
    }

    private fun knownIds(): Set<String> = prefs.getStringSet(KEY_IDS, emptySet()).orEmpty().toSet()

    private fun parseRoles(raw: String): Set<String> =
        raw.split(',', ' ')
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }
            .toSet()

    private fun enabledKey(id: String): String = "skills.enabled.$id"

    private fun assignKey(id: String): String = "skills.roles.$id"

    companion object {
        const val DEFAULT_PREFERENCES_NAME: String = "forge.skills"

        private const val KEY_IDS = "skills.ids"
    }
}
