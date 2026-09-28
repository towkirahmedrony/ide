package com.agentx.app.integrations.android

import android.content.Context
import android.content.SharedPreferences
import com.agentx.app.integrations.connection.ConnectionType
import com.agentx.app.integrations.setup.IntegrationSetupStore
import com.agentx.app.integrations.setup.PersonalOAuthSetup

/**
 * Persists personal, non-secret OAuth setup (Client ID, optional broker URL,
 * scopes) in app-private SharedPreferences.
 *
 * Client **secrets** are never written here. Tokens live in
 * [KeystoreConnectionSecretStore].
 */
class SharedPreferencesIntegrationSetupStore(
    context: Context,
    preferencesName: String = DEFAULT_PREFERENCES_NAME,
) : IntegrationSetupStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    override suspend fun load(type: ConnectionType): PersonalOAuthSetup? = read(type)

    override suspend fun save(type: ConnectionType, setup: PersonalOAuthSetup) {
        val key = type.name
        prefs.edit()
            .putStringSet(KEY_TYPES, typeSet() + key)
            .putString(field(key, FIELD_CLIENT_ID), setup.clientId)
            .putString(field(key, FIELD_BROKER), setup.exchangeBrokerUrl.orEmpty())
            .putString(field(key, FIELD_SCOPES), setup.scopes.sorted().joinToString(" "))
            .putLong(field(key, FIELD_UPDATED), setup.updatedAtMillis)
            .apply()
    }

    override suspend fun clear(type: ConnectionType) {
        val key = type.name
        prefs.edit()
            .putStringSet(KEY_TYPES, typeSet() - key)
            .remove(field(key, FIELD_CLIENT_ID))
            .remove(field(key, FIELD_BROKER))
            .remove(field(key, FIELD_SCOPES))
            .remove(field(key, FIELD_UPDATED))
            .apply()
    }

    override suspend fun loadAll(): Map<ConnectionType, PersonalOAuthSetup> =
        typeSet().mapNotNull { name ->
            val type = ConnectionType.entries.firstOrNull { it.name == name } ?: return@mapNotNull null
            val setup = read(type) ?: return@mapNotNull null
            type to setup
        }.toMap()

    private fun read(type: ConnectionType): PersonalOAuthSetup? {
        val key = type.name
        val clientId = prefs.getString(field(key, FIELD_CLIENT_ID), null)?.trim().orEmpty()
        if (clientId.isBlank() && !prefs.contains(field(key, FIELD_CLIENT_ID))) return null
        val broker = prefs.getString(field(key, FIELD_BROKER), null)?.trim()?.takeIf { it.isNotBlank() }
        val scopes = prefs.getString(field(key, FIELD_SCOPES), null)
            ?.split(' ', ',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            .orEmpty()
        return PersonalOAuthSetup(
            clientId = clientId,
            exchangeBrokerUrl = broker,
            scopes = scopes,
            updatedAtMillis = prefs.getLong(field(key, FIELD_UPDATED), 0L),
        )
    }

    private fun typeSet(): Set<String> = prefs.getStringSet(KEY_TYPES, emptySet()).orEmpty().toSet()

    private fun field(type: String, name: String): String = "setup.$type.$name"

    companion object {
        const val DEFAULT_PREFERENCES_NAME: String = "forge.integration-setup"

        private const val KEY_TYPES = "setup.types"
        private const val FIELD_CLIENT_ID = "clientId"
        private const val FIELD_BROKER = "broker"
        private const val FIELD_SCOPES = "scopes"
        private const val FIELD_UPDATED = "updated"
    }
}
