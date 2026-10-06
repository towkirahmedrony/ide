package com.agentx.app.integrations.oauth

import com.agentx.app.core.ForgeError
import com.agentx.app.core.ForgeErrorCode
import com.agentx.app.core.ForgeResult
import com.agentx.app.core.failure
import com.agentx.app.core.success
import com.agentx.app.integrations.connection.Connection
import com.agentx.app.integrations.connection.ConnectionId
import com.agentx.app.integrations.connection.ConnectionType
import com.agentx.app.integrations.connection.DeviceAuthorization
import com.agentx.app.integrations.connection.connectionFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * A device authorization that has been requested but not yet answered.
 *
 * This is memory only: it holds the `device_code` (a credential) and losing it on
 * process death is safe — the user simply starts the connection again. Nothing
 * here is persisted or exposed to the UI.
 */
data class PendingDeviceFlow(
    val connectionId: ConnectionId,
    val type: ConnectionType,
    val deviceCode: String,
    val scopes: Set<String>,
    val expiresAtMillis: Long,
    val intervalSeconds: Long,
) {
    fun isExpired(now: Long): Boolean = now >= expiresAtMillis

    override fun toString(): String =
        "PendingDeviceFlow(connectionId=$connectionId, type=${type.name}, expiresAtMillis=$expiresAtMillis)"
}

/** Holds the device authorizations currently waiting for the user to approve. */
interface DeviceFlowSessionStore {

    fun save(pending: PendingDeviceFlow)

    fun pendingFor(connectionId: ConnectionId): PendingDeviceFlow?

    fun cancel(connectionId: ConnectionId)
}

/** Default [DeviceFlowSessionStore]; in-memory by design, never persisted. */
class InMemoryDeviceFlowSessionStore : DeviceFlowSessionStore {

    private val pending = LinkedHashMap<ConnectionId, PendingDeviceFlow>()

    override fun save(pending: PendingDeviceFlow) {
        this.pending[pending.connectionId] = pending
    }

    override fun pendingFor(connectionId: ConnectionId): PendingDeviceFlow? = pending[connectionId]

    override fun cancel(connectionId: ConnectionId) {
        pending.remove(connectionId)
    }
}

/**
 * The OAuth 2.0 Device Authorization Grant mechanism, independent of any service.
 *
 * It requests a device code through a [DeviceFlowClient], keeps the pending attempt
 * in memory, and polls the token endpoint at no faster than the interval the
 * provider allows. Mapping tokens onto capabilities and persisting the grant stay
 * with the provider and the Connection Manager.
 *
 * Cancellation is honoured: the loop uses the caller's coroutine, so cancelling
 * it stops polling immediately and never leaves a background poller behind.
 * [sleep] is injectable so tests need not wait real time.
 */
class DeviceFlowRunner(
    private val client: DeviceFlowClient,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (millis: Long) -> Unit = { delay(it) },
    private val sessions: DeviceFlowSessionStore = InMemoryDeviceFlowSessionStore(),
) {

    fun pendingFor(connectionId: ConnectionId): PendingDeviceFlow? = sessions.pendingFor(connectionId)

    fun cancel(connectionId: ConnectionId) = sessions.cancel(connectionId)

    /**
     * Requests a device code and remembers the attempt in memory. Only the
     * user-facing values ([DeviceAuthorization.userCode]/[DeviceAuthorization.verificationUri])
     * leave this call; the device code stays in the pending session.
     */
    suspend fun begin(
        connection: Connection,
        scopes: Set<String>,
    ): ForgeResult<DeviceAuthorization, ForgeError> {
        return when (val result = client.requestDeviceCode(scopes)) {
            is OAuthDeviceCodeResult.Success -> {
                val code = result.code
                val expiresAt = clock() + code.expiresInSeconds * 1000
                sessions.save(
                    PendingDeviceFlow(
                        connectionId = connection.id,
                        type = connection.type,
                        deviceCode = code.deviceCode,
                        scopes = scopes,
                        expiresAtMillis = expiresAt,
                        intervalSeconds = code.intervalSeconds,
                    ),
                )
                success(
                    DeviceAuthorization(
                        connectionId = connection.id,
                        type = connection.type,
                        displayName = connection.displayName,
                        userCode = code.userCode,
                        verificationUri = code.verificationUri,
                        expiresAtMillis = expiresAt,
                        intervalSeconds = code.intervalSeconds,
                    ),
                )
            }

            is OAuthDeviceCodeResult.Failure -> failure(
                deviceFailure(result.reason, result.message, connection.id),
            )
        }
    }

    /**
     * Polls until the user approves, the attempt fails, expires or the calling
     * coroutine is cancelled. [onState] reports every transition so the UI can show
     * the waiting, polling and terminal states without ever seeing a token.
     */
    suspend fun complete(
        connectionId: ConnectionId,
        onState: suspend (DeviceFlowState) -> Unit,
    ): ForgeResult<OAuthTokenSet, ForgeError> {
        val initial = sessions.pendingFor(connectionId)
            ?: return failure(
                deviceFailure(
                    reason = OAuthFailureReason.NOT_CONNECTED,
                    message = "No device authorization is waiting for this connection.",
                    connectionId = connectionId,
                ),
            )
        var intervalSeconds = initial.intervalSeconds
        onState(DeviceFlowState.WAITING_FOR_USER)

        try {
            while (currentCoroutineContext().isActive) {
                val pending = sessions.pendingFor(connectionId)
                    ?: return failure(
                        deviceFailure(
                            reason = OAuthFailureReason.NOT_CONNECTED,
                            message = "The device authorization was cancelled.",
                            connectionId = connectionId,
                        ),
                    )
                if (pending.isExpired(clock())) {
                    sessions.cancel(connectionId)
                    onState(DeviceFlowState.EXPIRED)
                    return failure(
                        deviceFailure(
                            reason = OAuthFailureReason.DEVICE_CODE_EXPIRED,
                            message = "The device code expired before it was approved.",
                            connectionId = connectionId,
                        ),
                    )
                }

                onState(DeviceFlowState.POLLING)
                when (val result = client.poll(pending.deviceCode)) {
                    is OAuthDevicePollResult.Pending -> {
                        onState(DeviceFlowState.WAITING_FOR_USER)
                        // Never poll faster than the provider allows.
                        sleep(intervalSeconds * 1000)
                    }

                    is OAuthDevicePollResult.SlowDown -> {
                        intervalSeconds =
                            result.intervalSeconds.coerceAtLeast(intervalSeconds + SLOW_DOWN_STEP_SECONDS)
                        onState(DeviceFlowState.WAITING_FOR_USER)
                        sleep(intervalSeconds * 1000)
                    }

                    is OAuthDevicePollResult.Success -> {
                        sessions.cancel(connectionId)
                        return success(result.tokens)
                    }

                    is OAuthDevicePollResult.Failure -> {
                        sessions.cancel(connectionId)
                        onState(DeviceFlowState.forFailure(result.reason))
                        return failure(deviceFailure(result.reason, result.message, connectionId))
                    }
                }
            }

            // The caller was cancelled: stop cleanly, leaving the connection record
            // for the user to retry.
            sessions.cancel(connectionId)
            return failure(
                deviceFailure(
                    reason = OAuthFailureReason.NOT_CONNECTED,
                    message = "The device authorization was cancelled.",
                    connectionId = connectionId,
                ),
            )
        } catch (cancelled: CancellationException) {
            // Cancellation drops the pending attempt so nothing keeps polling, then
            // propagates: the owning coroutine decides what happens next.
            sessions.cancel(connectionId)
            throw cancelled
        }
    }

    private fun deviceFailure(
        reason: OAuthFailureReason,
        message: String,
        connectionId: ConnectionId,
    ): ForgeError = connectionFailure(
        code = when (reason) {
            OAuthFailureReason.AUTHORIZATION_DENIED -> ForgeErrorCode.CONNECTION_OAUTH_DENIED
            OAuthFailureReason.DEVICE_CODE_EXPIRED -> ForgeErrorCode.CONNECTION_CREDENTIAL_EXPIRED
            else -> ForgeErrorCode.CONNECTION_OAUTH_FAILED
        },
        message = message,
        details = mapOf(
            "reason" to reason.name,
            "connectionId" to connectionId.value,
        ),
    )

    companion object {
        /** Extra seconds RFC 8628 asks the client to add after `slow_down`. */
        const val SLOW_DOWN_STEP_SECONDS: Long = 5L
    }
}
