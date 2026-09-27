package com.agentx.app.model.preset

/**
 * Storage for model credentials.
 *
 * A [ModelPreset] only ever carries a [ModelPreset.credentialRef]; the secret
 * lives here. Platform implementations use the strongest storage the device
 * offers (Android Keystore-backed encryption); when encryption is unavailable
 * the implementation must report [persistent] = false so the UI can say that the
 * credential will not be remembered.
 *
 * Secrets must never be logged, echoed, or copied into a preset or a model
 * request payload other than the authorization header the provider adds.
 */
interface ModelSecretStore {
    /** Whether secrets survive process death on this platform. */
    val persistent: Boolean

    suspend fun put(ref: String, secret: String)

    suspend fun get(ref: String): String?

    suspend fun remove(ref: String)
}

/** Session-only store: useful for tests and as a safe fallback. */
class InMemoryModelSecretStore : ModelSecretStore {

    private val secrets = LinkedHashMap<String, String>()

    override val persistent: Boolean = false

    override suspend fun put(ref: String, secret: String) {
        secrets[ref] = secret
    }

    override suspend fun get(ref: String): String? = secrets[ref]

    override suspend fun remove(ref: String) {
        secrets.remove(ref)
    }
}

/** Resolves the credential for a preset without exposing the store to callers. */
interface ModelCredentialResolver {
    /** The secret for [preset], or null when the preset needs no credential. */
    suspend fun resolve(preset: ModelPreset): String?
}

class StoreBackedModelCredentialResolver(
    private val store: ModelSecretStore,
) : ModelCredentialResolver {

    override suspend fun resolve(preset: ModelPreset): String? {
        val ref = preset.credentialRef?.takeIf { it.isNotBlank() } ?: return null
        return store.get(ref)?.takeIf { it.isNotBlank() }
    }
}
