package com.agentx.app.integrations.setup

import com.agentx.app.core.ForgeErrorCode
import com.agentx.app.core.config.OAuthConfig
import com.agentx.app.core.config.OAuthProviderConfig
import com.agentx.app.core.errorOrNull
import com.agentx.app.core.valueOrNull
import com.agentx.app.integrations.connection.Connection
import com.agentx.app.integrations.connection.ConnectionAuthMethod
import com.agentx.app.integrations.connection.ConnectionCapabilities
import com.agentx.app.integrations.connection.ConnectionConfig
import com.agentx.app.integrations.connection.ConnectionId
import com.agentx.app.integrations.connection.ConnectionStatus
import com.agentx.app.integrations.connection.ConnectionType
import com.agentx.app.integrations.oauth.OAuthCallbackAuthority
import com.agentx.app.integrations.oauth.OAuthExchangeStrategy
import com.agentx.app.integrations.oauth.OAuthHttpClient
import com.agentx.app.integrations.oauth.OAuthRedirectUris
import com.agentx.app.integrations.providers.ConnectionProviders
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the personal integration setup: the callback URLs this app registers
 * with a provider, the Client ID the owner saves, the validation that gates
 * [Connect] and the lifecycle the Connections page shows.
 *
 * The provider port is a fake, so no test needs a real GitHub or Supabase account
 * and no test performs a network call.
 */
class PersonalIntegrationSetupTest {

    private var now = 1_700_000_000_000L
    private val store = InMemoryIntegrationSetupStore()

    /** The setup layer must never call a provider: any request fails the test. */
    private val http = OAuthHttpClient { error("the personal setup layer must not call a provider") }

    private val built = ConnectionProviders.fromConfig(
        config = OAuthConfig(),
        http = http,
        callbacks = OAuthCallbackAuthority.DEFAULT,
        setupStore = store,
        clock = { now },
    )

    private val setup: IntegrationSetupManager get() = built.setup

    // --- Callback URLs ------------------------------------------------------

    @Test
    fun `callback urls are generated from the application identity`() {
        val authority = OAuthCallbackAuthority.DEFAULT

        assertEquals("agentx://oauth/github", authority.uriFor(ConnectionType.GITHUB))
        assertEquals("agentx://oauth/supabase", authority.uriFor(ConnectionType.SUPABASE))
        assertEquals("agentx://oauth/mcp", authority.uriFor(ConnectionType.MCP_SERVER))

        // The same authority resolves a redirect back to the service that owns it,
        // ignoring the query the provider appends.
        assertEquals(ConnectionType.GITHUB, authority.typeForUri("agentx://oauth/github"))
        assertEquals(
            ConnectionType.SUPABASE,
            authority.typeForUri("agentx://oauth/supabase?code=abc&state=xyz"),
        )
        assertEquals(ConnectionType.MCP_SERVER, authority.typeForUri("agentx://oauth/mcp#code=abc"))

        // A stranger's URI is not treated as this app's callback.
        assertNull(authority.typeForUri("agentx://oauth/unknown"))
        assertNull(authority.typeForUri("https://github.com/settings/apps"))
        assertFalse(authority.isValidUri("agentx://other/github"))

        // The authority is derived from configuration, not from a screen.
        assertEquals("agentx://oauth/github", OAuthCallbackAuthority.from("AGENTX", "OAuth").uriFor(ConnectionType.GITHUB))
        assertEquals(authority.uriFor(ConnectionType.GITHUB), setup.callbackUri(ConnectionType.GITHUB))
    }

    @Test
    fun `redirect uri validation explains what is wrong`() {
        assertTrue(OAuthRedirectUris.problems("agentx://oauth/github").isEmpty())
        assertTrue(OAuthRedirectUris.isValid("https://oauth.example.com/callback"))

        assertTrue(OAuthRedirectUris.problems("").isNotEmpty())
        assertTrue(OAuthRedirectUris.problems("agentx://oauth/a b").isNotEmpty())
        assertTrue(OAuthRedirectUris.problems("not-a-uri").isNotEmpty())
        assertFalse(OAuthRedirectUris.isValid("javascript:alert(1)"))
    }

    // --- Setup validation ---------------------------------------------------

    @Test
    fun `a missing client id is reported instead of failing later`() {
        val validation = validatePersonalSetup(
            type = ConnectionType.GITHUB,
            clientId = "",
            callbackUri = "agentx://oauth/github",
        )

        assertFalse(validation.complete)
        assertTrue(validation.problems.any { it.contains("not configured yet") })
        assertTrue(validation.problems.any { it.contains("Client ID") })
        assertEquals(validation.problems.first(), validation.summary)
    }

    @Test
    fun `an invalid callback uri and broker url are reported`() {
        val badCallback = validatePersonalSetup(
            type = ConnectionType.GITHUB,
            clientId = "gh-client-id",
            callbackUri = "",
        )
        assertFalse(badCallback.complete)
        assertTrue(badCallback.problems.any { it.contains("Callback URL") })

        val badBroker = validatePersonalSetup(
            type = ConnectionType.SUPABASE,
            clientId = "sb-client-id",
            callbackUri = "agentx://oauth/supabase",
            brokerUrl = "broker.example.com",
        )
        assertFalse(badBroker.complete)
        assertTrue(badBroker.problems.any { it.contains("broker") })

        val complete = validatePersonalSetup(
            type = ConnectionType.SUPABASE,
            clientId = "sb-client-id",
            callbackUri = "agentx://oauth/supabase",
            brokerUrl = "https://broker.example.com/exchange",
        )
        assertTrue(complete.complete)
        assertEquals("Supabase integration is configured.", complete.summary)
    }

    @Test
    fun `an unconfigured provider is shown as not configured yet`() {
        val snapshot = setup.snapshot(ConnectionType.GITHUB)

        assertEquals(IntegrationLifecycle.NOT_CONFIGURED, snapshot.lifecycle)
        assertFalse(snapshot.lifecycle.isConnected)
        assertFalse(snapshot.clientIdConfigured)
        assertEquals("not set", snapshot.clientIdLabel)
        assertFalse(snapshot.canConnect)
        assertFalse(snapshot.validation.complete)
        assertEquals("agentx://oauth/github", snapshot.callbackUri)
        assertTrue(snapshot.validation.summary.contains("not configured", ignoreCase = true))

        val availability = built.registry.availability(ConnectionType.GITHUB)
        assertTrue(availability.registered)
        assertFalse(availability.configured)
        assertFalse(availability.supportsHostedAuthorization)
        assertNotNull(availability.unavailableReason)
    }

    @Test
    fun `authorization is refused while the client id is missing`() = runBlocking {
        val provider = assertNotNull(built.registry.provider(ConnectionType.GITHUB))

        val result = provider.beginAuthorization(githubConnection())

        val error = assertNotNull(result.errorOrNull())
        assertEquals(ForgeErrorCode.CONNECTION_OAUTH_UNAVAILABLE, error.code)
        assertEquals("PROVIDER_NOT_CONFIGURED", error.details["reason"])
        assertNull(result.valueOrNull())
    }

    // --- Saving the personal setup -----------------------------------------

    @Test
    fun `saving the client id makes the provider ready without connecting it`() = runBlocking {
        val saved = setup.saveSetup(ConnectionType.GITHUB, "  gh-client-id  ")

        assertEquals("gh-client-id", assertNotNull(saved.valueOrNull()).clientId)
        // The public client values are applied to the live authorization provider.
        assertEquals("gh-client-id", built.github.client.clientId)
        assertEquals("agentx://oauth/github", built.github.client.redirectUri)
        assertTrue(built.github.client.isConfigured)

        val snapshot = setup.snapshot(ConnectionType.GITHUB)
        assertEquals(IntegrationLifecycle.READY_TO_CONNECT, snapshot.lifecycle)
        assertTrue(snapshot.canConnect)
        assertTrue(snapshot.clientIdConfigured)
        assertEquals("configured", snapshot.clientIdLabel)
        assertFalse(snapshot.lifecycle.isConnected, "a saved Client ID is not a connection")
        assertTrue(snapshot.validation.complete)
        assertTrue(built.registry.availability(ConnectionType.GITHUB).configured)
    }

    @Test
    fun `a blank client id is refused and nothing is persisted`() = runBlocking {
        val result = setup.saveSetup(ConnectionType.GITHUB, "   ")

        val error = assertNotNull(result.errorOrNull())
        assertEquals(ForgeErrorCode.CONNECTION_INVALID, error.code)
        assertTrue(error.message.orEmpty().contains("not configured", ignoreCase = true))
        assertNull(store.load(ConnectionType.GITHUB))
        assertFalse(built.github.client.isConfigured)
    }

    @Test
    fun `an invalid broker url is refused instead of being saved`() = runBlocking {
        val result = setup.saveSetup(ConnectionType.GITHUB, "gh-client-id", "broker.example.com")

        val error = assertNotNull(result.errorOrNull())
        assertEquals(ForgeErrorCode.CONNECTION_INVALID, error.code)
        assertTrue(error.message.orEmpty().contains("broker", ignoreCase = true))
        assertNull(store.load(ConnectionType.GITHUB), "a half-configured provider is never stored")
        assertEquals(IntegrationLifecycle.NOT_CONFIGURED, setup.snapshot(ConnectionType.GITHUB).lifecycle)
    }

    @Test
    fun `a brokered setup is saved and selects the server exchange`() = runBlocking {
        val result = setup.saveSetup(
            type = ConnectionType.SUPABASE,
            clientId = "sb-client-id",
            exchangeBrokerUrl = "https://broker.example.com/exchange",
        )
        assertNotNull(result.valueOrNull())

        assertEquals(OAuthExchangeStrategy.EXCHANGE_BROKER, built.supabase.client.exchangeStrategy)
        assertEquals("https://broker.example.com/exchange", built.supabase.client.exchangeBrokerUrl)
        assertEquals("sb-client-id", built.supabase.client.clientId)
    }

    @Test
    fun `the setup survives a restart through the store`() = runBlocking {
        setup.saveSetup(ConnectionType.SUPABASE, "sb-client-id")

        val restarted = ConnectionProviders.fromConfig(
            config = OAuthConfig(),
            http = http,
            setupStore = store,
            clock = { now },
        )
        restarted.setup.refresh()

        assertEquals("sb-client-id", restarted.supabase.client.clientId)
        assertEquals(
            IntegrationLifecycle.READY_TO_CONNECT,
            restarted.setup.snapshot(ConnectionType.SUPABASE).lifecycle,
        )
        assertEquals("sb-client-id", restarted.setup.resolvedClient(ConnectionType.SUPABASE).clientId)
    }

    @Test
    fun `clearing the setup returns the provider to not configured`() = runBlocking {
        setup.saveSetup(ConnectionType.GITHUB, "gh-client-id")

        assertNotNull(setup.clearSetup(ConnectionType.GITHUB).valueOrNull())

        assertNull(store.load(ConnectionType.GITHUB))
        assertEquals(IntegrationLifecycle.NOT_CONFIGURED, setup.snapshot(ConnectionType.GITHUB).lifecycle)
        assertFalse(built.registry.availability(ConnectionType.GITHUB).configured)
        assertFalse(built.github.client.isConfigured)
    }

    @Test
    fun `a service without a provider console has no client id to configure`() = runBlocking {
        val mcp = setup.snapshot(ConnectionType.MCP_SERVER)
        assertEquals(IntegrationLifecycle.READY_TO_CONNECT, mcp.lifecycle)
        assertTrue(mcp.canConnect)
        assertTrue(mcp.validation.complete)
        assertFalse(mcp.clientIdConfigured)

        assertTrue(usesPersonalOAuthSetup(ConnectionType.GITHUB))
        assertTrue(usesPersonalOAuthSetup(ConnectionType.SUPABASE))
        assertFalse(usesPersonalOAuthSetup(ConnectionType.MCP_SERVER))
        assertFalse(usesPersonalOAuthSetup(ConnectionType.CUSTOM_API))

        // Saving a Client ID for such a service is refused rather than stored.
        val result = setup.saveSetup(ConnectionType.MCP_SERVER, "mcp-client-id")
        assertEquals(ForgeErrorCode.CONNECTION_INVALID, assertNotNull(result.errorOrNull()).code)
        assertNull(store.load(ConnectionType.MCP_SERVER))

        val availability = built.registry.availability(ConnectionType.MCP_SERVER)
        assertTrue(availability.registered)
        assertTrue(availability.configured)
    }

    // --- Lifecycle ----------------------------------------------------------

    @Test
    fun `the lifecycle follows the connection and never claims connected on its own`() {
        assertEquals(IntegrationLifecycle.READY_TO_CONNECT, integrationLifecycleOf(configured = true, connection = null))
        assertEquals(
            IntegrationLifecycle.NOT_CONFIGURED,
            integrationLifecycleOf(
                configured = false,
                connection = githubConnection(status = ConnectionStatus.CONNECTED, credentialRef = REF),
            ),
            "an unconfigured provider is never shown as connected",
        )

        assertEquals(
            IntegrationLifecycle.AUTHORIZING,
            integrationLifecycleOf(true, githubConnection(status = ConnectionStatus.AUTHORIZING)),
        )
        assertEquals(
            IntegrationLifecycle.VERIFYING,
            integrationLifecycleOf(true, githubConnection(status = ConnectionStatus.VERIFYING)),
        )
        assertEquals(
            IntegrationLifecycle.VERIFYING,
            integrationLifecycleOf(true, githubConnection(status = ConnectionStatus.CONNECTING)),
        )
        assertEquals(
            IntegrationLifecycle.CONNECTED,
            integrationLifecycleOf(true, githubConnection(status = ConnectionStatus.CONNECTED, credentialRef = REF)),
        )
        assertEquals(
            IntegrationLifecycle.EXPIRED,
            integrationLifecycleOf(true, githubConnection(status = ConnectionStatus.EXPIRED, credentialRef = REF)),
        )
        assertEquals(
            IntegrationLifecycle.ERROR,
            integrationLifecycleOf(true, githubConnection(status = ConnectionStatus.ERROR)),
        )
        assertEquals(
            IntegrationLifecycle.DISCONNECTED,
            integrationLifecycleOf(true, githubConnection(status = ConnectionStatus.DISCONNECTED, credentialRef = REF)),
        )
        assertEquals(
            IntegrationLifecycle.READY_TO_CONNECT,
            integrationLifecycleOf(true, githubConnection(status = ConnectionStatus.DISCONNECTED)),
        )
    }

    @Test
    fun `a configured provider is connected only with a verified connection`() = runBlocking {
        setup.saveSetup(ConnectionType.GITHUB, "gh-client-id")

        val authorizing = setup.snapshot(
            ConnectionType.GITHUB,
            githubConnection(status = ConnectionStatus.AUTHORIZING),
        )
        assertEquals(IntegrationLifecycle.AUTHORIZING, authorizing.lifecycle)
        assertFalse(authorizing.lifecycle.isConnected)
        assertFalse(authorizing.canConnect, "a second authorization cannot start while one is open")

        val connected = setup.snapshot(
            ConnectionType.GITHUB,
            githubConnection(
                status = ConnectionStatus.CONNECTED,
                credentialRef = REF,
                accountLabel = "@octocat",
            ),
        )
        assertEquals(IntegrationLifecycle.CONNECTED, connected.lifecycle)
        assertTrue(connected.lifecycle.isConnected)
        assertEquals("@octocat", connected.accountLabel)
        assertFalse(connected.canConnect)
        assertTrue(connected.lifecycle.isConnected)
    }

    // --- Credential isolation ----------------------------------------------

    @Test
    fun `a client id is public and a secret has no home in the setup`() {
        val value = PersonalOAuthSetup(
            clientId = "gh-client-id",
            exchangeBrokerUrl = "https://broker.example.com/exchange",
            scopes = setOf("repo"),
        )
        assertTrue(value.isConfigured)
        // The type never renders the client id it holds.
        assertFalse(value.toString().contains("gh-client-id"))

        val client = value.toClientConfig("agentx://oauth/github", OAuthProviderConfig())
        assertTrue(client.isConfigured)
        assertNull(client.configurationProblem)
        assertEquals(OAuthExchangeStrategy.EXCHANGE_BROKER, client.exchangeStrategy)
        assertEquals(setOf("repo"), client.configuredScopes)

        // Nowhere in the setup surface is there a field for a client secret.
        val secret = "gh-client-secret-value"
        val surfaces = listOf(
            value.toString(),
            client.toString(),
            setup.snapshot(ConnectionType.GITHUB).toString(),
            built.github.client.toString(),
            built.github.descriptor.toString(),
        )
        surfaces.forEach { surface ->
            assertFalse(surface.contains(secret), "a secret must have no home here: $surface")
        }
    }

    // --- Setup instructions -------------------------------------------------

    @Test
    fun `the setup guides explain each provider and ask for no secret`() {
        val github = setup.guide(ConnectionType.GITHUB)
        assertEquals("How to set up GitHub", github.title)
        assertEquals(ConnectionType.GITHUB, github.type)
        assertTrue(github.steps.size >= 5)
        assertTrue(github.developerSettingsUrl?.contains("github.com") == true)
        assertTrue(github.steps.any { it.text.contains("Client ID") })
        assertTrue(github.notes.any { it.contains("never stores a GitHub client secret") })
        assertTrue(github.notes.any { it.contains("password") })

        val supabase = setup.guide(ConnectionType.SUPABASE)
        assertEquals(ConnectionType.SUPABASE, supabase.type)
        assertTrue(supabase.developerSettingsUrl?.contains("supabase.com") == true)
        assertTrue(supabase.notes.any { it.contains("client secret") })

        val mcp = setup.guide(ConnectionType.MCP_SERVER)
        assertEquals(ConnectionType.MCP_SERVER, mcp.type)
        assertTrue(mcp.steps.any { it.text.contains("transport") })
        assertTrue(mcp.notes.any { it.contains("not enabled") })

        // Every step is numbered, so the screen can render them in order.
        listOf(github, supabase, mcp).forEach { guide ->
            assertEquals(guide.steps.indices.map { it + 1 }, guide.steps.map { it.number })
        }
    }

    // --- Helpers ------------------------------------------------------------

    private fun githubConnection(
        status: ConnectionStatus = ConnectionStatus.NOT_CONNECTED,
        credentialRef: String? = null,
        accountLabel: String? = null,
    ): Connection = Connection(
        id = ConnectionId("c-github"),
        displayName = "Personal GitHub",
        type = ConnectionType.GITHUB,
        config = ConnectionConfig(authMethod = ConnectionAuthMethod.OAUTH),
        capabilities = ConnectionCapabilities.GITHUB,
        status = status,
        credentialRef = credentialRef,
        accountLabel = accountLabel,
    )

    private companion object {
        const val REF = "connections.secret.c-github"
    }
}
