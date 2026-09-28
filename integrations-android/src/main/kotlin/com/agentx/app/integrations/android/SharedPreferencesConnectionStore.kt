package com.agentx.app.integrations.android

import android.content.Context
import android.content.SharedPreferences
import com.agentx.app.integrations.connection.Connection
import com.agentx.app.integrations.connection.ConnectionCodec
import com.agentx.app.integrations.connection.ConnectionId
import com.agentx.app.integrations.connection.ConnectionStore

/**
 * Persists non-secret connection records in app-private [SharedPreferences].
 *
 * Only identity, configuration metadata, capabilities, enabled state and last
 * known status are stored. Credentials live in [KeystoreConnectionSecretStore].
 */
class SharedPreferencesConnectionStore(
    context: Context,
    preferencesName: String = DEFAULT_PREFERENCES_NAME,
) : ConnectionStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    override suspend fun load(): List<Connection> = idSet().mapNotNull { read(it) }

    override suspend fun save(connection: Connection) {
        val id = connection.id.value
        val encoded = ConnectionCodec.encode(connection)
        val editor = prefs.edit().putStringSet(KEY_IDS, idSet() + id)
        ALL_FIELDS.forEach { field ->
            val value = encoded[field]
            if (value == null) editor.remove(fieldKey(id, field)) else editor.putString(fieldKey(id, field), value)
        }
        editor.apply()
    }

    override suspend fun delete(id: ConnectionId) {
        val key = id.value
        val editor = prefs.edit().putStringSet(KEY_IDS, idSet() - key)
        ALL_FIELDS.forEach { field -> editor.remove(fieldKey(key, field)) }
        editor.apply()
    }

    private fun read(id: String): Connection? {
        val encoded = ALL_FIELDS.mapNotNull { field ->
            val value = prefs.getString(fieldKey(id, field), null) ?: return@mapNotNull null
            field to value
        }.toMap()
        if (encoded.isEmpty()) return null
        val withId = if (encoded.containsKey(ConnectionCodec.FIELD_ID)) encoded else encoded + (ConnectionCodec.FIELD_ID to id)
        return ConnectionCodec.decode(withId)
    }

    private fun idSet(): Set<String> = prefs.getStringSet(KEY_IDS, emptySet()).orEmpty().toSet()

    private fun fieldKey(id: String, field: String): String = "connection.$id.$field"

    companion object {
        const val DEFAULT_PREFERENCES_NAME: String = "forge.connections"

        private const val KEY_IDS = "connections.ids"

        private val ALL_FIELDS = listOf(
            ConnectionCodec.FIELD_ID,
            ConnectionCodec.FIELD_DISPLAY_NAME,
            ConnectionCodec.FIELD_TYPE,
            ConnectionCodec.FIELD_ENDPOINT,
            ConnectionCodec.FIELD_AUTH_METHOD,
            ConnectionCodec.FIELD_USERNAME,
            ConnectionCodec.FIELD_METADATA,
            ConnectionCodec.FIELD_CAPABILITIES,
            ConnectionCodec.FIELD_ENABLED,
            ConnectionCodec.FIELD_STATUS,
            ConnectionCodec.FIELD_STATUS_MESSAGE,
            ConnectionCodec.FIELD_LAST_TESTED,
            ConnectionCodec.FIELD_CREDENTIAL_REF,
            ConnectionCodec.FIELD_GRANTED_SCOPES,
            ConnectionCodec.FIELD_ACCOUNT_LABEL,
            ConnectionCodec.FIELD_CREDENTIALS_EXPIRE_AT,
            ConnectionCodec.FIELD_REFRESHABLE,
            ConnectionCodec.FIELD_CREATED,
            ConnectionCodec.FIELD_UPDATED,
        )
    }
}
