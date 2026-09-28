package com.agentx.app.integrations.connection

/**
 * Probes whether a saved connection can actually talk to its service.
 *
 * ConnectionManager → ConnectionTester → service-specific implementation later.
 * This task only ships the contract and a default that refuses to claim
 * success for types that have no tester yet.
 */
fun interface ConnectionTester {
    suspend fun test(connection: Connection): ConnectionTestResult
}

/**
 * Default tester. Unsupported types always return
 * [ConnectionTestResult.NOT_IMPLEMENTED] and never [ConnectionStatus.CONNECTED].
 */
class UnsupportedConnectionTester(
    private val clock: () -> Long = System::currentTimeMillis,
) : ConnectionTester {

    override suspend fun test(connection: Connection): ConnectionTestResult =
        ConnectionTestResult.unsupported(clock())
}

/**
 * Dispatches to a per-type tester. Types without a registered implementation
 * fall back to [UnsupportedConnectionTester].
 */
class DispatchingConnectionTester(
    private val testers: Map<ConnectionType, ConnectionTester> = emptyMap(),
    private val fallback: ConnectionTester = UnsupportedConnectionTester(),
) : ConnectionTester {

    override suspend fun test(connection: Connection): ConnectionTestResult {
        val tester = testers[connection.type] ?: fallback
        return tester.test(connection)
    }
}
