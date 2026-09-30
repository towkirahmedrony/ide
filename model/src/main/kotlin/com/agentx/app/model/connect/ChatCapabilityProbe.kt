package com.agentx.app.model.connect

import com.agentx.app.model.DefaultModelGateway
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.ModelGateway
import com.agentx.app.model.ModelGenerationSettings
import com.agentx.app.model.ModelMessage
import com.agentx.app.model.ModelProvider
import com.agentx.app.model.ModelProviderError
import com.agentx.app.model.ModelProviderErrorCode
import com.agentx.app.model.ModelRequest
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
) {

    suspend fun verify(
        preset: ModelPreset,
        rootUrl: String,
        apiBasePath: String,
        modelId: String,
        credential: String?,
    ): ChatProbeResult {
        val config = ModelConfig(
            providerId = preset.apiProtocol.providerId,
            baseUrl = EndpointResolver.join(rootUrl, apiBasePath),
            model = modelId,
            apiKey = credential,
            stream = false,
            generation = ModelGenerationSettings(maxOutputTokens = 1, temperature = 0.0),
            timeoutMillis = timeoutMillis,
        )
        val problems = config.validate()
        if (problems.isNotEmpty()) {
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
            ChatProbeResult(ChatProbeStatus.OK, "Chat endpoint responded.")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ModelProviderError) {
            ChatProbeResult(
                status = ChatProbeStatus.FAILED,
                message = userMessage(error),
                httpStatus = error.httpStatus,
                kind = kindFor(error),
            )
        } catch (_: Throwable) {
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
        const val DEFAULT_TIMEOUT_MILLIS: Long = 12_000
    }
}
