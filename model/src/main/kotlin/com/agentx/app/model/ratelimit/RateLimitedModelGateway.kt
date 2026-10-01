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
 * permit is reconciled with the provider's real usage on success and released on
 * failure. A provider 429 is retried centrally, respecting `Retry-After`, under
 * the same admission control — providers never retry on their own.
 *
 * Local runtimes ([ModelProviderType.LOCAL_PHONE]) bypass rate limiting entirely
 * so a local Qwen model keeps working with no remote quota involved.
 */
class RateLimitedModelGateway(
    private val delegate: ModelGateway,
    private val rateLimits: RateLimitManager,
    private val tokenEstimator: TokenEstimator = HeuristicTokenEstimator(),
    private val backoff: RateLimitBackoff = RateLimitBackoff(),
    private val clock: RateLimitClock = SystemRateLimitClock(),
) : ModelGateway {

    override fun register(provider: ModelProvider) = delegate.register(provider)

    override fun registerOrReplace(provider: ModelProvider) = delegate.registerOrReplace(provider)

    override fun unregister(id: String): Boolean = delegate.unregister(id)

    override fun providers(): List<ModelProvider> = delegate.providers()

    override fun provider(id: String): ModelProvider? = delegate.provider(id)

    override fun resolve(request: ModelRequest): ModelProvider? = delegate.resolve(request)

    override fun capabilities(request: ModelRequest): ModelCapabilities = delegate.capabilities(request)

    override suspend fun complete(request: ModelRequest): ModelResponse =
        execute(request, allowRetry = { true }) { delegate.complete(request) }

    override suspend fun stream(
        request: ModelRequest,
        onEvent: (ModelStreamEvent) -> Unit,
    ): ModelResponse {
        // Once any event has been forwarded, a retry would duplicate output.
        var emitted = false
        val forwarding: (ModelStreamEvent) -> Unit = { event ->
            emitted = true
            onEvent(event)
        }
        return execute(request, allowRetry = { !emitted }) { delegate.stream(request, forwarding) }
    }

    private suspend fun execute(
        request: ModelRequest,
        allowRetry: () -> Boolean,
        block: suspend () -> ModelResponse,
    ): ModelResponse {
        val admission = admissionFor(request)
        var attempts = 0
        while (true) {
            val permit = rateLimits.acquire(admission)
            try {
                val response = block()
                rateLimits.complete(permit, response.usage)
                return response
            } catch (cancelled: CancellationException) {
                rateLimits.abandon(permit)
                throw cancelled
            } catch (error: ModelProviderError) {
                rateLimits.abandon(permit)
                if (error.code != ModelProviderErrorCode.RATE_LIMITED) throw error
                rateLimits.noteRateLimited(admission)
                attempts += 1
                if (!backoff.canRetry(attempts) || !allowRetry()) {
                    throw rateLimitExhausted(error, admission, attempts)
                }
                clock.sleep(backoff.delayMillis(attempts, error.retryAfterMillis))
            } catch (error: Throwable) {
                rateLimits.abandon(permit)
                throw error
            }
        }
    }

    private fun admissionFor(request: ModelRequest): RateLimitRequest = RateLimitRequest(
        providerId = request.config.providerId,
        modelId = request.model,
        accountId = request.config.metadata[ACCOUNT_METADATA_KEY]?.takeIf { it.isNotBlank() },
        estimatedInputTokens = tokenEstimator.estimateInputTokens(request).toLong(),
        estimatedOutputTokens = tokenEstimator.estimateOutputTokens(request).toLong(),
        rateLimited = isRemote(request.config),
    )

    /** A local, on-device runtime shares no remote quota and is exempt. */
    private fun isRemote(config: ModelConfig): Boolean {
        val providerType = config.metadata[PROVIDER_TYPE_METADATA_KEY] ?: return true
        return !providerType.equals(ModelProviderType.LOCAL_PHONE.name, ignoreCase = true)
    }

    private fun rateLimitExhausted(
        cause: ModelProviderError,
        request: RateLimitRequest,
        attempts: Int,
    ): ModelProviderError = ModelProviderError(
        code = ModelProviderErrorCode.RATE_LIMITED,
        message = "Rate limit for ${request.providerId}/${request.modelId} persisted after " +
            "$attempts attempt${if (attempts == 1) "" else "s"}",
        providerId = request.providerId,
        httpStatus = cause.httpStatus,
        providerErrorType = cause.providerErrorType,
        retryable = false,
        retryAfterMillis = cause.retryAfterMillis,
        details = mapOf("attempts" to attempts),
        cause = cause,
    )

    companion object {
        /** Set by the connection registry; only local presets are exempt. */
        const val PROVIDER_TYPE_METADATA_KEY: String = "providerType"

        /** Optional account discriminator for multi-account rate-limit scopes. */
        const val ACCOUNT_METADATA_KEY: String = "accountId"
    }
}
