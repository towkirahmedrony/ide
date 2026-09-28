package com.agentx.app.integrations.connection

import com.agentx.app.core.ForgeErrorCode
import com.agentx.app.core.errorOrNull
import com.agentx.app.core.valueOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectionManagerTest {

    private val store = InMemoryConnectionStore()
    private val secrets = InMemoryConnectionSecretStore()
    private var ids = 0
    private var now = 1_000L

    private val manager = DefaultConnectionManager(
        store = store,
        secrets = secrets,
        tester = UnsupportedConnectionTester { now },
        clock = { now },
        idFactory = { "conn-${++ids}" },
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun githubDraft(
        name: String = "Work GitHub",
        credential: String? = "ghp-test-token",
        enabled: Boolean = true,
        capabilities: Set<ConnectionCapability> = ConnectionCapabilities.GITHUB,
    ) = ConnectionDraft(
        displayName = name,
        type = ConnectionType.GITHUB,
        config = ConnectionConfig(authMethod = ConnectionAuthMethod.ACCESS_TOKEN),
        capabilities = capabilities,
        enabled = enabled,
        credential = credential,
    )

    private suspend fun addGithub(
        name: String = "Work GitHub",
        credential: String? = "ghp-test-token",
        enabled: Boolean = true,
        capabilities: Set<ConnectionCapability> = ConnectionCapabilities.GITHUB,
    ): Connection = assertNotNull(
        manager.addConnection(githubDraft(name, credential, enabled, capabilities)).valueOrNull(),
    )

    @Test
    fun `connections can be created updated and removed`() = runBlocking {
        val created = addGithub()
        assertEquals(listOf("Work GitHub"), manager.state.value.connections.map { it.displayName })
        assertEquals(ConnectionType.GITHUB, created.type)
        assertEquals(ConnectionStatus.NOT_CONFIGURED, created.status)

        val updated = assertNotNull(
            manager.updateConnection(
                githubDraft(name = "Personal GitHub").copy(id = created.id, credential = null),
            ).valueOrNull(),
        )
        assertEquals("Personal GitHub", manager.state.value.connections.single().displayName)
        assertEquals(created.id, updated.id)

        assertNull(manager.removeConnection(created.id).errorOrNull())
        assertTrue(manager.state.value.connections.isEmpty())
    }

    @Test
    fun `a credential is stored securely and never appears on the connection`() = runBlocking {
        val created = addGithub(credential = "ghp-secret-value")
        val ref = assertNotNull(created.credentialRef)

        assertEquals("ghp-secret-value", secrets.get(ref))
        assertFalse(created.toString().contains("ghp-secret-value"))
        assertFalse(created.authorizedHandle().toString().contains("ghp-secret-value"))
        assertNull(created.config.metadata["token"])
        assertEquals("Access token", created.config.authMethod.displayName)

        assertNotNull(
            manager.updateConnection(
                githubDraft(name = created.displayName).copy(id = created.id, credential = null, clearCredential = true),
            ).valueOrNull(),
        )
        assertNull(manager.state.value.connections.single().credentialRef)
        assertNull(secrets.get(ref))
    }

    @Test
    fun `deleting a connection also removes its credential`() = runBlocking {
        val created = addGithub(credential = "ghp-secret-value")
        val ref = assertNotNull(created.credentialRef)

        manager.removeConnection(created.id)

        assertTrue(store.load().isEmpty())
        assertNull(secrets.get(ref))
    }

    @Test
    fun `duplicate names of the same type are rejected`() = runBlocking {
        addGithub(name = "Work GitHub")
        val error = assertNotNull(manager.addConnection(githubDraft(name = "work github")).errorOrNull())
        assertEquals(ForgeErrorCode.CONNECTION_DUPLICATE, error.code)
        assertEquals(1, manager.state.value.connections.size)
    }

    @Test
    fun `the same name is allowed for a different type`() = runBlocking {
        addGithub(name = "Prod")
        val supabase = assertNotNull(
            manager.addConnection(
                ConnectionDraft(
                    displayName = "Prod",
                    type = ConnectionType.SUPABASE,
                    config = ConnectionConfig(
                        endpoint = "https://example.supabase.co",
                        authMethod = ConnectionAuthMethod.API_KEY,
                    ),
                    credential = "sb-key",
                ),
            ).valueOrNull(),
        )
        assertEquals(ConnectionType.SUPABASE, supabase.type)
        assertEquals(2, manager.state.value.connections.size)
    }

    @Test
    fun `enable and disable persist on the connection`() = runBlocking {
        val created = addGithub()
        assertTrue(created.enabled)

        val disabled = assertNotNull(manager.setEnabled(created.id, false).valueOrNull())
        assertFalse(disabled.enabled)
        assertFalse(manager.state.value.connections.single().enabled)

        val enabled = assertNotNull(manager.setEnabled(created.id, true).valueOrNull())
        assertTrue(enabled.enabled)
    }

    @Test
    fun `unsupported testers never report connected`() = runBlocking {
        val created = addGithub()
        val error = assertNotNull(manager.testConnection(created.id).errorOrNull())

        assertEquals(ForgeErrorCode.CONNECTION_TEST_UNSUPPORTED, error.code)
        assertEquals(ConnectionTestResult.NOT_IMPLEMENTED, error.message)

        val after = manager.state.value.connections.single()
        assertEquals(ConnectionStatus.DISCONNECTED, after.status)
        assertEquals(ConnectionTestResult.NOT_IMPLEMENTED, after.statusMessage)
        assertEquals(now, after.lastTestedAtMillis)
        assertTrue(manager.status(created.id) != ConnectionStatus.CONNECTED)
    }

    @Test
    fun `status moves through connecting then disconnected for unimplemented tests`() = runBlocking {
        val created = addGithub()
        assertEquals(ConnectionStatus.NOT_CONFIGURED, created.status)
        manager.testConnection(created.id)
        assertEquals(ConnectionStatus.DISCONNECTED, manager.status(created.id))
    }

    @Test
    fun `disabled connections cannot be tested`() = runBlocking {
        val created = addGithub()
        manager.setEnabled(created.id, false)
        val error = assertNotNull(manager.testConnection(created.id).errorOrNull())
        assertEquals(ForgeErrorCode.CONNECTION_OPERATION_FAILED, error.code)
    }

    @Test
    fun `authorize grants a handle without the secret`() = runBlocking {
        val created = addGithub(credential = "ghp-secret-value")
        val granted = assertNotNull(
            manager.authorize(ConnectionType.GITHUB, ConnectionCapabilities.REPOSITORY_READ).valueOrNull(),
        )

        assertEquals(created.id, granted.id)
        assertEquals(ConnectionType.GITHUB, granted.type)
        assertTrue(ConnectionCapabilities.REPOSITORY_READ in granted.capabilities)
        assertFalse(granted.toString().contains("ghp-secret-value"))
        assertEquals(created.credentialRef, granted.credentialRef)
        val secret = secrets.get(granted.credentialRef!!)
        assertNotNull(secret)
        assertFalse(secret in granted.toString())
    }

    @Test
    fun `authorize fails when no connection of that type exists`() = runBlocking {
        val error = assertNotNull(
            manager.authorize(ConnectionType.GITHUB, ConnectionCapabilities.REPOSITORY_READ).errorOrNull(),
        )
        assertEquals(ConnectionAuthorizationFailure.NOT_FOUND, error.failure)
    }

    @Test
    fun `authorize fails when the connection is disabled`() = runBlocking {
        val created = addGithub(enabled = false)
        val error = assertNotNull(
            manager.authorize(ConnectionType.GITHUB, ConnectionCapabilities.REPOSITORY_READ).errorOrNull(),
        )
        assertEquals(ConnectionAuthorizationFailure.DISABLED, error.failure)
        assertEquals(created.id, error.connectionId)
    }

    @Test
    fun `authorize fails when the capability is missing`() = runBlocking {
        addGithub(capabilities = setOf(ConnectionCapabilities.ISSUES))
        val error = assertNotNull(
            manager.authorize(ConnectionType.GITHUB, ConnectionCapabilities.REPOSITORY_WRITE).errorOrNull(),
        )
        assertEquals(ConnectionAuthorizationFailure.MISSING_CAPABILITY, error.failure)
        assertEquals(ConnectionCapabilities.REPOSITORY_WRITE, error.capability)
    }

    @Test
    fun `authorize fails when a required credential is missing`() = runBlocking {
        addGithub(credential = null)
        val error = assertNotNull(
            manager.authorize(ConnectionType.GITHUB, ConnectionCapabilities.REPOSITORY_READ).errorOrNull(),
        )
        assertEquals(ConnectionAuthorizationFailure.MISSING_CREDENTIAL, error.failure)
    }

    @Test
    fun `supabase requires an endpoint`() = runBlocking {
        val error = assertNotNull(
            manager.addConnection(
                ConnectionDraft(
                    displayName = "Cloud",
                    type = ConnectionType.SUPABASE,
                    config = ConnectionConfig(authMethod = ConnectionAuthMethod.API_KEY),
                    credential = "sb-key",
                ),
            ).errorOrNull(),
        )
        assertEquals(ForgeErrorCode.CONNECTION_INVALID, error.code)
    }

    @Test
    fun `mcp connections persist transport metadata without executing anything`() = runBlocking {
        val created = assertNotNull(
            manager.addConnection(
                ConnectionDraft(
                    displayName = "Local MCP",
                    type = ConnectionType.MCP_SERVER,
                    config = com.agentx.app.integrations.mcp.mcpConnectionConfig(
                        transport = com.agentx.app.integrations.mcp.McpTransportKind.STDIO,
                        command = "npx fake-mcp",
                    ),
                ),
            ).valueOrNull(),
        )
        val mcp = assertNotNull(created.asMcpConnection())
        assertEquals("stdio", mcp.config.transport)
        assertEquals("npx fake-mcp", mcp.config.command)
        assertEquals(ConnectionCapabilities.MCP, created.capabilities)
        assertEquals(ConnectionStatus.NOT_CONFIGURED, created.status)
    }
}

private fun Connection.asMcpConnection() = com.agentx.app.integrations.mcp.McpConnection.from(this)
