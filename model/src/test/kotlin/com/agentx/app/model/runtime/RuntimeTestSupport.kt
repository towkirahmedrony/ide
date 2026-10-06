package com.agentx.app.model.runtime

import com.agentx.app.core.logging.ForgeLogger
import com.agentx.app.core.logging.ForgeLoggers
import com.agentx.app.core.logging.LogLevel
import com.agentx.app.core.logging.LogRecord
import com.agentx.app.core.logging.LogSink
import com.agentx.app.model.connect.ModelSetupKind
import com.agentx.app.model.preset.CUSTOM_SETUP_KIND
import com.agentx.app.model.preset.ColabRuntimeConfig
import com.agentx.app.model.preset.EndpointConfig
import com.agentx.app.model.preset.EndpointDiscoveryMode
import com.agentx.app.model.preset.HealthCheckConfig
import com.agentx.app.model.preset.ModelApiProtocol
import com.agentx.app.model.preset.ModelPreset
import com.agentx.app.model.preset.ModelProviderType
import com.agentx.app.model.preset.TunnelConfig
import com.agentx.app.model.preset.TunnelType

/** A Colab preset shaped like something a user would save. No model is assumed. */
internal fun colabPreset(
    id: String = "qwen",
    name: String = "Qwen",
    model: String = "qwen2.5-coder-7b-instruct",
    endpointMode: EndpointDiscoveryMode = EndpointDiscoveryMode.RUNTIME_OUTPUT,
    explicitUrl: String? = null,
    tunnelType: TunnelType = TunnelType.CLOUDFLARE_QUICK,
    credentialRef: String? = null,
    requireModelInList: Boolean = true,
): ModelPreset = ModelPreset(
    id = id,
    displayName = name,
    providerType = ModelProviderType.GOOGLE_COLAB,
    modelIdentifier = model,
    credentialRef = credentialRef,
    serverPort = 8000,
    endpoint = EndpointConfig(mode = endpointMode, explicitUrl = explicitUrl),
    tunnel = TunnelConfig(type = tunnelType),
    health = HealthCheckConfig(requireModelInList = requireModelInList),
    colab = ColabRuntimeConfig(notebookUrl = "https://colab.research.google.com/drive/test-notebook"),
)

/**
 * A saved Gemini preset shaped exactly like one the connect flow persists: the
 * provider root as the endpoint, the API version as the base path, the bare model
 * id and a credential reference.
 *
 * The display name is deliberately unrelated to every other field, so a test can
 * prove that "geminj" is never read back as an endpoint, a model id or an API path.
 */
internal fun geminiPreset(
    id: String = "gemini-preset",
    name: String = "geminj",
    model: String = "gemini-3.5-flash-lite",
    endpoint: String? = "https://generativelanguage.googleapis.com",
    credentialRef: String? = "model-credential-7",
    requireModelInList: Boolean = true,
): ModelPreset = ModelPreset(
    id = id,
    displayName = name,
    providerType = ModelProviderType.REMOTE_OPENAI_COMPATIBLE,
    modelIdentifier = model,
    apiProtocol = ModelApiProtocol.GEMINI_NATIVE,
    apiBasePath = ModelApiProtocol.GEMINI_NATIVE.defaultApiBasePath,
    credentialRef = credentialRef,
    endpoint = EndpointConfig(EndpointDiscoveryMode.CONFIGURED_ENDPOINT, endpoint),
    tunnel = TunnelConfig(type = TunnelType.NONE),
    health = HealthCheckConfig(requireModelInList = requireModelInList),
    setupKind = ModelSetupKind.GEMINI.id,
)

/**
 * A saved Custom/Local preset shaped like one the connect flow persists: the user's
 * endpoint, the compatible surface, an opaque model id and — when the server needs
 * one — a credential reference.
 *
 * The id is deliberately the kind a local server reports (a repository path plus a
 * quantization), because it must survive every hop unchanged.
 */
internal fun customPreset(
    id: String = "custom-preset",
    name: String = "Devstral",
    model: String = "hf.co/unsloth/Devstral-Small-2-24B-Instruct-2512-GGUF:Q3_K_M",
    endpoint: String? = "https://armored-fantasy-stuffing.ngrok-free.dev",
    credentialRef: String? = null,
    requireModelInList: Boolean = true,
): ModelPreset = ModelPreset(
    id = id,
    displayName = name,
    providerType = ModelProviderType.REMOTE_OPENAI_COMPATIBLE,
    modelIdentifier = model,
    apiProtocol = ModelApiProtocol.OPENAI_COMPATIBLE,
    apiBasePath = "/v1",
    credentialRef = credentialRef,
    endpoint = EndpointConfig(EndpointDiscoveryMode.CONFIGURED_ENDPOINT, endpoint),
    tunnel = TunnelConfig(type = TunnelType.NONE),
    health = HealthCheckConfig(requireModelInList = requireModelInList),
    setupKind = CUSTOM_SETUP_KIND,
)

/**
 * A saved FreeLLMAPI preset: a hosted OpenAI-compatible gateway that serves Gemini,
 * Groq and other remote models behind one credential.
 *
 * It speaks the same wire protocol as [customPreset] but is a distinct provider
 * identity ([ModelProviderIds.FREELMAPI]) in the API execution domain, so a local
 * endpoint and a FreeLLMAPI gateway are never confused even though both are
 * "OpenAI-compatible".
 */
internal fun freeLlmApiPreset(
    id: String = "freellmapi-preset",
    name: String = "FreeLLMAPI",
    model: String = "gemini-3.5-flash",
    endpoint: String? = "https://freellmapi.example",
    credentialRef: String? = "model-credential-9",
    requireModelInList: Boolean = false,
): ModelPreset = ModelPreset(
    id = id,
    displayName = name,
    providerType = ModelProviderType.REMOTE_OPENAI_COMPATIBLE,
    modelIdentifier = model,
    apiProtocol = ModelApiProtocol.OPENAI_COMPATIBLE,
    apiBasePath = "/v1",
    credentialRef = credentialRef,
    endpoint = EndpointConfig(EndpointDiscoveryMode.CONFIGURED_ENDPOINT, endpoint),
    tunnel = TunnelConfig(type = TunnelType.NONE),
    health = HealthCheckConfig(requireModelInList = requireModelInList),
    setupKind = ModelSetupKind.FREELLMAPI.id,
)

/** Discovery stub with a scripted outcome and a call count. */
internal class FakeEndpointDiscovery(
    var outcome: (ModelPreset) -> DiscoveryOutcome = { DiscoveryOutcome.NotFound("nothing published yet") },
) : ModelEndpointDiscovery {

    val calls = mutableListOf<String>()

    override suspend fun discover(preset: ModelPreset): DiscoveryOutcome {
        calls += preset.id
        return outcome(preset)
    }
}

/** Health stub with a scripted result and a record of the endpoints it saw. */
internal class FakeHealthChecker(
    var result: (ModelPreset, ModelEndpoint) -> ModelHealth = { _, _ ->
        ModelHealth(ModelHealthStatus.HEALTHY, "ok")
    },
) : ModelHealthChecker {

    val checked = mutableListOf<ModelEndpoint>()

    override suspend fun check(
        preset: ModelPreset,
        endpoint: ModelEndpoint,
        credential: String?,
    ): ModelHealth {
        checked += endpoint
        return result(preset, endpoint)
    }
}

/** Tunnel stub, so discovery can be tested without Cloudflare specifics. */
internal class FakeTunnelProvider(
    override val id: String = "fake-tunnel",
    override val type: TunnelType = TunnelType.CLOUDFLARE_QUICK,
    var detectedUrl: String? = null,
) : TunnelProvider {

    var detections: Int = 0
    private set

    override fun detect(preset: ModelPreset, output: List<String>): TunnelDetection {
        detections++
        val url = detectedUrl ?: return TunnelDetection.NotFound("no tunnel endpoint in output")
        return validate(url)
    }

    override fun validate(url: String): TunnelDetection =
        if (url.startsWith("https://")) {
            TunnelDetection.Detected(url.trimEnd('/'), id)
        } else {
            TunnelDetection.NotFound("$url is not a https endpoint")
        }
}

/** Captures log records so tests can assert what was (and was not) written. */
internal class RecordingLogSink : LogSink {

    val records = mutableListOf<LogRecord>()

    override fun write(record: LogRecord) {
        records += record
    }

    /** True when [needle] appears in any message or field value. */
    fun contains(needle: String): Boolean = text().contains(needle)

    fun text(): String = records.joinToString("\n") { record ->
        buildString {
            append(record.message)
            record.fields.forEach { (key, value) -> append(" $key=").append(value) }
        }
    }
}

internal fun recordingLogger(sink: LogSink): ForgeLogger = ForgeLoggers.create(LogLevel.DEBUG, sink)

internal fun healthy(models: List<String> = emptyList(), detail: String = "Model API reachable"): ModelHealth =
    ModelHealth(ModelHealthStatus.HEALTHY, detail, models)

internal fun degraded(detail: String = "reachable but incomplete"): ModelHealth =
    ModelHealth(ModelHealthStatus.DEGRADED, detail)

internal fun unhealthy(detail: String = "not reachable"): ModelHealth =
    ModelHealth(ModelHealthStatus.UNHEALTHY, detail)

internal fun found(url: String = "https://unit-test-host.trycloudflare.com"): DiscoveryOutcome =
    DiscoveryOutcome.Found(ModelEndpoint(url, EndpointSource.RUNTIME_OUTPUT))

internal fun defaultProtocol(): ModelApiProtocol = ModelApiProtocol.OPENAI_COMPATIBLE
