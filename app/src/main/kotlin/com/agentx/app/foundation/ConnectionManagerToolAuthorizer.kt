package com.agentx.app.foundation

import com.agentx.app.core.errorOrNull
import com.agentx.app.core.valueOrNull
import com.agentx.app.integrations.connection.ConnectionAuthorizationError
import com.agentx.app.integrations.connection.ConnectionAuthorizationFailure
import com.agentx.app.integrations.connection.ConnectionCapability
import com.agentx.app.integrations.connection.ConnectionId
import com.agentx.app.integrations.connection.ConnectionManager
import com.agentx.app.integrations.connection.ConnectionType
import com.agentx.app.tools.ToolConnectionAuthorization
import com.agentx.app.tools.ToolConnectionAuthorizer
import com.agentx.app.tools.ToolConnectionAuthorizationError
import com.agentx.app.tools.ToolConnectionCapability
import com.agentx.app.tools.ToolConnectionDenial
import com.agentx.app.tools.ToolConnectionHandle
import com.agentx.app.tools.ToolConnectionRequirement
import com.agentx.app.tools.ToolConnectionType

/**
 * Adapts [ConnectionManager] to the Tool System's connection port.
 *
 * Tools request a type plus a capability; they receive a handle with a
 * credential reference, never the secret. The Agent never talks to this class.
 */
class ConnectionManagerToolAuthorizer(
    private val manager: ConnectionManager,
) : ToolConnectionAuthorizer {

    override suspend fun authorize(
        requirement: ToolConnectionRequirement,
        connectionId: String?,
    ): ToolConnectionAuthorization {
        val result = manager.authorize(
            type = requirement.type.toConnectionType(),
            capability = ConnectionCapability(requirement.capability.id),
            connectionId = connectionId?.takeIf { it.isNotBlank() }?.let(::ConnectionId),
        )
        result.valueOrNull()?.let { granted ->
            return ToolConnectionAuthorization.Granted(
                ToolConnectionHandle(
                    connectionId = granted.id.value,
                    displayName = granted.displayName,
                    type = requirement.type,
                    capabilities = granted.capabilities.map { ToolConnectionCapability(it.id) }.toSet(),
                    endpoint = granted.endpoint,
                    credentialRef = granted.credentialRef,
                ),
            )
        }
        val error = result.errorOrNull() ?: return ToolConnectionAuthorization.Denied(
            ToolConnectionAuthorizationError(
                denial = ToolConnectionDenial.NOT_AUTHORIZED,
                type = requirement.type,
                capability = requirement.capability,
                connectionId = connectionId,
                message = "The connection is not authorized for this tool",
            ),
        )
        return ToolConnectionAuthorization.Denied(error.toToolError(requirement))
    }
}

private fun ToolConnectionType.toConnectionType(): ConnectionType = when (this) {
    ToolConnectionType.GITHUB -> ConnectionType.GITHUB
    ToolConnectionType.SUPABASE -> ConnectionType.SUPABASE
    ToolConnectionType.MCP_SERVER -> ConnectionType.MCP_SERVER
    ToolConnectionType.CUSTOM_API -> ConnectionType.CUSTOM_API
}

private fun ConnectionAuthorizationError.toToolError(
    requirement: ToolConnectionRequirement,
): ToolConnectionAuthorizationError = ToolConnectionAuthorizationError(
    denial = when (failure) {
        ConnectionAuthorizationFailure.NOT_FOUND -> ToolConnectionDenial.NOT_FOUND
        ConnectionAuthorizationFailure.DISABLED -> ToolConnectionDenial.DISABLED
        ConnectionAuthorizationFailure.MISSING_CAPABILITY -> ToolConnectionDenial.MISSING_CAPABILITY
        ConnectionAuthorizationFailure.MISSING_CREDENTIAL -> ToolConnectionDenial.MISSING_CREDENTIAL
        ConnectionAuthorizationFailure.NOT_AUTHORIZED -> ToolConnectionDenial.NOT_AUTHORIZED
        ConnectionAuthorizationFailure.AUTHORIZING -> ToolConnectionDenial.AUTHORIZING
        ConnectionAuthorizationFailure.EXPIRED -> ToolConnectionDenial.CREDENTIAL_EXPIRED
    },
    type = requirement.type,
    capability = requirement.capability,
    connectionId = connectionId?.value,
    message = message,
)
