package com.agentx.app.model.android

import android.content.Context
import android.content.SharedPreferences
import com.agentx.app.model.catalog.ModelCatalogCodec
import com.agentx.app.model.catalog.ModelCatalogSnapshot
import com.agentx.app.model.catalog.ModelCatalogStore

/**
 * Persists provider model catalogs in app-private [SharedPreferences] — the same
 * mechanism the model presets and the workspace metadata already use, so no second
 * database is introduced.
 *
 * What is stored is exactly what a provider reported about its models: identities,
 * display names, token limits, availability and deprecation. No credential is ever
 * written here — a snapshot holds none, and an API key stays in
 * [KeystoreModelSecretStore] under the preset's credential reference. That is what
 * makes an ordinary preference file an acceptable home for a catalog.
 *
 * Restoring this on startup is what keeps the models a previous run discovered: a
 * persisted catalog is a cache of the provider's own answer, so it can be shown
 * before the provider is asked again, and a restart no longer falls back to a
 * built-in list.
 */
class SharedPreferencesModelCatalogStore(
    context: Context,
    preferencesName: String = DEFAULT_PREFERENCES_NAME,
) : ModelCatalogStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    override suspend fun load(providerId: String): ModelCatalogSnapshot? =
        prefs.getString(key(providerId), null)
            ?.let { stored -> ModelCatalogCodec.decode(stored) }
            // A snapshot that cannot be read is treated as absent rather than
            // crashing: the next refresh simply writes a good one.
            ?.takeIf { it.providerId == providerId }

    override suspend fun save(snapshot: ModelCatalogSnapshot) {
        prefs.edit().putString(key(snapshot.providerId), ModelCatalogCodec.encode(snapshot)).apply()
    }

    /** Provider identities a snapshot was persisted for, so startup can restore them. */
    override suspend fun providers(): List<String> =
        prefs.all.keys
            .filter { it.startsWith(FIELD_PREFIX) }
            .map { it.removePrefix(FIELD_PREFIX) }
            .filter { it.isNotBlank() }

    private fun key(providerId: String): String = "$FIELD_PREFIX$providerId"

    companion object {
        /** The same preferences file the model presets use; the keys do not collide. */
        const val DEFAULT_PREFERENCES_NAME: String = "forge.models"

        private const val FIELD_PREFIX = "models.catalog."
    }
}
