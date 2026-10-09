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
import com.agentx.app.model.connect.diagnosticPath
import com.agentx.app.model.manager.DefaultModelProviderFactory
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
 * Model Gateway and the provider the preset's protocol selects — never a second
 * HTTP client and never a second endpoint construction.
 *
 * Because the provider comes from the same factory the runtime uses, verification
 * and normal agent requests cannot diverge: a Gemini connection is verified with
 * a native `models/<model>:generateContent` call, an OpenAI-compatible one with
 * `<base>/chat/completions`, exactly as its later completions will be.
 *
 * The request is tiny (one output token) so connecting is cheap.
 */
class ChatCapabilityProbe(
    /**
     * Dedicated gateway used only for the probe. Defaults to a fresh in-memory
     * gateway so a live Main Agent connection is never replaced mid-request.
     * The provider still speaks through the same OpenAI-compatible stack.
     */
    private val gateway: ModelGateway = DefaultModelGateway(),
    private val providerFactory: (ModelPreset) -> ModelProvider = { preset ->
        DefaultModelProviderFactory().create(preset)
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
        // The provider instance, registered under the preset's connection identity
        // by the default factory, so two connections of one family never collide.
        val provider = providerFactory(preset)
        val baseUrl = EndpointResolver.join(rootUrl, apiBasePath)
        val trace = ApiTrace.create(logger, providerId, ApiOperation.CONNECTION)
        trace.stage(
            "START",
            "operation" to "connection-verification",
            "provider" to providerId,
            "preset" to preset.id.ifBlank { "-" },
            "endpoint" to diagnosticPath(baseUrl),
            "path" to diagnosticPath(
                baseUrl.trimEnd('/') + preset.apiProtocol.chatPathFor(modelId),
            ),
            "model" to modelId,
            "protocol" to preset.apiProtocol.name,
            "stream" to false,
            "toolCalling" to "NO",
            "hasApiKey" to configuredFlag(!credential.isNullOrBlank()),
            "requestHeaders" to preset.requestHeaders.keys.joinToString(",").ifBlank { "-" },
            "maxOutputTokens" to 1,
            "timeoutMs" to timeoutMillis,
        )
        val config = ModelConfig(
            // The provider identity the runtime connection uses, so the probe and a
            // real request resolve the same provider instance.
            providerId = preset.providerId,
            // The connection identity the provider is registered under (the preset's
            // connection id from the default factory), so the probe resolves the
            // exact connection it just registered — and never another connection
            // that shares the provider family.
            connectionId = provider.id,
            baseUrl = baseUrl,
            model = modelId,
            apiKey = credential,
            stream = false,
            generation = ModelGenerationSettings(maxOutputTokens = 1, temperature = 0.0),
            timeoutMillis = timeoutMillis,
            // The same connection headers the discovery request carried: a tunnel
            // that refuses an unflagged client must not pass discovery and then
            // reject the verification.
            headers = preset.requestHeaders,
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
                // The endpoint's own machine-readable error type — `empty_completion`
                // from a gateway relaying an upstream failure, Gemini's
                // `RESOURCE_EXHAUSTED`, an OpenAI-compatible `invalid_request_error` —
                // next to [code], which is AgentX's own classification of it. The two
                // together are what separate an upstream failure the endpoint reported
                // from a fault in this client, and this is the same field the provider
                // layer already logs for a completion. Redacted like every other
                // free-text value: it is provider-supplied text, so it is not trusted
                // to be free of secrets.
                "providerErrorType" to (error.providerErrorType?.let(::sanitizeForLog) ?: "-"),
                "message" to sanitizeForLog(error.message ?: ""),
            )
            ChatProbeResult(
                status = ChatProbeStatus.FAILED,
                message = userMessage(error, hasCredential = !credential.isNullOrBlank()),
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
        // A probe the server refused is a credential/access problem whether it said
        // 401 (rejected) or 403 (accepted but not permitted): the setup is what has to
        // change, so both lead the user to the same place.
        ModelProviderErrorCode.AUTHENTICATION_FAILED,
        ModelProviderErrorCode.AUTHORIZATION_FAILED,
        ModelProviderErrorCode.PERMISSION_DENIED,
        -> DiscoveryFailureKind.AUTHENTICATION_REQUIRED

        ModelProviderErrorCode.TIMEOUT -> DiscoveryFailureKind.TIMEOUT
        ModelProviderErrorCode.RATE_LIMITED, ModelProviderErrorCode.QUOTA_EXHAUSTED ->
            DiscoveryFailureKind.RATE_LIMITED
        ModelProviderErrorCode.CONNECTION_FAILED, ModelProviderErrorCode.NETWORK_ERROR ->
            DiscoveryFailureKind.UNREACHABLE
        ModelProviderErrorCode.MODEL_NOT_FOUND -> DiscoveryFailureKind.NOT_FOUND
        ModelProviderErrorCode.SERVER_ERROR, ModelProviderErrorCode.SERVICE_UNAVAILABLE ->
            DiscoveryFailureKind.SERVER_ERROR
        ModelProviderErrorCode.INVALID_RESPONSE, ModelProviderErrorCode.INVALID_REQUEST ->
            DiscoveryFailureKind.MALFORMED
        ModelProviderErrorCode.PROVIDER_ERROR ->
            if ((error.httpStatus ?: 0) == 404) DiscoveryFailureKind.NOT_FOUND else DiscoveryFailureKind.SERVER_ERROR
        else -> DiscoveryFailureKind.UNKNOWN
    }

    private fun userMessage(error: ModelProviderError, hasCredential: Boolean): String {
        val status = error.httpStatus
        return when {
            // 401/403 means the server was reached and said no. Which half of the
            // handshake failed decides whether the UI must ask for a key or point
            // at the one it already sent.
            error.code == ModelProviderErrorCode.AUTHENTICATION_FAILED ->
                if (hasCredential) {
                    "The server is reachable, but authentication was rejected. " +
                        "Check the API key for this endpoint."
                } else {
                    "The server is reachable, but it requires authentication. " +
                        "Add the API key for this endpoint."
                }
            // The credential was accepted but the account may not use this endpoint or
            // model. Telling the user to check the key again would send them to fix
            // something that is not broken.
            error.code == ModelProviderErrorCode.AUTHORIZATION_FAILED ||
                error.code == ModelProviderErrorCode.PERMISSION_DENIED ->
                "The server is reachable, but this credential is not permitted to use " +
                    "this endpoint or model. Check the account's access."
            error.code == ModelProviderErrorCode.QUOTA_EXHAUSTED ->
                "The model endpoint reports that this account's quota is exhausted."
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
