package com.agentx.app.integrations.connection

/**
 * Storage for connection credentials.
 *
 * A [Connection] only ever carries a [Connection.credentialRef]; the secret
 * lives here. Platform implementations use the strongest storage the device
 * offers (Android Keystore-backed encryption); when encryption is unavailable
 * the implementation must report [persistent] = false so the UI can say that
 * the credential will not be remembered.
 *
 * Secrets must never be logged, echoed, copied into a connection, an agent
 * context, a tool result, or a model request.
 */
interface ConnectionSecretStore {
    /** Whether secrets survive process death on this platform. */
    val persistent: Boolean

    suspend fun put(ref: String, secret: String)

    suspend fun get(ref: String): String?

    suspend fun remove(ref: String)

    suspend fun contains(ref: String): Boolean = get(ref) != null
}

/** Session-only store: useful for tests and as a safe fallback. */
class InMemoryConnectionSecretStore : ConnectionSecretStore {

    private val secrets = LinkedHashMap<String, String>()

    override val persistent: Boolean = false

    override suspend fun put(ref: String, secret: String) {
        secrets[ref] = secret
    }

    override suspend fun get(ref: String): String? = secrets[ref]

    override suspend fun remove(ref: String) {
        secrets.remove(ref)
    }

    override suspend fun contains(ref: String): Boolean = secrets.containsKey(ref)
}
