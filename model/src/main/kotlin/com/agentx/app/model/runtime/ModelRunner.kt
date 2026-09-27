package com.agentx.app.model.runtime

import com.agentx.app.core.logging.ForgeLogger
import com.agentx.app.core.logging.ForgeLoggers
import com.agentx.app.core.logging.LogLevel
import com.agentx.app.model.preset.ModelCredentialResolver
import com.agentx.app.model.preset.ModelPreset
import com.agentx.app.model.preset.ModelProviderType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext

/**
 * Starts, stops and verifies one *kind* of model runtime.
 *
 * The Model Manager depends only on this interface, so a Colab runtime, a
 * phone-local server, a remote machine and anything added later are
 * interchangeable. Runners own the lifecycle state machine for the presets they
 * support and never talk to the gateway: connecting a model to the gateway is the
 * manager's job.
 */
interface ModelRunner {
    val id: String

    val supportedProviderTypes: Set<ModelProviderType>

    fun supports(preset: ModelPreset): Boolean = preset.providerType in supportedProviderTypes

    fun status(presetId: String): ModelRuntimeStatus

    fun statuses(): StateFlow<Map<String, ModelRuntimeStatus>>

    /** Brings the model online; must be bounded and always end in a terminal state. */
    suspend fun start(preset: ModelPreset): RunnerOperationResult

    /** Re-runs detection and the health check for a model that was connected. */
    suspend fun reconnect(preset: ModelPreset): RunnerOperationResult

    /** Stops using the model. Runtimes the app cannot terminate are left untouched. */
    suspend fun stop(preset: ModelPreset): RunnerOperationResult

    /** Verifies the model API and reflects the result in the status. */
    suspend fun healthCheck(preset: ModelPreset): ModelHealth

    /** Drops cached state for a preset that no longer exists. */
    fun forget(presetId: String)

    /**
     * Records that the connection state is no longer known — for example after
     * the app spent time in the background, where Android may have suspended the
     * network monitoring. This never starts, stops or contacts anything; it only
     * stops the UI from claiming a connection that has not been re-checked.
     */
    fun markStale(
        presetId: String,
        message: String,
        state: ModelLifecycleState = ModelLifecycleState.CHECKING,
    ) = Unit
}

/**
 * Shared lifecycle implementation.
 *
 * Subclasses supply only what is genuinely runtime-specific: how an endpoint is
 * discovered and the wording of the messages shown while waiting for the
 * runtime. Everything else — bounded retries with backoff, health gating,
 * cancellation, timeouts, "do not restart a healthy runtime" — lives here once.
 */
abstract class AbstractModelRunner(
    protected val policy: ModelConnectionPolicy,
    protected val healthChecker: ModelHealthChecker,
    protected val credentials: ModelCredentialResolver,
    protected val clock: () -> Long = System::currentTimeMillis,
    protected val logger: ForgeLogger = ForgeLoggers.create(
        level = LogLevel.WARN,
        baseFields = mapOf("component" to "model-runner"),
    ),
    /** Injected so tests can run the retry path without waiting. */
    protected val sleep: suspend (Long) -> Unit = { delay(it) },
) : ModelRunner {

    private val statuses = MutableStateFlow<Map<String, ModelRuntimeStatus>>(emptyMap())

    override fun statuses(): StateFlow<Map<String, ModelRuntimeStatus>> = statuses.asStateFlow()

    override fun status(presetId: String): ModelRuntimeStatus =
        statuses.value[presetId] ?: ModelRuntimeStatus.notConfigured(presetId, clock())

    override fun forget(presetId: String) {
        statuses.value = statuses.value - presetId
    }

    override fun markStale(presetId: String, message: String, state: ModelLifecycleState) {
        val current = statuses.value[presetId] ?: return
        setStatus(current.copy(state = state, message = message, updatedAtMillis = clock()))
    }

    // --- runner-specific behaviour -----------------------------------------

    /** Where the model endpoint is expected to come from for this runtime. */
    protected abstract suspend fun discover(preset: ModelPreset): DiscoveryOutcome

    /** Shown while the runtime has not published an endpoint yet. */
    protected open fun waitingMessage(preset: ModelPreset): String =
        "Waiting for the ${preset.providerType.displayName} endpoint"

    /** Shown when no endpoint appeared within the allowed attempts. */
    protected open fun notDetectedMessage(preset: ModelPreset): String =
        "No endpoint was detected for ${preset.displayName}."

    /** Shown after a successful stop; runtime-specific limits belong here. */
    protected open fun stoppedMessage(preset: ModelPreset): String = "Stopped"

    // --- lifecycle ---------------------------------------------------------

    override suspend fun start(preset: ModelPreset): RunnerOperationResult {
        reject(preset)?.let { return it }

        val current = status(preset.id)
        if (current.isUsable) {
            // A runtime that already answered a health check is never restarted.
            val health = health(preset, current.endpoint)
            if (health.status != ModelHealthStatus.UNHEALTHY) {
                return RunnerOperationResult(
                    status = current.copy(
                        message = health.detail,
                        detail = null,
                        updatedAtMillis = clock(),
                    ),
                    health = health,
                )
            }
        }

        return attemptLoop(
            preset = preset,
            busyState = ModelLifecycleState.STARTING,
            busyMessage = "Preparing the ${preset.providerType.displayName} runtime",
            maxAttempts = policy.maxStartAttempts,
        )
    }

    override suspend fun reconnect(preset: ModelPreset): RunnerOperationResult {
        reject(preset)?.let { return it }
        return attemptLoop(
            preset = preset,
            busyState = ModelLifecycleState.CHECKING,
            busyMessage = "Reconnecting to ${preset.displayName}",
            maxAttempts = policy.maxReconnectAttempts,
        )
    }

    override suspend fun stop(preset: ModelPreset): RunnerOperationResult {
        val current = status(preset.id)
        if (current.state == ModelLifecycleState.STOPPED || current.state == ModelLifecycleState.NOT_CONFIGURED) {
            return RunnerOperationResult(current)
        }
        setStatus(current.copy(state = ModelLifecycleState.STOPPING, message = "Disconnecting…"))
        val stopped = current.copy(
            state = ModelLifecycleState.STOPPED,
            message = stoppedMessage(preset),
            detail = null,
            endpoint = null,
            failure = ModelRuntimeFailure.NONE,
            attempt = 0,
            updatedAtMillis = clock(),
            awaitingRuntime = preset.providerType == ModelProviderType.GOOGLE_COLAB,
        )
        setStatus(stopped)
        return RunnerOperationResult(stopped)
    }

    override suspend fun healthCheck(preset: ModelPreset): ModelHealth {
        val current = status(preset.id)
        val endpoint = current.endpoint ?: when (val outcome = discover(preset)) {
            is DiscoveryOutcome.Found -> outcome.endpoint
            is DiscoveryOutcome.NotFound -> {
                val health = ModelHealth.unhealthy(outcome.reason)
                setStatus(unreachable(preset, health, outcome.invalidEndpoint))
                return health
            }
        }

        val health = health(preset, endpoint)
        setStatus(
            when (health.status) {
                ModelHealthStatus.HEALTHY -> current.copy(
                    state = ModelLifecycleState.ONLINE,
                    message = health.detail,
                    detail = null,
                    endpoint = endpoint,
                    failure = ModelRuntimeFailure.NONE,
                    updatedAtMillis = clock(),
                    awaitingRuntime = false,
                )

                ModelHealthStatus.DEGRADED -> current.copy(
                    state = ModelLifecycleState.DEGRADED,
                    message = health.detail,
                    detail = null,
                    endpoint = endpoint,
                    failure = ModelRuntimeFailure.MODEL_API_UNREACHABLE,
                    updatedAtMillis = clock(),
                    awaitingRuntime = false,
                )

                ModelHealthStatus.UNHEALTHY -> unreachable(preset, health, invalidEndpoint = false, endpoint = endpoint)
            },
        )
        return health
    }

    // --- internals ---------------------------------------------------------

    private suspend fun attemptLoop(
        preset: ModelPreset,
        busyState: ModelLifecycleState,
        busyMessage: String,
        maxAttempts: Int,
    ): RunnerOperationResult {
        setStatus(
            status(preset.id).copy(
                state = busyState,
                message = busyMessage,
                detail = null,
                failure = ModelRuntimeFailure.NONE,
                attempt = 0,
                updatedAtMillis = clock(),
                awaitingRuntime = false,
            ),
        )

        val attempts = runWithOperationTimeout(preset) {
            var lastReason: String? = null
            var invalidEndpoint = false

            for (attempt in 1..maxAttempts) {
                when (val outcome = discover(preset)) {
                    is DiscoveryOutcome.Found -> {
                        setStatus(
                            status(preset.id).copy(
                                state = ModelLifecycleState.CONNECTING,
                                message = "Verifying ${outcome.endpoint.host}",
                                endpoint = outcome.endpoint,
                                attempt = attempt,
                                updatedAtMillis = clock(),
                            ),
                        )
                        val health = health(preset, outcome.endpoint)
                        when (health.status) {
                            ModelHealthStatus.HEALTHY -> return@runWithOperationTimeout RunnerOperationResult(
                                status = status(preset.id).copy(
                                    state = ModelLifecycleState.ONLINE,
                                    message = health.detail,
                                    detail = null,
                                    endpoint = outcome.endpoint,
                                    failure = ModelRuntimeFailure.NONE,
                                    attempt = attempt,
                                    updatedAtMillis = clock(),
                                    awaitingRuntime = false,
                                ),
                                health = health,
                            )

                            ModelHealthStatus.DEGRADED -> return@runWithOperationTimeout RunnerOperationResult(
                                status = status(preset.id).copy(
                                    state = ModelLifecycleState.DEGRADED,
                                    message = health.detail,
                                    detail = null,
                                    endpoint = outcome.endpoint,
                                    failure = ModelRuntimeFailure.MODEL_API_UNREACHABLE,
                                    attempt = attempt,
                                    updatedAtMillis = clock(),
                                    awaitingRuntime = false,
                                ),
                                health = health,
                            )

                            ModelHealthStatus.UNHEALTHY -> {
                                lastReason = health.detail
                                invalidEndpoint = false
                            }
                        }
                    }

                    is DiscoveryOutcome.NotFound -> {
                        lastReason = outcome.reason
                        invalidEndpoint = outcome.invalidEndpoint
                    }
                }

                if (attempt == maxAttempts) break

                val reason = lastReason.orEmpty()
                setStatus(
                    status(preset.id).copy(
                        state = busyState,
                        message = if (invalidEndpoint && reason.isNotBlank()) reason else waitingMessage(preset),
                        detail = if (invalidEndpoint) null else reason.ifBlank { null },
                        failure = ModelRuntimeFailure.NONE,
                        attempt = attempt,
                        updatedAtMillis = clock(),
                        awaitingRuntime = !invalidEndpoint,
                    ),
                )
                sleep(policy.backoffMillis(attempt))
            }

            val reason = lastReason.orEmpty()
            val invalid = invalidEndpoint
            val failure = if (invalid) ModelRuntimeFailure.ENDPOINT_INVALID else ModelRuntimeFailure.RUNTIME_NOT_DETECTED
            RunnerOperationResult(
                status = status(preset.id).copy(
                    state = ModelLifecycleState.FAILED,
                    message = if (invalid && reason.isNotBlank()) reason else notDetectedMessage(preset),
                    detail = reason.ifBlank { null },
                    failure = failure,
                    attempt = maxAttempts,
                    updatedAtMillis = clock(),
                    awaitingRuntime = true,
                ),
                health = null,
            )
        }

        val result = attempts ?: RunnerOperationResult(
            status = status(preset.id).copy(
                state = ModelLifecycleState.FAILED,
                message = "Gave up after ${policy.operationTimeoutMillis / 1000}s",
                failure = ModelRuntimeFailure.TIMEOUT,
                updatedAtMillis = clock(),
                awaitingRuntime = true,
            ),
        )
        setStatus(result.status)
        return result
    }

    /**
     * Applies the overall operation timeout when a coroutine job is available.
     * Returns null when the timeout fired, so the caller can end in a terminal
     * state instead of leaving the UI spinning.
     */
    private suspend fun <T> runWithOperationTimeout(
        preset: ModelPreset,
        block: suspend () -> T,
    ): T? = try {
        if (coroutineContext[Job] != null) {
            withTimeout(policy.operationTimeoutMillis) { block() }
        } else {
            block()
        }
    } catch (timeout: TimeoutCancellationException) {
        // Logged without any preset secret; the preset only carries a reference.
        logger.warn(
            "Model operation timed out",
            mapOf("preset" to preset.id, "timeoutMillis" to policy.operationTimeoutMillis),
        )
        null
    } catch (cancelled: CancellationException) {
        setStatus(
            status(preset.id).copy(
                state = ModelLifecycleState.DISCONNECTED,
                message = "Cancelled",
                failure = ModelRuntimeFailure.CANCELLED,
                updatedAtMillis = clock(),
            ),
        )
        throw cancelled
    }

    /** Single health check, bounded by the health timeout. Never mutates status. */
    private suspend fun health(preset: ModelPreset, endpoint: ModelEndpoint?): ModelHealth {
        if (endpoint == null) return ModelHealth.unhealthy("No endpoint has been detected for this model yet")
        val credential = credentials.resolve(preset)
        return try {
            if (coroutineContext[Job] != null) {
                withTimeout(preset.health.timeoutMillis) { healthChecker.check(preset, endpoint, credential) }
            } else {
                healthChecker.check(preset, endpoint, credential)
            }
        } catch (timeout: TimeoutCancellationException) {
            ModelHealth.unhealthy("The model API did not answer within ${preset.health.timeoutMillis}ms")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            ModelHealth.unhealthy("The model endpoint could not be reached: ${error::class.simpleName}")
        }
    }

    private fun unreachable(
        preset: ModelPreset,
        health: ModelHealth,
        invalidEndpoint: Boolean,
        endpoint: ModelEndpoint? = null,
    ): ModelRuntimeStatus = status(preset.id).copy(
        state = ModelLifecycleState.DISCONNECTED,
        message = health.detail,
        detail = null,
        endpoint = endpoint,
        failure = if (invalidEndpoint) ModelRuntimeFailure.ENDPOINT_INVALID else ModelRuntimeFailure.MODEL_API_UNREACHABLE,
        updatedAtMillis = clock(),
        awaitingRuntime = preset.providerType == ModelProviderType.GOOGLE_COLAB,
    )

    private fun reject(preset: ModelPreset): RunnerOperationResult? {
        if (!preset.enabled) {
            return terminal(preset, "This model is disabled", ModelRuntimeFailure.DISABLED)
        }
        val errors = preset.validate()
        if (errors.isNotEmpty()) {
            return terminal(
                preset = preset,
                message = "The model configuration is incomplete",
                failure = ModelRuntimeFailure.INVALID_PRESET,
                detail = errors.joinToString("; "),
            )
        }
        if (!supports(preset)) {
            return terminal(
                preset = preset,
                message = "No runner handles ${preset.providerType.displayName} models",
                failure = ModelRuntimeFailure.NO_RUNNER,
            )
        }
        return null
    }

    private fun terminal(
        preset: ModelPreset,
        message: String,
        failure: ModelRuntimeFailure,
        detail: String? = null,
    ): RunnerOperationResult {
        val status = status(preset.id).copy(
            state = ModelLifecycleState.FAILED,
            message = message,
            detail = detail,
            failure = failure,
            updatedAtMillis = clock(),
        )
        setStatus(status)
        return RunnerOperationResult(status)
    }

    private fun setStatus(status: ModelRuntimeStatus) {
        statuses.value = statuses.value + (status.presetId to status)
    }
}
