package com.agentx.app.model.ratelimit

import com.agentx.app.core.logging.ForgeLogger
import com.agentx.app.core.logging.ForgeLoggers
import com.agentx.app.core.logging.LogLevel
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
import com.agentx.app.model.retry.RetryVerdict
import com.agentx.app.model.retry.TransientRetryPolicy
import java.util.concurrent.atomic.AtomicBoolean
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
 * error. The rate limit itself is not retried here: [RateLimitManager] has just
 * recorded the cooldown the provider asked for, so a retry would either be refused
 * by admission control or would ignore it. Recovery from a 429 is the configured
 * fallback, unchanged from P1-3.
 *
 * ## Retry, and how it stays separate from fallback
 *
 * A failure that [TransientRetryPolicy] calls retryable is repeated **at most**
 * `maximumAttempts` times, on the *same* connection identity and the *same* model —
 * switching models is fallback, which this class never does. Every attempt —
 * including the first — re-enters admission control, so a retry cannot bypass the
 * quota manager and cannot spend capacity the manager says should not be spent.
 *
 * Retry sits *below* the fallback layer on purpose:
 *
 * ```text
 * request → attempt(s) with bounded transient retry → on exhaustion, throw
 *         → ModelFallback decides whether a configured candidate may run
 * ```
 *
 * so "retries exhausted, now fall back" is the only order that can happen, and a
 * fallback is never started before the retry budget is spent.
 *
 * A streamed attempt that has already emitted output is never retried: the wait is
 * a suspending [delay], so cancelling the run stops the backoff and no further
 * attempt starts.
 *
 * Local runtimes ([ModelProviderType.LOCAL_PHONE] / [isLocalRuntime]) bypass
 * rate limiting so a local model keeps working with no remote quota involved.
 */
class RateLimitedModelGateway(
    private val delegate: ModelGateway,
    private val rateLimits: RateLimitManager,
    private val tokenEstimator: TokenEstimator = HeuristicTokenEstimator(),
    /**
     * The bounded transient-retry policy. Retrying never changes the model or the
     * connection; see the class documentation for why that separation matters.
     */
    private val retry: TransientRetryPolicy = TransientRetryPolicy(),
    /**
     * How a backoff is waited out.
     *
     * This is the project's existing cancellable wait ([RateLimitClock.sleep]), not a
     * second mechanism: it suspends rather than blocking a thread, cancelling the run
     * stops a pending wait immediately, and tests supply a deterministic clock so
     * nothing actually sleeps.
     */
    private val wait: RateLimitClock = SystemRateLimitClock(),
    private val logger: ForgeLogger = ForgeLoggers.create(
        LogLevel.INFO,
        baseFields = mapOf("layer" to "model", "component" to "modelGateway"),
    ),
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

    override suspend fun complete(request: ModelRequest): ModelResponse =
        execute(request, outputEmitted = { false }) { delegate.complete(request) }

    override suspend fun stream(
        request: ModelRequest,
        onEvent: (ModelStreamEvent) -> Unit,
    ): ModelResponse {
        // Whether *this* attempt has already put model output on the wire. Once it
        // has, restarting the request cannot be done safely: the user may already be
        // reading the partial answer, and appending a second attempt to it would
        // present two answers as one. Such an attempt is failed instead of retried.
        val emitted = AtomicBoolean(false)
        val guarded: (ModelStreamEvent) -> Unit = { event ->
            if (isMeaningfulOutput(event)) emitted.set(true)
            onEvent(event)
        }
        return execute(request, outputEmitted = { emitted.get() }) { delegate.stream(request, guarded) }
    }

    /**
     * One logical model request, with the bounded transient-retry loop around it.
     *
     * [outputEmitted] reports whether the attempt that just failed had already
     * produced user-visible model output.
     */
    private suspend fun execute(
        request: ModelRequest,
        outputEmitted: () -> Boolean,
        block: suspend () -> ModelResponse,
    ): ModelResponse {
        var attempt = 0
        while (true) {
            attempt += 1
            try {
                val response = admitAndCall(request, block)
                if (attempt > 1) {
                    logger.info(
                        RETRY_SUCCEEDED,
                        attemptFields(request, attempts = attempt, outcome = RetryVerdict.RETRY_SAME_MODEL.name),
                    )
                }
                return response
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: ModelProviderError) {
                val verdict = retry.verdict(error, attempt, outputEmitted())
                if (verdict != RetryVerdict.RETRY_SAME_MODEL) {
                    // A failure the policy would have retried but could not is worth
                    // stating: it is the difference between "this was permanent" and
                    // "we stopped because the budget or the stream said so".
                    if (retry.isRetryable(error)) {
                        logger.info(
                            RETRY_STOPPED,
                            attemptFields(request, attempts = attempt, outcome = verdict.name) +
                                failureFields(error) +
                                mapOf(
                                    "maxAttempts" to retry.maximumAttempts,
                                    "retryAfterMillis" to error.retryAfterMillis,
                                ),
                        )
                    }
                    throw finalize(error, attempt, verdict)
                }
                val waitMillis = retry.delayMillis(attempt, error.retryAfterMillis)
                logger.info(
                    RETRY_SCHEDULED,
                    attemptFields(request, attempts = attempt, outcome = verdict.name) +
                        failureFields(error) +
                        mapOf(
                            "nextAttempt" to attempt + 1,
                            "maxAttempts" to retry.maximumAttempts,
                            "backoffMillis" to waitMillis,
                            "retryAfterMillis" to error.retryAfterMillis,
                        ),
                )
                wait.sleep(waitMillis)
            }
        }
    }

    /**
     * One attempt: admission control, reservation, provider call, reconciliation.
     *
     * The admission check is here rather than outside the loop so *every* attempt —
     * a retry included — is admitted on its own merits. A retry therefore cannot
     * bypass the quota manager, cannot ride on the previous attempt's reservation,
     * and is refused outright when the manager says the scope is blocked.
     */
    private suspend fun admitAndCall(
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

    /**
     * The failure the caller finally sees, annotated with what the retry layer did.
     *
     * A failure that went through retries is annotated, and so is one the policy would
     * have retried but was not allowed to — "the retry budget ran out", "a partial
     * answer had already been shown" and "this was permanent" are different outcomes
     * and the caller should not have to infer which happened. A plain permanent
     * failure on the first attempt carries nothing, so the common case stays quiet.
     */
    private fun finalize(error: ModelProviderError, attempts: Int, verdict: RetryVerdict): ModelProviderError {
        if (attempts <= 1 && verdict == RetryVerdict.NOT_RETRYABLE) return error
        return ModelProviderError(
            code = error.code,
            message = error.message ?: "Model request failed after $attempts attempts",
            providerId = error.providerId,
            httpStatus = error.httpStatus,
            providerErrorType = error.providerErrorType,
            retryable = error.retryable,
            retryAfterMillis = error.retryAfterMillis,
            details = error.details + mapOf(
                "attempts" to attempts,
                "maxAttempts" to retry.maximumAttempts,
                "retryOutcome" to verdict.name,
            ),
            cause = error,
        )
    }

    /** Whether an event is model output the user could already be reading. */
    private fun isMeaningfulOutput(event: ModelStreamEvent): Boolean = when (event) {
        is ModelStreamEvent.TextDelta -> event.text.isNotBlank()
        is ModelStreamEvent.ToolCallDelta -> true
        else -> false
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
    private fun quotaScope(config: ModelConfig): String? = config.quotaScope()

    private fun isRemote(config: ModelConfig): Boolean {
        if (config.isLocalRuntime()) return false
        val providerType = config.metadata[PROVIDER_TYPE_METADATA_KEY] ?: return true
        return !providerType.equals(ModelProviderType.LOCAL_PHONE.name, ignoreCase = true)
    }

    /**
     * Attempt accounting for the log stream. No message text, no request body and no
     * credential is included, so the retry trail stays safe to hand to a developer
     * log: `attempt 1 → TIMEOUT → backoff 500ms → attempt 2 → SERVED`.
     */
    private fun attemptFields(
        request: ModelRequest,
        attempts: Int,
        outcome: String,
    ): Map<String, Any?> = mapOf(
        "providerId" to request.config.providerId,
        "model" to request.model,
        "connectionId" to request.config.connectionId,
        "attempt" to attempts,
        "maxAttempts" to retry.maximumAttempts,
        "outcome" to outcome,
    )

    /** The classification of a failure, without its free-text message. */
    private fun failureFields(error: ModelProviderError): Map<String, Any?> = mapOf(
        "errorCategory" to error.code.name,
        "httpStatus" to error.httpStatus,
        "providerErrorType" to error.providerErrorType,
    )

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

        /** A transient failure will be repeated after a bounded backoff. */
        const val RETRY_SCHEDULED: String = "Model request retry scheduled"

        /** A retried attempt was answered. */
        const val RETRY_SUCCEEDED: String = "Model request retry succeeded"

        /** The retry budget ran out, or a later attempt was permanent. */
        const val RETRY_STOPPED: String = "Model request retry stopped"

        // The account discriminator key now lives with the scope rule itself
        // ([ACCOUNT_METADATA_KEY]) so evaluation and enforcement cannot drift apart.
    }
}
