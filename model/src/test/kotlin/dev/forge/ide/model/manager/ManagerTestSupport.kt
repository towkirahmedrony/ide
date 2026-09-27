package dev.forge.ide.model.manager

import dev.forge.ide.model.ModelCapabilities
import dev.forge.ide.model.ModelMessage
import dev.forge.ide.model.ModelProvider
import dev.forge.ide.model.ModelRequest
import dev.forge.ide.model.ModelResponse
import dev.forge.ide.model.ModelStreamEvent
import dev.forge.ide.model.preset.ModelPreset
import dev.forge.ide.model.preset.ModelProviderType
import dev.forge.ide.model.runtime.ModelEndpoint
import dev.forge.ide.model.runtime.ModelHealth
import dev.forge.ide.model.runtime.ModelHealthStatus
import dev.forge.ide.model.runtime.ModelLifecycleState
import dev.forge.ide.model.runtime.ModelRunner
import dev.forge.ide.model.runtime.ModelRuntimeFailure
import dev.forge.ide.model.runtime.ModelRuntimeStatus
import dev.forge.ide.model.runtime.RunnerOperationResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal const val FAKE_ENDPOINT_URL: String = "https://unit-test-runner.trycloudflare.com"

internal fun endpoint(url: String = FAKE_ENDPOINT_URL): ModelEndpoint =
    ModelEndpoint(url, dev.forge.ide.model.runtime.EndpointSource.RUNTIME_OUTPUT)

internal fun onlineResult(
    preset: ModelPreset,
    url: String = FAKE_ENDPOINT_URL,
    message: String = "Model API reachable",
): RunnerOperationResult = RunnerOperationResult(
    status = ModelRuntimeStatus(
        presetId = preset.id,
        state = ModelLifecycleState.ONLINE,
        message = message,
        endpoint = endpoint(url),
    ),
    health = ModelHealth(ModelHealthStatus.HEALTHY, message),
)

internal fun failedResult(
    preset: ModelPreset,
    message: String = "Model runtime stopped.",
    failure: ModelRuntimeFailure = ModelRuntimeFailure.RUNTIME_NOT_DETECTED,
): RunnerOperationResult = RunnerOperationResult(
    status = ModelRuntimeStatus(
        presetId = preset.id,
        state = ModelLifecycleState.FAILED,
        message = message,
        failure = failure,
        awaitingRuntime = true,
    ),
)

internal fun stoppedResult(preset: ModelPreset): RunnerOperationResult = RunnerOperationResult(
    status = ModelRuntimeStatus(
        presetId = preset.id,
        state = ModelLifecycleState.STOPPED,
        message = "Stopped",
    ),
)

/**
 * Scripted runner. The manager's own responsibilities — selection, gateway
 * binding, credential handling, status bookkeeping — are what the manager tests
 * assert, so the runner is a stub here.
 */
internal class FakeModelRunner(
    override val id: String = "fake",
    override val supportedProviderTypes: Set<ModelProviderType> = ModelProviderType.entries.toSet(),
    /** Whether a health check should update the published status. */
    var reflectHealth: Boolean = true,
) : ModelRunner {

    private val mutableStatuses = MutableStateFlow<Map<String, ModelRuntimeStatus>>(emptyMap())

    var startCalls: Int = 0
        private set

    var stopCalls: Int = 0
        private set

    var reconnectCalls: Int = 0
        private set

    var healthCalls: Int = 0
        private set

    var lastStarted: ModelPreset? = null
        private set

    var onStart: (ModelPreset) -> RunnerOperationResult = { onlineResult(it) }

    var onReconnect: (ModelPreset) -> RunnerOperationResult = { onlineResult(it) }

    var onStop: (ModelPreset) -> RunnerOperationResult = { stoppedResult(it) }

    var onHealth: (ModelPreset) -> ModelHealth = { ModelHealth(ModelHealthStatus.HEALTHY, "ok") }

    override fun status(presetId: String): ModelRuntimeStatus =
        mutableStatuses.value[presetId] ?: ModelRuntimeStatus.notConfigured(presetId)

    override fun statuses(): StateFlow<Map<String, ModelRuntimeStatus>> = mutableStatuses.asStateFlow()

    override suspend fun start(preset: ModelPreset): RunnerOperationResult {
        startCalls++
        lastStarted = preset
        return publish(onStart(preset))
    }

    override suspend fun reconnect(preset: ModelPreset): RunnerOperationResult {
        reconnectCalls++
        return publish(onReconnect(preset))
    }

    override suspend fun stop(preset: ModelPreset): RunnerOperationResult {
        stopCalls++
        return publish(onStop(preset))
    }

    override suspend fun healthCheck(preset: ModelPreset): ModelHealth {
        healthCalls++
        val health = onHealth(preset)
        if (reflectHealth) {
            val current = status(preset.id)
            publish(
                if (health.isReachable) {
                    RunnerOperationResult(
                        current.copy(
                            state = if (health.status == ModelHealthStatus.HEALTHY) {
                                ModelLifecycleState.ONLINE
                            } else {
                                ModelLifecycleState.DEGRADED
                            },
                            message = health.detail,
                            endpoint = current.endpoint ?: endpoint(),
                        ),
                    )
                } else {
                    RunnerOperationResult(
                        current.copy(
                            state = ModelLifecycleState.DISCONNECTED,
                            message = health.detail,
                            failure = ModelRuntimeFailure.MODEL_API_UNREACHABLE,
                        ),
                    )
                },
            )
        }
        return health
    }

    override fun forget(presetId: String) {
        mutableStatuses.value = mutableStatuses.value - presetId
    }

    override fun markStale(presetId: String, message: String, state: ModelLifecycleState) {
        val current = mutableStatuses.value[presetId] ?: return
        publish(current.copy(state = state, message = message))
    }

    fun publish(status: ModelRuntimeStatus) {
        mutableStatuses.value = mutableStatuses.value + (status.presetId to status)
    }

    private fun publish(result: RunnerOperationResult): RunnerOperationResult {
        publish(result.status)
        return result
    }
}

/** Provider stub that records exactly what the gateway asked it to send. */
internal class RecordingModelProvider(
    override val id: String = "openai-compatible",
    var content: String = "ok",
) : ModelProvider {

    val requests = mutableListOf<ModelRequest>()

    override fun capabilities(modelId: String): ModelCapabilities =
        ModelCapabilities(streaming = true, toolCalling = true)

    override suspend fun complete(request: ModelRequest): ModelResponse {
        requests += request
        return ModelResponse(model = request.model, providerId = id, content = content)
    }

    override suspend fun stream(
        request: ModelRequest,
        onEvent: (ModelStreamEvent) -> Unit,
    ): ModelResponse {
        val response = complete(request)
        onEvent(ModelStreamEvent.Started(request.model, id))
        onEvent(ModelStreamEvent.TextDelta(response.content))
        return response
    }
}

internal fun userRequest(providerId: String = "openai-compatible"): ModelRequest = ModelRequest(
    config = dev.forge.ide.model.ModelConfig(
        providerId = providerId,
        baseUrl = "http://configured-by-manager/v1",
        model = "configured-by-manager",
    ),
    messages = listOf(ModelMessage.user("hello")),
)
