package com.agentx.app.workspace.android

import android.content.Context
import android.content.SharedPreferences
import com.agentx.app.workspace.WorkspaceId
import com.agentx.app.workspace.WorkspaceMetadata
import com.agentx.app.workspace.WorkspaceMetadataStore
import com.agentx.app.workspace.WorkspaceRecord

/**
 * Persists workspace metadata in app-private [SharedPreferences].
 *
 * Only the opaque handle needed to reopen the workspace, its display name, and
 * the last-opened time are stored. No file contents or credentials ever reach
 * this store.
 */
class SharedPreferencesWorkspaceMetadataStore(
    context: Context,
    preferencesName: String = DEFAULT_PREFERENCES_NAME,
) : WorkspaceMetadataStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    override suspend fun records(): List<WorkspaceRecord> =
        idSet().mapNotNull { read(it) }

    override suspend fun find(id: WorkspaceId): WorkspaceRecord? = read(id.value)

    override suspend fun save(record: WorkspaceRecord) {
        val id = record.metadata.id.value
        prefs.edit()
            .putStringSet(KEY_IDS, idSet() + id)
            .putString(fieldKey(id, FIELD_HANDLE), record.handle)
            .putString(fieldKey(id, FIELD_NAME), record.metadata.name)
            .putString(fieldKey(id, FIELD_DISPLAY), record.metadata.displayLocation)
            .putLong(fieldKey(id, FIELD_LAST_OPENED), record.metadata.lastOpenedAtEpochMillis ?: 0L)
            .apply()
    }

    override suspend fun delete(id: WorkspaceId) {
        val key = id.value
        val editor = prefs.edit()
            .putStringSet(KEY_IDS, idSet() - key)
            .remove(fieldKey(key, FIELD_HANDLE))
            .remove(fieldKey(key, FIELD_NAME))
            .remove(fieldKey(key, FIELD_DISPLAY))
            .remove(fieldKey(key, FIELD_LAST_OPENED))
        if (prefs.getString(KEY_LAST_OPENED_ID, null) == key) {
            editor.remove(KEY_LAST_OPENED_ID)
        }
        editor.apply()
    }

    override suspend fun lastOpenedId(): WorkspaceId? =
        prefs.getString(KEY_LAST_OPENED_ID, null)?.takeIf { it.isNotBlank() }?.let(::WorkspaceId)

    override suspend fun setLastOpened(id: WorkspaceId?) {
        prefs.edit().apply {
            if (id == null) remove(KEY_LAST_OPENED_ID) else putString(KEY_LAST_OPENED_ID, id.value)
        }.apply()
    }

    private fun read(id: String): WorkspaceRecord? {
        val handle = prefs.getString(fieldKey(id, FIELD_HANDLE), null)?.takeIf { it.isNotBlank() } ?: return null
        val name = prefs.getString(fieldKey(id, FIELD_NAME), null)?.takeIf { it.isNotBlank() } ?: id
        val display = prefs.getString(fieldKey(id, FIELD_DISPLAY), null)?.takeIf { it.isNotBlank() } ?: name
        val lastOpened = prefs.getLong(fieldKey(id, FIELD_LAST_OPENED), 0L).takeIf { it > 0L }
        return WorkspaceRecord(
            metadata = WorkspaceMetadata(
                id = WorkspaceId(id),
                name = name,
                displayLocation = display,
                lastOpenedAtEpochMillis = lastOpened,
                persisted = true,
            ),
            handle = handle,
        )
    }

    private fun idSet(): Set<String> = prefs.getStringSet(KEY_IDS, emptySet()).orEmpty().toSet()

    private fun fieldKey(id: String, field: String): String = "workspace.$id.$field"

    companion object {
        const val DEFAULT_PREFERENCES_NAME: String = "forge.workspaces"

        private const val KEY_IDS = "workspaces.ids"
        private const val KEY_LAST_OPENED_ID = "workspaces.lastOpened"
        private const val FIELD_HANDLE = "handle"
        private const val FIELD_NAME = "name"
        private const val FIELD_DISPLAY = "display"
        private const val FIELD_LAST_OPENED = "lastOpened"
    }
}
