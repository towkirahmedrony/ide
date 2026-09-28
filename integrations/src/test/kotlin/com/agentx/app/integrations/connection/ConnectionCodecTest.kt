package com.agentx.app.integrations.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectionCodecTest {

    @Test
    fun `round trip preserves non-secret fields and never encodes a secret`() {
        val original = Connection(
            id = ConnectionId("abc"),
            displayName = "Work GitHub",
            type = ConnectionType.GITHUB,
            config = ConnectionConfig(
                endpoint = "https://api.github.com",
                authMethod = ConnectionAuthMethod.ACCESS_TOKEN,
                username = "octocat",
                metadata = mapOf("scope" to "repo"),
            ),
            capabilities = ConnectionCapabilities.GITHUB,
            enabled = true,
            status = ConnectionStatus.DISCONNECTED,
            statusMessage = ConnectionTestResult.NOT_IMPLEMENTED,
            lastTestedAtMillis = 42L,
            credentialRef = "connections.secret.abc",
            createdAtMillis = 1L,
            updatedAtMillis = 2L,
        )

        val encoded = ConnectionCodec.encode(original)
        assertTrue(encoded.values.none { it.contains("ghp-") })
        assertNull(encoded["credential"])
        assertNull(encoded["token"])
        assertNull(encoded["secret"])

        val decoded = ConnectionCodec.decode(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `unknown records are dropped rather than guessed`() {
        assertNull(ConnectionCodec.decode(emptyMap()))
        assertNull(ConnectionCodec.decode(mapOf("id" to "x")))
    }
}
