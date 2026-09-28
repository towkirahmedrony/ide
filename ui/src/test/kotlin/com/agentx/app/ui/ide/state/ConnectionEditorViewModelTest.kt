package com.agentx.app.ui.ide.state

import com.agentx.app.integrations.connection.Connection
import com.agentx.app.integrations.connection.ConnectionAuthMethod
import com.agentx.app.integrations.connection.ConnectionConfig
import com.agentx.app.integrations.connection.ConnectionId
import com.agentx.app.integrations.connection.ConnectionType
import com.agentx.app.integrations.mcp.META_COMMAND
import com.agentx.app.integrations.mcp.META_TRANSPORT
import com.agentx.app.integrations.mcp.McpTransportKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectionEditorViewModelTest {

    @Test
    fun `form draft never includes the typed credential as a field on the connection`() {
        val state = ConnectionEditorState(
            loading = false,
            displayName = "Work GitHub",
            type = ConnectionType.GITHUB,
            authMethod = ConnectionAuthMethod.ACCESS_TOKEN,
            credential = "ghp-secret-value",
            enabled = true,
        )
        val draft = state.toDraft()
        assertEquals("ghp-secret-value", draft.credential)
        val connection = draft.toConnection(
            id = ConnectionId("c1"),
            existing = null,
            credentialRef = "connections.secret.c1",
            now = 1L,
        )
        assertFalse(connection.toString().contains("ghp-secret-value"))
        assertEquals("connections.secret.c1", connection.credentialRef)
        assertNull(connection.config.metadata["credential"])
    }

    @Test
    fun `editing a saved connection preserves identity and does not echo the secret`() {
        val saved = Connection(
            id = ConnectionId("c1"),
            displayName = "Work GitHub",
            type = ConnectionType.GITHUB,
            config = ConnectionConfig(authMethod = ConnectionAuthMethod.ACCESS_TOKEN),
            credentialRef = "connections.secret.c1",
        )
        val state = ConnectionEditorState.from(saved)
        assertTrue(state.hasStoredCredential)
        assertEquals("", state.credential)
        assertEquals("c1", state.connectionId)
    }

    @Test
    fun `mcp fields round-trip through the form`() {
        val saved = Connection(
            id = ConnectionId("mcp-1"),
            displayName = "Local MCP",
            type = ConnectionType.MCP_SERVER,
            config = ConnectionConfig(
                authMethod = ConnectionAuthMethod.NONE,
                metadata = mapOf(
                    META_TRANSPORT to McpTransportKind.STDIO.wireName,
                    META_COMMAND to "npx fake-mcp",
                ),
            ),
        )
        val state = ConnectionEditorState.from(saved)
        assertEquals(McpTransportKind.STDIO, state.mcpTransport)
        assertEquals("npx fake-mcp", state.mcpCommand)
        val draft = state.toDraft()
        assertEquals("stdio", draft.config.metadata[META_TRANSPORT])
        assertEquals("npx fake-mcp", draft.config.metadata[META_COMMAND])
    }
}
