package com.agentx.app.model.ratelimit

import com.agentx.app.model.ModelConfig
import com.agentx.app.model.ModelGateway
import com.agentx.app.model.ModelProvider
import com.agentx.app.model.ModelProviderError
import com.agentx.app.model.ModelProviderErrorCode
import com.agentx.app.model.ModelRequest
import com.agentx.app.model.ModelResponse
import com.agentx.app.model.ModelStreamEvent
import com.agentx.app.model.ModelCapabilities
import com.agentx.app.model.capability.isLocalRuntime
import com.agentx.app.model.preset.ModelProviderType
import kotlin.coroutines.cancellation.CancellationException

/**
 * The single choke point for model traffic:
 *
 * ```text
 * Agent → ModelGateway (this) → RateLimitManager → Provider → API
 * ```
 *
 * It delegates every registration/resolution call unchanged and wraps only
 * [complete] and [stream]. Each attempt acquires admission control first; the
 * reservation is reconciled with the provider's real usage on success and
 * released on failure.
 *
 * A provider 429 is recorded centrally and returned as a structured rate-limit
 * error. This phase does not retry and does not switch models.
 *
 * Local runtimes ([ModelProviderType.LOCAL_PHONE] / [isLocalRuntime]) bypass
 * rate limiting so a local model keeps working with no remote quota involved.
 */
class RateLimitedModelGateway(
    private val delegate: ModelGateway,
    private val rateLimits: RateLimitManager,
    private val tokenEstimator: TokenEstimator = HeuristicTokenEstimator(),
) : ModelGateway {

    override fun register(provider: ModelProvider) = delegate.register(provider)

    override fun registerOrReplace(provider: ModelProvider) = delegate.registerOrReplace(provider)

    override fun registerConnection(connectionId: String, provider: ModelProvider) =
        delegate.registerConnection(connectionId, provider)

    override fun unregister(id: String): Boolean = delegate.unregister(id)

    override fun providers(): List<ModelProvider> = delegate.providers()

    override fun provider(id: String): ModelProvider? = delegate.provider(id)

    override fun resolve(request: ModelRequest): ModelProvider? = delegate.resolve(request)

    override fun capabilities(request: ModelRequest): ModelCapabilities = delegate.capabilities(request)

    override suspend fun complete(request: ModelRequest): ModelResponse = execute(request) { delegate.complete(request) }

    override suspend fun stream(
        request: ModelRequest,
        onEvent: (ModelStreamEvent) -> Unit,
    ): ModelResponse = execute(request) { delegate.stream(request, onEvent) }

    private suspend fun execute(
        request: ModelRequest,
        block: suspend () -> ModelResponse,
    ): ModelResponse {
        val admission = admissionFor(request)
        when (val decision = rateLimits.canRequest(admission)) {
            is RateLimitDecision.Blocked -> throw decision.toError()
            RateLimitDecision.Allowed -> Unit
        }
        val reservation = rateLimits.reserve(admission)
        try {
            val response = block()
            rateLimits.complete(reservation, response.usage)
            return response
        } catch (cancelled: CancellationException) {
            rateLimits.abandon(reservation)
            throw cancelled
        } catch (error: ModelProviderError) {
            rateLimits.abandon(reservation)
            if (error.code != ModelProviderErrorCode.RATE_LIMITED) throw error
            val kind = rateLimitKindOf(error.message, error.providerErrorType)
            rateLimits.recordRateLimited(
                providerId = admission.providerId,
                modelId = admission.modelId,
                retryAfterMs = error.retryAfterMillis,
                kind = kind,
                accountId = admission.accountId,
            )
            throw structuredRateLimit(error, admission, kind)
        } catch (error: Throwable) {
            rateLimits.abandon(reservation)
            throw error
        }
    }

    private fun admissionFor(request: ModelRequest): RateLimitRequest = RateLimitRequest(
        providerId = request.config.providerId,
        modelId = request.model,
        accountId = quotaScope(request.config),
        estimatedInputTokens = tokenEstimator.estimateInputTokens(request),
        estimatedOutputTokens = tokenEstimator.estimateOutputTokens(request),
        rateLimited = isRemote(request.config),
    )

    /**
     * The quota scope this request's capacity is taken from.
     *
     * [quotaScopeOf] holds the rule, so admission here and profile registration
     * ([RateLimitProfileRegistrar]) can never disagree about which bucket a
     * connection's requests belong to. An explicit `accountId` in the
     * configuration's metadata wins, so a host that really does run several accounts
     * through one connection can say so.
     */
    private fun quotaScope(config: ModelConfig): String? =
        config.metadata[ACCOUNT_METADATA_KEY]?.takeIf { it.isNotBlank() }
            ?: quotaScopeOf(config.connectionId, config.providerId)

    private fun isRemote(config: ModelConfig): Boolean {
        if (config.isLocalRuntime()) return false
        val providerType = config.metadata[PROVIDER_TYPE_METADATA_KEY] ?: return true
        return !providerType.equals(ModelProviderType.LOCAL_PHONE.name, ignoreCase = true)
    }

    private fun structuredRateLimit(
        cause: ModelProviderError,
        request: RateLimitRequest,
        kind: RateLimitKind,
    ): ModelProviderError = ModelProviderError(
        code = ModelProviderErrorCode.RATE_LIMITED,
        message = cause.message ?: "Rate limit for ${request.providerId}/${request.modelId}",
        providerId = request.providerId,
        httpStatus = cause.httpStatus ?: 429,
        providerErrorType = cause.providerErrorType,
        retryable = false,
        retryAfterMillis = cause.retryAfterMillis,
        details = cause.details + mapOf(
            "modelId" to request.modelId,
            "scope" to kind.name,
            "retryAfterMs" to cause.retryAfterMillis,
        ),
        cause = cause,
    )

    companion object {
        const val PROVIDER_TYPE_METADATA_KEY: String = "providerType"
        const val ACCOUNT_METADATA_KEY: String = "accountId"
    }
}
