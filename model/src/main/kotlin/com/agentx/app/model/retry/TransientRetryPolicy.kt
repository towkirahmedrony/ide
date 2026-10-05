package com.agentx.app.model.retry

import com.agentx.app.model.ModelProviderError
import com.agentx.app.model.error.ProviderErrorClassifier
import com.agentx.app.model.ratelimit.RateLimitBackoff

/**
 * What the retry policy decided about one failed attempt.
 *
 * This is a decision, not a boolean, because "we did not retry" has three very
 * different causes and an operator needs to be able to tell them apart:
 * the failure was permanent, the budget ran out, or a response had already reached
 * the user. Only [RETRY_SAME_MODEL] starts another attempt.
 */
enum class RetryVerdict {
    /**
     * The same model, reached through the same connection identity, may be asked
     * again after a bounded backoff. Nothing about the request changes — retrying is
     * not fallback and must never quietly become one.
     */
    RETRY_SAME_MODEL,

    /** The failure is permanent; repeating it would produce the same answer. */
    NOT_RETRYABLE,

    /** The bounded attempt budget is spent. The failure is returned as-is. */
    ATTEMPTS_EXHAUSTED,

    /**
     * The attempt had already emitted meaningful model output before it failed.
     *
     * A partially emitted response cannot be restarted without either duplicating
     * what the user can already see or appending a second answer to a broken one, so
     * it is refused rather than guessed at.
     */
    OUTPUT_ALREADY_EMITTED,
}

/**
 * Bounded, deterministic retry for *transient* model failures.
 *
 * The scope is deliberately narrow, and it is the whole point of this type:
 *
 * - **Same model, same connection.** Retrying never changes [ModelConfig]. A
 *   different model is fallback, which stays governed by the configured fallback
 *   policy and is never reached from here.
 * - **Model requests only.** This is applied by the model gateway around provider
 *   calls. Tool execution, git and GitHub writes never pass through it, so a
 *   shell command with a side effect is never repeated by this layer.
 * - **Bounded.** At most [maximumAttempts] attempts in total, including the first.
 *   There is no loop that can outlive that count.
 * - **Cancellation-aware.** Every delay is a suspending wait, so cancelling the run
 *   stops the backoff and no further attempt starts.
 *
 * The delay comes from [RateLimitBackoff], the project's single bounded
 * exponential-backoff-with-jitter implementation, so retry pacing has one definition
 * rather than two.
 */
class TransientRetryPolicy(
    /** Total attempts including the first; the default is the project's existing bound. */
    val maximumAttempts: Int = RateLimitBackoff.DEFAULT_MAX_ATTEMPTS,
    private val backoff: RateLimitBackoff = RateLimitBackoff(maxAttempts = maximumAttempts),
) {
    init {
        require(maximumAttempts >= 1) { "maximumAttempts must be at least 1, was $maximumAttempts" }
    }

    /** True when this failure may be repeated against the same model. */
    fun isRetryable(error: ModelProviderError): Boolean =
        ProviderErrorClassifier.isTransient(error.code, error.httpStatus)

    /**
     * The decision for [error] after [attempt] attempts have already been made.
     *
     * [attempt] is 1-based: `1` means the first attempt just failed. [outputEmitted]
     * reports whether that attempt had already produced meaningful model output.
     *
     * Order matters for observability. A permanent failure is reported as permanent
     * even if output had been emitted, because that is the fact an operator needs;
     * "the budget ran out" and "a partial answer was already shown" describe the
     * retry layer's own limits and are only worth reporting for failures that would
     * otherwise have been retried.
     */
    fun verdict(error: ModelProviderError, attempt: Int, outputEmitted: Boolean): RetryVerdict = when {
        !isRetryable(error) -> RetryVerdict.NOT_RETRYABLE
        outputEmitted -> RetryVerdict.OUTPUT_ALREADY_EMITTED
        attempt >= maximumAttempts -> RetryVerdict.ATTEMPTS_EXHAUSTED
        else -> RetryVerdict.RETRY_SAME_MODEL
    }

    /**
     * How long to wait before the attempt that follows [attempt].
     *
     * A provider-supplied `Retry-After` wins when the failure carried one, capped so
     * a provider cannot make the app wait unboundedly; otherwise the delay grows
     * exponentially with jitter. [attempt] is 1-based, so `1` is the wait before the
     * first retry.
     */
    fun delayMillis(attempt: Int, retryAfterMillis: Long? = null): Long =
        backoff.delayMillis(attempt, retryAfterMillis)
}
