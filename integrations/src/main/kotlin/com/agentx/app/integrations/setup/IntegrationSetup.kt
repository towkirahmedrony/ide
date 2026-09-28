package com.agentx.app.integrations.setup

import com.agentx.app.core.config.OAuthConfig
import com.agentx.app.core.config.OAuthProviderConfig
import com.agentx.app.integrations.connection.Connection
import com.agentx.app.integrations.connection.ConnectionStatus
import com.agentx.app.integrations.connection.ConnectionType
import com.agentx.app.integrations.oauth.OAuthCallbackAuthority
import com.agentx.app.integrations.oauth.OAuthClientConfig
import com.agentx.app.integrations.oauth.OAuthRedirectUris

/**
 * Lifecycle of a personal integration, as the Connections page shows it.
 *
 * This is derived from provider setup plus the saved connection. A provider is
 * never shown as [CONNECTED] without a verified grant.
 */
enum class IntegrationLifecycle(val displayName: String) {
    NOT_CONFIGURED("Not configured"),
    READY_TO_CONNECT("Ready to connect"),
    AUTHORIZING("Waiting for authorization"),
    VERIFYING("Verifying"),
    CONNECTED("Connected"),
    EXPIRED("Connection expired"),
    ERROR("Connection error"),
    DISCONNECTED("Disconnected"),
    ;

    val isConnected: Boolean get() = this == CONNECTED

    val isBusy: Boolean get() = this == AUTHORIZING || this == VERIFYING
}

/**
 * Non-secret OAuth app configuration the owner entered in this personal IDE.
 *
 * Client IDs are public values. Client **secrets** never appear here and are
 * never persisted by the app.
 */
data class PersonalOAuthSetup(
    val clientId: String = "",
    val exchangeBrokerUrl: String? = null,
    val scopes: Set<String> = emptySet(),
    val updatedAtMillis: Long = 0L,
) {
    val isConfigured: Boolean get() = clientId.isNotBlank()

    override fun toString(): String =
        "PersonalOAuthSetup(clientId=${if (isConfigured) "configured" else "missing"}, " +
            "broker=${if (exchangeBrokerUrl.isNullOrBlank()) "absent" else "present"})"
}

/** Persistence for [PersonalOAuthSetup]. Implementations must never store secrets. */
interface IntegrationSetupStore {
    suspend fun load(type: ConnectionType): PersonalOAuthSetup?

    suspend fun save(type: ConnectionType, setup: PersonalOAuthSetup)

    suspend fun clear(type: ConnectionType)

    suspend fun loadAll(): Map<ConnectionType, PersonalOAuthSetup>
}

/** In-memory store used by tests and as the JVM default. */
class InMemoryIntegrationSetupStore(
    initial: Map<ConnectionType, PersonalOAuthSetup> = emptyMap(),
) : IntegrationSetupStore {

    private val items = LinkedHashMap<ConnectionType, PersonalOAuthSetup>()

    init {
        items.putAll(initial)
    }

    override suspend fun load(type: ConnectionType): PersonalOAuthSetup? = items[type]

    override suspend fun save(type: ConnectionType, setup: PersonalOAuthSetup) {
        items[type] = setup
    }

    override suspend fun clear(type: ConnectionType) {
        items.remove(type)
    }

    override suspend fun loadAll(): Map<ConnectionType, PersonalOAuthSetup> = LinkedHashMap(items)
}

/** Result of checking whether a provider can start authorization. */
data class SetupValidation(
    val type: ConnectionType,
    val problems: List<String> = emptyList(),
) {
    val complete: Boolean get() = problems.isEmpty()

    val summary: String
        get() = problems.firstOrNull() ?: "${type.displayName} integration is configured."
}

/**
 * What the setup / Connections screens render for one provider.
 *
 * [clientId] is a public value; it is never a token. [callbackUri] is generated
 * from the application identity and is safe to copy into the provider console.
 */
data class ProviderSetupSnapshot(
    val type: ConnectionType,
    val lifecycle: IntegrationLifecycle,
    val clientId: String,
    val callbackUri: String,
    val exchangeBrokerUrl: String?,
    val accountLabel: String? = null,
    val statusMessage: String? = null,
    val validation: SetupValidation,
) {
    val clientIdConfigured: Boolean get() = clientId.isNotBlank()

    val canConnect: Boolean get() = validation.complete &&
        lifecycle != IntegrationLifecycle.AUTHORIZING &&
        lifecycle != IntegrationLifecycle.VERIFYING &&
        lifecycle != IntegrationLifecycle.CONNECTED

    val clientIdLabel: String get() = if (clientIdConfigured) "configured" else "not set"

    override fun toString(): String =
        "ProviderSetupSnapshot(type=${type.name}, lifecycle=$lifecycle, clientId=${clientIdLabel})"
}

data class SetupInstructionStep(
    val number: Int,
    val text: String,
)

/**
 * Provider-specific "How to set up" copy, kept isolated so it can be updated
 * without touching the OAuth or Connection Manager code.
 */
data class ProviderSetupGuide(
    val type: ConnectionType,
    val title: String,
    val summary: String,
    val steps: List<SetupInstructionStep>,
    val developerSettingsUrl: String? = null,
    val developerSettingsLabel: String? = null,
    val notes: List<String> = emptyList(),
)

/** Built-in setup guides. Provider-specific and intentionally easy to replace. */
object IntegrationSetupGuides {

    fun forType(type: ConnectionType): ProviderSetupGuide = when (type) {
        ConnectionType.GITHUB -> GITHUB
        ConnectionType.SUPABASE -> SUPABASE
        ConnectionType.MCP_SERVER -> MCP
        ConnectionType.CUSTOM_API -> CUSTOM
    }

    val GITHUB: ProviderSetupGuide = ProviderSetupGuide(
        type = ConnectionType.GITHUB,
        title = "How to set up GitHub",
        summary = "Create a GitHub OAuth App for this personal IDE, then save the Client ID here.",
        developerSettingsUrl = "https://github.com/settings/developers",
        developerSettingsLabel = "GitHub Developer Settings",
        steps = listOf(
            SetupInstructionStep(1, "Open GitHub Developer Settings."),
            SetupInstructionStep(2, "Create or configure an OAuth App for this IDE."),
            SetupInstructionStep(3, "Add the callback URL shown by this app."),
            SetupInstructionStep(4, "Copy the Client ID."),
            SetupInstructionStep(5, "Save it in this app's GitHub integration settings."),
            SetupInstructionStep(6, "Tap Connect GitHub."),
            SetupInstructionStep(7, "Authorize on GitHub's official page."),
        ),
        notes = listOf(
            "This app never asks for your GitHub password.",
            "This app never stores a GitHub client secret.",
            "If GitHub requires a confidential client secret for the code exchange, " +
                "point an optional exchange broker at a server you control. Do not paste the secret here.",
        ),
    )

    val SUPABASE: ProviderSetupGuide = ProviderSetupGuide(
        type = ConnectionType.SUPABASE,
        title = "How to set up Supabase",
        summary = "Register an OAuth App in your Supabase organization, then save the Client ID here.",
        developerSettingsUrl = "https://supabase.com/dashboard/org/_/apps",
        developerSettingsLabel = "Supabase OAuth Apps",
        steps = listOf(
            SetupInstructionStep(1, "Open the Supabase Dashboard."),
            SetupInstructionStep(2, "Open your organization settings and create or configure an OAuth App."),
            SetupInstructionStep(3, "Add the callback URL shown by this app."),
            SetupInstructionStep(4, "Copy the OAuth Client ID."),
            SetupInstructionStep(5, "Save it in this app's Supabase integration settings."),
            SetupInstructionStep(6, "Tap Connect Supabase."),
            SetupInstructionStep(7, "Authorize on Supabase's official page."),
        ),
        notes = listOf(
            "Supabase scopes are configured on the OAuth App, not requested per sign-in.",
            "This app never asks for your Supabase password.",
            "This app never stores a Supabase client secret.",
            "If the code exchange requires a confidential client secret, use an exchange broker " +
                "you control. Do not paste the secret here.",
        ),
    )

    val MCP: ProviderSetupGuide = ProviderSetupGuide(
        type = ConnectionType.MCP_SERVER,
        title = "How to add an MCP server",
        summary = "Register a Model Context Protocol server by name, URL and transport.",
        steps = listOf(
            SetupInstructionStep(1, "Choose a name for the server."),
            SetupInstructionStep(2, "Enter the server URL (or command, for stdio)."),
            SetupInstructionStep(3, "Select the transport the server uses."),
            SetupInstructionStep(4, "Choose how the server authenticates: none, API key, token, or OAuth if it supports it."),
            SetupInstructionStep(5, "Save the server. Credentials, when needed, are stored in secure storage."),
        ),
        notes = listOf(
            "MCP tool execution is not enabled in this step — this only saves the connection.",
        ),
    )

    val CUSTOM: ProviderSetupGuide = ProviderSetupGuide(
        type = ConnectionType.CUSTOM_API,
        title = "How to add a custom API",
        summary = "Describe a generic HTTP API the agent may call through tools later.",
        steps = listOf(
            SetupInstructionStep(1, "Enter a display name."),
            SetupInstructionStep(2, "Enter the API base URL."),
            SetupInstructionStep(3, "Choose an authentication method if the API needs one."),
        ),
    )
}

fun integrationLifecycleOf(
    configured: Boolean,
    connection: Connection?,
): IntegrationLifecycle {
    if (!configured) return IntegrationLifecycle.NOT_CONFIGURED
    val status = connection?.status ?: return IntegrationLifecycle.READY_TO_CONNECT
    return when (status) {
        ConnectionStatus.AUTHORIZING -> IntegrationLifecycle.AUTHORIZING
        ConnectionStatus.VERIFYING, ConnectionStatus.CONNECTING -> IntegrationLifecycle.VERIFYING
        ConnectionStatus.CONNECTED -> IntegrationLifecycle.CONNECTED
        ConnectionStatus.EXPIRED -> IntegrationLifecycle.EXPIRED
        ConnectionStatus.ERROR -> IntegrationLifecycle.ERROR
        ConnectionStatus.DISCONNECTED ->
            if (connection.hasCredential) IntegrationLifecycle.DISCONNECTED
            else IntegrationLifecycle.READY_TO_CONNECT
        ConnectionStatus.NOT_CONNECTED -> IntegrationLifecycle.READY_TO_CONNECT
    }
}

fun OAuthConfig.provider(type: ConnectionType): OAuthProviderConfig = when (type) {
    ConnectionType.GITHUB -> github
    ConnectionType.SUPABASE -> supabase
    ConnectionType.MCP_SERVER, ConnectionType.CUSTOM_API -> OAuthProviderConfig()
}

fun PersonalOAuthSetup.toClientConfig(redirectUri: String, fallback: OAuthProviderConfig): OAuthClientConfig =
    OAuthClientConfig(
        clientId = clientId.ifBlank { fallback.clientId },
        redirectUri = redirectUri,
        exchangeBrokerUrl = exchangeBrokerUrl ?: fallback.exchangeBrokerUrl,
        configuredScopes = scopes.ifEmpty { fallback.scopes },
    )

/**
 * True for the services this build authorizes through a personal OAuth app — one
 * whose Client ID the owner registers in the provider's console.
 *
 * Other services have no provider console to register an app in: an MCP server or
 * a custom API is described by the user in this app. They are therefore never
 * "not configured" for a missing Client ID, and their primary action is adding
 * the service rather than opening a browser authorization that does not exist.
 */
fun usesPersonalOAuthSetup(type: ConnectionType): Boolean =
    type == ConnectionType.GITHUB || type == ConnectionType.SUPABASE

fun validatePersonalSetup(
    type: ConnectionType,
    clientId: String,
    callbackUri: String,
    brokerUrl: String? = null,
): SetupValidation {
    val problems = mutableListOf<String>()
    if (clientId.isBlank()) {
        problems += "${type.displayName} integration is not configured yet."
        problems += "A Client ID is required."
    }
    problems += OAuthRedirectUris.problems(callbackUri)
    brokerUrl?.takeIf { it.isNotBlank() }?.let { url ->
        if (!OAuthRedirectUris.isValid(url) && !url.startsWith("https://")) {
            problems += "Exchange broker URL must be a valid https URL"
        }
    }
    return SetupValidation(type = type, problems = problems.distinct())
}
