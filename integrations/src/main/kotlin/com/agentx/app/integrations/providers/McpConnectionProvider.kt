package com.agentx.app.integrations.providers

import com.agentx.app.core.ForgeError
import com.agentx.app.core.ForgeErrorCode
import com.agentx.app.core.ForgeResult
import com.agentx.app.core.failure
import com.agentx.app.core.success
import com.agentx.app.integrations.connection.AuthorizationStart
import com.agentx.app.integrations.connection.Connection
import com.agentx.app.integrations.connection.ConnectionAuthMethod
import com.agentx.app.integrations.connection.ConnectionCapabilities
import com.agentx.app.integrations.connection.ConnectionCapability
import com.agentx.app.integrations.connection.ConnectionType
import com.agentx.app.integrations.connection.ProviderCapabilityInfo
import com.agentx.app.integrations.connection.ProviderDescriptor
import com.agentx.app.integrations.connection.ProviderGrant
import com.agentx.app.integrations.connection.ProviderIdentity
import com.agentx.app.integrations.connection.ProviderRevocation
import com.agentx.app.integrations.connection.ProviderToolCatalog
import com.agentx.app.integrations.connection.ProviderToolCategory
import com.agentx.app.integrations.connection.ProviderToolSpec
import com.agentx.app.integrations.connection.connectionFailure

/**
 * MCP servers.
 *
 * An MCP server is user-supplied, so there is no hosted authorization page to open
 * until the server itself advertises one: [beginAuthorization] therefore returns
 * [AuthorizationStart.ManualFormRequired] and the app's own form collects the URL,
 * transport and — when the server needs one — an API key or token.
 *
 * Discovery of a server's own OAuth metadata, and any MCP protocol traffic, are
 * deliberately not implemented here: this provider registers no tools and never
 * reports a connection as connected on its own.
 */
class McpConnectionProvider(
    private val clock: () -> Long = System::currentTimeMillis,
) : ConnectionProvider {

    override val descriptor: ProviderDescriptor = ProviderDescriptor(
        type = ConnectionType.MCP_SERVER,
        displayName = "MCP",
        description = "Connect MCP servers and extend the agent with external tools.",
        details = "Register a Model Context Protocol server to expose its tools, resources and " +
            "prompts to the agent. Credentials, when the server needs them, are stored in the " +
            "platform's secure storage.",
        capabilities = listOf(
            ProviderCapabilityInfo(
                capability = ConnectionCapabilities.TOOLS,
                label = "Tools",
                description = "Tools the server advertises.",
            ),
            ProviderCapabilityInfo(
                capability = ConnectionCapabilities.RESOURCES,
                label = "Resources",
                description = "Resources the server exposes.",
            ),
            ProviderCapabilityInfo(
                capability = ConnectionCapabilities.PROMPTS,
                label = "Prompts",
                description = "Prompt templates the server provides.",
            ),
        ),
        authMethods = listOf(
            ConnectionAuthMethod.NONE,
            ConnectionAuthMethod.API_KEY,
            ConnectionAuthMethod.ACCESS_TOKEN,
        ),
        requiresEndpoint = true,
        authorizationNote = "Use an API key or token only when the server requires one; " +
            "servers that support OAuth are authorized through the server's own page.",
    )

    override fun authMethods(): Set<ConnectionAuthMethod> = descriptor.authMethods.toSet()

    /**
     * No tools are installed for MCP yet: the server's own tool list is not
     * implemented, so declaring tools here would promise something that cannot run.
     */
    override fun toolCatalog(): ProviderToolCatalog = ProviderToolCatalog(
        provider = ConnectionType.MCP_SERVER,
        tools = emptyList(),
    )

    override fun scopesFor(connection: Connection): Set<String> = emptySet()

    override fun capabilitiesFor(connection: Connection, scopes: Set<String>): Set<ConnectionCapability> =
        connection.capabilities

    override suspend fun beginAuthorization(connection: Connection): ForgeResult<AuthorizationStart, ForgeError> {
        if (connection.config.endpoint.isNullOrBlank()) {
            return failure(
                connectionFailure(
                    code = ForgeErrorCode.CONNECTION_INVALID,
                    message = "A server URL is required for an MCP server",
                    details = mapOf("connectionId" to connection.id.value, "type" to connection.type.name),
                ),
            )
        }
        return success(
            AuthorizationStart.ManualFormRequired(
                connectionId = connection.id,
                type = connection.type,
                displayName = connection.displayName,
                reason = "This server has no hosted authorization page, so its credential is entered " +
                    "in the app and verified against the server.",
                credentialLabel = "API key or token (optional)",
            ),
        )
    }

    /**
     * MCP has no callback: the credential path is the manual form, and a redirect
     * can never complete an MCP connection.
     */
    override suspend fun completeAuthorization(
        connection: Connection,
        callbackUri: String,
    ): ForgeResult<ProviderGrant, ForgeError> = failure(
        connectionFailure(
            code = ForgeErrorCode.CONNECTION_OAUTH_UNAVAILABLE,
            message = "MCP servers are not authorized through a redirect",
            details = mapOf("connectionId" to connection.id.value, "type" to connection.type.name),
        ),
    )

    /**
     * MCP traffic is not implemented, so the app cannot prove the server works.
     * Reporting a verification failure is the honest outcome; a connection must not
     * be shown as connected on the strength of a stored string alone.
     */
    override suspend fun verify(connection: Connection, payload: String): ForgeResult<ProviderIdentity, ForgeError> =
        unsupported(connection, "MCP connections cannot be verified yet.")

    override suspend fun refresh(connection: Connection, payload: String): ForgeResult<String, ForgeError> =
        success(payload)

    override suspend fun revoke(connection: Connection, payload: String): ProviderRevocation =
        ProviderRevocation(
            supported = false,
            revoked = false,
            message = "MCP servers do not expose a revocation endpoint; the stored credential was deleted.",
        )

    override suspend fun verifyManualCredential(
        connection: Connection,
        credential: String,
    ): ForgeResult<ProviderIdentity, ForgeError> = unsupported(
        connection,
        "MCP connections cannot be verified yet, so no credential was stored.",
    )

    private fun unsupported(connection: Connection, message: String): ForgeResult<ProviderIdentity, ForgeError> =
        failure(
            connectionFailure(
                code = ForgeErrorCode.CONNECTION_TEST_UNSUPPORTED,
                message = message,
                details = mapOf(
                    "connectionId" to connection.id.value,
                    "type" to connection.type.name,
                    "capabilities" to emptyList<String>(),
                ),
            ),
        )
}
