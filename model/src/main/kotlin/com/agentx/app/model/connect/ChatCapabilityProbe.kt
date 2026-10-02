package com.agentx.app.model.connect

import com.agentx.app.core.logging.ForgeLogger
import com.agentx.app.model.DefaultModelGateway
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.ModelGateway
import com.agentx.app.model.ModelGenerationSettings
import com.agentx.app.model.ModelMessage
import com.agentx.app.model.ModelProvider
import com.agentx.app.model.ModelProviderError
import com.agentx.app.model.ModelProviderErrorCode
import com.agentx.app.model.ModelRequest
import com.agentx.app.model.diagnostics.ApiOperation
import com.agentx.app.model.diagnostics.ApiTrace
import com.agentx.app.model.diagnostics.configuredFlag
import com.agentx.app.model.diagnostics.sanitizeForLog
import com.agentx.app.model.preset.ModelApiProtocol
import com.agentx.app.model.preset.ModelPreset
import com.agentx.app.model.provider.openai.OpenAiCompatibleProvider
import kotlin.coroutines.cancellation.CancellationException

enum class ChatProbeStatus { OK, FAILED }

data class ChatProbeResult(
    val status: ChatProbeStatus,
    val message: String,
    val httpStatus: Int? = null,
    val kind: DiscoveryFailureKind? = null,
) {
    val succeeded: Boolean get() = status == ChatProbeStatus.OK
}

/**
 * Verifies that a discovered endpoint can actually chat, using the existing
 * Model Gateway / OpenAI-compatible provider — never a second HTTP client.
 *
 * The request is tiny (`max_tokens = 1`) so connecting is cheap.
 */
class ChatCapabilityProbe(
    /**
     * Dedicated gateway used only for the probe. Defaults to a fresh in-memory
     * gateway so a live Main Agent connection is never replaced mid-request.
     * The provider still speaks through the same OpenAI-compatible stack.
     */
    private val gateway: ModelGateway = DefaultModelGateway(),
    private val providerFactory: (ModelApiProtocol) -> ModelProvider = { protocol ->
        OpenAiCompatibleProvider(
            id = protocol.providerId,
            chatPath = protocol.chatPath,
        )
    },
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    /** Structured Developer Log sink for the verification request; null disables it. */
    private val logger: ForgeLogger? = null,
) {

    suspend fun verify(
        preset: ModelPreset,
        rootUrl: String,
        apiBasePath: String,
        modelId: String,
        credential: String?,
    ): ChatProbeResult {
        // The provider identity the preset connects as: Gemini, Groq or a plain
        // OpenAI-compatible endpoint. It is what makes the verification log line
        // addressable next to the discovery it follows.
        val providerId = preset.providerId
        val baseUrl = EndpointResolver.join(rootUrl, apiBasePath)
        val trace = ApiTrace.create(logger, providerId, ApiOperation.CONNECTION)
        trace.stage(
            "START",
            "operation" to "connection-verification",
            "provider" to providerId,
            "preset" to preset.id.ifBlank { "-" },
            "endpoint" to diagnosticPath(baseUrl),
            "path" to diagnosticPath(baseUrl.trimEnd('/') + preset.apiProtocol.chatPath),
            "model" to modelId,
            "protocol" to preset.apiProtocol.name,
            "stream" to false,
            "toolCalling" to "NO",
            "hasApiKey" to configuredFlag(!credential.isNullOrBlank()),
            "maxOutputTokens" to 1,
            "timeoutMs" to timeoutMillis,
        )
        val config = ModelConfig(
            providerId = preset.apiProtocol.providerId,
            baseUrl = baseUrl,
            model = modelId,
            apiKey = credential,
            stream = false,
            generation = ModelGenerationSettings(maxOutputTokens = 1, temperature = 0.0),
            timeoutMillis = timeoutMillis,
        )
        val problems = config.validate()
        if (problems.isNotEmpty()) {
            trace.failure(
                "ERROR",
                "stage" to "config",
                "provider" to providerId,
                "model" to modelId,
                "kind" to DiscoveryFailureKind.MALFORMED.name,
                "message" to sanitizeForLog(problems.first()),
            )
            return ChatProbeResult(
                status = ChatProbeStatus.FAILED,
                message = problems.first(),
                kind = DiscoveryFailureKind.MALFORMED,
            )
        }

        val provider = providerFactory(preset.apiProtocol)
        val previous = gateway.provider(provider.id)
        gateway.registerOrReplace(provider)
        return try {
            gateway.complete(
                ModelRequest(
                    config = config,
                    messages = listOf(ModelMessage.user("ping")),
                ),
            )
            trace.stage(
                "COMPLETE",
                "outcome" to "ok",
                "provider" to providerId,
                "model" to modelId,
                "status" to "-",
            )
            ChatProbeResult(ChatProbeStatus.OK, "Chat endpoint responded.")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ModelProviderError) {
            val kind = kindFor(error)
            trace.failure(
                "ERROR",
                "stage" to "chat-probe",
                "provider" to providerId,
                "model" to modelId,
                "status" to (error.httpStatus ?: "-"),
                "kind" to kind.name,
                "code" to error.code.name,
                "retryable" to error.retryable,
                "message" to sanitizeForLog(error.message ?: ""),
            )
            ChatProbeResult(
                status = ChatProbeStatus.FAILED,
                message = userMessage(error),
                httpStatus = error.httpStatus,
                kind = kind,
            )
        } catch (error: Throwable) {
            trace.failure(
                "ERROR",
                "stage" to "chat-probe",
                "provider" to providerId,
                "model" to modelId,
                "kind" to DiscoveryFailureKind.UNKNOWN.name,
                "exception" to error.javaClass.name,
                "message" to sanitizeForLog(error.message ?: error.javaClass.name),
            )
            ChatProbeResult(
                status = ChatProbeStatus.FAILED,
                message = "The chat endpoint could not be verified.",
                kind = DiscoveryFailureKind.UNKNOWN,
            )
        } finally {
            if (previous != null) {
                gateway.registerOrReplace(previous)
            } else {
                gateway.unregister(provider.id)
            }
        }
    }

    private fun kindFor(error: ModelProviderError): DiscoveryFailureKind = when (error.code) {
        ModelProviderErrorCode.AUTHENTICATION_FAILED -> DiscoveryFailureKind.AUTHENTICATION_REQUIRED
        ModelProviderErrorCode.TIMEOUT -> DiscoveryFailureKind.TIMEOUT
        ModelProviderErrorCode.RATE_LIMITED -> DiscoveryFailureKind.RATE_LIMITED
        ModelProviderErrorCode.CONNECTION_FAILED, ModelProviderErrorCode.NETWORK_ERROR ->
            DiscoveryFailureKind.UNREACHABLE
        ModelProviderErrorCode.INVALID_RESPONSE, ModelProviderErrorCode.INVALID_REQUEST ->
            DiscoveryFailureKind.MALFORMED
        ModelProviderErrorCode.PROVIDER_ERROR ->
            if ((error.httpStatus ?: 0) == 404) DiscoveryFailureKind.NOT_FOUND else DiscoveryFailureKind.SERVER_ERROR
        else -> DiscoveryFailureKind.UNKNOWN
    }

    private fun userMessage(error: ModelProviderError): String {
        val status = error.httpStatus
        return when {
            error.code == ModelProviderErrorCode.AUTHENTICATION_FAILED ->
                "The server is reachable, but authentication is required."
            error.code == ModelProviderErrorCode.TIMEOUT ->
                "The chat endpoint did not respond in time."
            error.code == ModelProviderErrorCode.RATE_LIMITED ->
                "The model endpoint is rate-limiting requests. Try again shortly."
            error.code == ModelProviderErrorCode.CONNECTION_FAILED ->
                "Could not connect to the chat endpoint."
            status == 404 ->
                "The chat endpoint was not found. Check Advanced settings."
            status != null && status in 500..599 ->
                "The chat endpoint returned HTTP $status."
            else -> "The chat endpoint could not be verified."
        }
    }

    companion object {
        /**
         * The probe asks for a single token, but the *first* chat request is what
         * makes a local runtime load the model into memory. Depending on the
         * runtime and the model size that takes far longer than generating from a
         * warm one, so this bounds the load rather than the reply.
         */
        const val DEFAULT_TIMEOUT_MILLIS: Long = 60_000
    }
}
