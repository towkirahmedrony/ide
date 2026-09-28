package com.agentx.app.integrations.connection

/**
 * Persistence port for non-secret connection records.
 *
 * Implementations must never store credentials — only the connection, its
 * configuration metadata, enabled state, capabilities and last known status.
 * The platform already persists workspace and model metadata through
 * SharedPreferences; connections reuse that approach.
 */
interface ConnectionStore {
    suspend fun load(): List<Connection>

    suspend fun save(connection: Connection)

    suspend fun delete(id: ConnectionId)
}

/** Store used by tests, previews, and as the platform default. */
class InMemoryConnectionStore(initial: List<Connection> = emptyList()) : ConnectionStore {

    private val connections = LinkedHashMap<String, Connection>()

    init {
        initial.forEach { connections[it.id.value] = it }
    }

    override suspend fun load(): List<Connection> = connections.values.toList()

    override suspend fun save(connection: Connection) {
        connections[connection.id.value] = connection
    }

    override suspend fun delete(id: ConnectionId) {
        connections.remove(id.value)
    }
}
