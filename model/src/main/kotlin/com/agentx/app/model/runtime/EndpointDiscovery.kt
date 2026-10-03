package com.agentx.app.model.runtime

import com.agentx.app.model.preset.EndpointDiscoveryMode
import com.agentx.app.model.preset.EndpointValidation
import com.agentx.app.model.preset.ModelPreset
import com.agentx.app.model.preset.validateEndpoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Text the model runtime produced (notebook output, a tunnel log line, a console
 * message forwarded by the Model Runner browser). Endpoint discovery reads it;
 * the UI can display it so the user sees exactly what was captured.
 */
interface RuntimeOutputSource {
    fun snapshot(): List<String>

    val lines: StateFlow<List<String>>
}

class RuntimeOutputBuffer(private val maxLines: Int = MAX_LINES) : RuntimeOutputSource {

    private val buffer = MutableStateFlow<List<String>>(emptyList())

    override val lines: StateFlow<List<String>> = buffer.asStateFlow()

    override fun snapshot(): List<String> = buffer.value

    fun append(line: String) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return
        buffer.value = (buffer.value + trimmed).takeLast(maxLines)
    }

    fun appendAll(newLines: List<String>) {
        newLines.forEach(::append)
    }

    fun clear() {
        buffer.value = emptyList()
    }

    companion object {
        const val MAX_LINES: Int = 400
    }
}

/** What a discovery pass found. */
sealed interface DiscoveryOutcome {
    data class Found(val endpoint: ModelEndpoint) : DiscoveryOutcome

    /**
     * [invalidEndpoint] distinguishes "the runtime published something unusable"
     * from "the runtime has not published anything yet": the first is a
     * configuration problem, the second usually means the runtime is not running.
     */
    data class NotFound(
        val reason: String,
        val invalidEndpoint: Boolean = false,
    ) : DiscoveryOutcome
}

/** Port for finding the endpoint a model is served on. */
interface ModelEndpointDiscovery {
    suspend fun discover(preset: ModelPreset): DiscoveryOutcome
}

/**
 * Default discovery, driven by [ModelPreset.endpoint]. Nothing here is
 * tunnel- or vendor-specific: the sources are the configured URL, the runtime
 * output (through [TunnelProviders]), and the device's own loopback address.
 */
class DefaultModelEndpointDiscovery(
    private val tunnels: TunnelProviders = TunnelProviders(),
    private val output: RuntimeOutputSource = RuntimeOutputBuffer(),
) : ModelEndpointDiscovery {

    override suspend fun discover(preset: ModelPreset): DiscoveryOutcome = when (preset.endpoint.mode) {
        EndpointDiscoveryMode.CONFIGURED_ENDPOINT -> configured(preset)
        EndpointDiscoveryMode.RUNTIME_OUTPUT -> fromRuntimeOutput(preset)
        EndpointDiscoveryMode.DEVICE_LOCAL_PORT -> deviceLocal(preset)
    }

    private fun configured(preset: ModelPreset): DiscoveryOutcome {
        // A preset that says its endpoint is configured, but carries none, is a
        // configuration problem rather than a runtime that has not published yet:
        // reporting it as invalid surfaces the missing field instead of sending the
        // user looking for a server that was never involved.
        val raw = preset.endpoint.explicitUrl
            ?: return DiscoveryOutcome.NotFound(
                "No endpoint is configured for this model",
                invalidEndpoint = true,
            )
        return validate(raw, preset, EndpointSource.CONFIGURED)
    }

    private fun fromRuntimeOutput(preset: ModelPreset): DiscoveryOutcome =
        when (val detection = tunnels.detect(preset, output.snapshot())) {
            is TunnelDetection.Detected -> validate(detection.url, preset, EndpointSource.RUNTIME_OUTPUT)
            is TunnelDetection.NotFound -> DiscoveryOutcome.NotFound(detection.reason)
        }

    private fun deviceLocal(preset: ModelPreset): DiscoveryOutcome {
        val port = preset.serverPort
            ?: return DiscoveryOutcome.NotFound(
                "No server port is configured for this model",
                invalidEndpoint = true,
            )
        if (port !in 1..65_535) {
            return DiscoveryOutcome.NotFound("Server port $port is not a valid port", invalidEndpoint = true)
        }
        return validate("http://$LOOPBACK:$port", preset, EndpointSource.DEVICE_LOCAL)
    }

    private fun validate(raw: String, preset: ModelPreset, source: EndpointSource): DiscoveryOutcome =
        when (val result = validateEndpoint(raw, preset.providerType.requiresSecureEndpoint)) {
            is EndpointValidation.Valid -> DiscoveryOutcome.Found(ModelEndpoint(result.url, source))
            is EndpointValidation.Invalid -> DiscoveryOutcome.NotFound(result.reason, invalidEndpoint = true)
        }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
    }
}
