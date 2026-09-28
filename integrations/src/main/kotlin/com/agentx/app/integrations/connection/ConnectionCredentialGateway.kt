package com.agentx.app.integrations.connection

import com.agentx.app.core.ForgeError
import com.agentx.app.core.ForgeResult

/**
 * Hands a credential to the code that actually calls the service, and to nothing
 * else.
 *
 * The service client runs inside the tool/transport layer, never inside the agent
 * loop. The credential is passed as a lambda argument so it cannot be returned,
 * stored on a handle, copied into a tool result, or read out of a manager call by
 * the agent:
 *
 * ```
 * gateway.withCredential(connectionId) { token -> serviceClient.get("/user", token) }
 * ```
 *
 * Implementations refresh an expiring OAuth grant before invoking [block] and fail
 * with [ConnectionAuthorizationFailure.EXPIRED] when the grant can no longer be
 * used.
 */
interface ConnectionCredentialGateway {

    suspend fun <T> withCredential(
        connectionId: ConnectionId,
        block: suspend (String) -> T,
    ): ForgeResult<T, ForgeError>
}
