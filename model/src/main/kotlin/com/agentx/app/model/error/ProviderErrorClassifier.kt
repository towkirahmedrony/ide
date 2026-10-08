package com.agentx.app.model.error

import com.agentx.app.model.ModelProviderErrorCode

/**
 * The single place that turns a provider response into a [ModelProviderErrorCode].
 *
 * Before this existed, each provider carried its own small `when (status)` block,
 * so the same HTTP status could mean different things depending on which provider
 * answered and a status neither block named silently became `PROVIDER_ERROR`. Two
 * providers drifting apart is exactly how "many errors collapse into UNKNOWN"
 * happens, so the rule lives here once and both providers call it.
 *
 * What it reads, in order of reliability:
 * - the provider's own machine-readable status/type (`RESOURCE_EXHAUSTED`,
 *   `PERMISSION_DENIED`, `insufficient_quota`) when the body carried one;
 * - otherwise the HTTP status.
 *
 * What it never does: inspect, keep or echo a credential, a request body or an
 * authorization header. Only the error's own type string and message are read, and
 * those are already the sanitized values the provider extracted.
 */
object ProviderErrorClassifier {

    /**
     * Client-fault statuses that mean "this request is wrong", not "the provider is
     * wrong": they are repeated identically by any model, so they are permanent.
     */
    private val INVALID_REQUEST_STATUSES = setOf(400, 405, 406, 409, 411, 413, 415, 422, 428)

    /** Statuses that mean the model or route the caller named does not exist. */
    private val NOT_FOUND_STATUSES = setOf(404, 410)

    /**
     * Classifies one provider HTTP failure.
     *
     * [providerErrorType] is the provider's own error type/status string when the
     * body supplied one; it is consulted before the bare status because it is more
     * specific (a 429 that says `insufficient_quota` is an exhausted balance, not a
     * rate to wait out).
     */
    fun forHttpStatus(
        status: Int,
        providerErrorType: String? = null,
        message: String? = null,
    ): ModelProviderErrorCode {
        providerNativeCode(providerErrorType)?.let { return it }
        return when {
            status == 401 -> ModelProviderErrorCode.AUTHENTICATION_FAILED
            status == 403 -> permissionOrAuthorization(providerErrorType, message)
            status in NOT_FOUND_STATUSES -> ModelProviderErrorCode.MODEL_NOT_FOUND
            status == 408 || status == 504 -> ModelProviderErrorCode.TIMEOUT
            status == 429 -> rateLimitedOrQuota(providerErrorType, message)
            status == 503 -> ModelProviderErrorCode.SERVICE_UNAVAILABLE
            status in 500..599 -> ModelProviderErrorCode.SERVER_ERROR
            status in INVALID_REQUEST_STATUSES -> ModelProviderErrorCode.INVALID_REQUEST
            else -> ModelProviderErrorCode.PROVIDER_ERROR
        }
    }

    /**
     * Classifies a failure a provider reported *inside* a response rather than as an
     * HTTP status: an OpenAI-compatible event stream may carry `{"error":{…}}` in
     * place of a chunk, because the status line was sent long before the failure.
     *
     * There is no status to read, so only the rules that do not depend on one apply:
     * the provider's own machine-readable type first, then the wording it used for a
     * spent balance or a short-term rate limit. Anything else stays [PROVIDER_ERROR],
     * the same category a status-less failure already gets, rather than being guessed
     * into a more specific one — this is the same rule set, not a second classifier.
     */
    fun forStreamError(
        providerErrorType: String? = null,
        message: String? = null,
    ): ModelProviderErrorCode {
        providerNativeCode(providerErrorType)?.let { return it }
        val text = haystack(providerErrorType, message)
        return when {
            mentions(text, QUOTA_HINTS) -> ModelProviderErrorCode.QUOTA_EXHAUSTED
            mentions(text, RATE_LIMIT_HINTS) -> ModelProviderErrorCode.RATE_LIMITED
            else -> ModelProviderErrorCode.PROVIDER_ERROR
        }
    }

    /**
     * Wording that names a *short-term* refusal rather than a spent balance.
     *
     * Read only when the provider's own type was not machine-readable, and only to
     * tell a rate limit from a quota: the two are recovered differently, and calling a
     * rate limit exhausted would stop a request that only had to wait. Deliberately
     * narrow — when the wording is not conclusive the failure stays the generic
     * [PROVIDER_ERROR] instead of being guessed at.
     */
    private val RATE_LIMIT_HINTS = listOf(
        "rate limit",
        "rate_limit",
        "too many requests",
    )

    /**
     * Whether a category may be retried against the same model and connection.
     *
     * Only faults that are plausibly gone by the next attempt. A rejected
     * credential, a request the provider refused, a model that does not exist and an
     * exhausted quota are all still true one second later, so retrying them only
     * spends time and, for a quota, another provider's capacity.
     *
     * `RATE_LIMITED` is deliberately *not* here. A 429 is admitted by
     * `RateLimitManager` — it records the cooldown the provider asked for and the
     * next attempt is refused until that cooldown passes — so retrying it here would
     * be a second, worse retry loop that ignores the manager. Its recovery path is
     * the configured fallback, exactly as before this phase.
     */
    fun isTransient(code: ModelProviderErrorCode, httpStatus: Int? = null): Boolean = when (code) {
        ModelProviderErrorCode.TIMEOUT,
        ModelProviderErrorCode.NETWORK_ERROR,
        ModelProviderErrorCode.CONNECTION_FAILED,
        ModelProviderErrorCode.SERVER_ERROR,
        ModelProviderErrorCode.SERVICE_UNAVAILABLE,
        -> true

        // A provider fault with no HTTP status at all, or a 5xx that was not further
        // classified, is the provider's problem and may not repeat.
        ModelProviderErrorCode.PROVIDER_ERROR -> httpStatus == null || httpStatus >= 500

        else -> false
    }

    /** Maps a provider's own machine-readable status string, when it is reliable. */
    private fun providerNativeCode(type: String?): ModelProviderErrorCode? {
        val normalized = type?.trim()?.uppercase()?.replace('-', '_') ?: return null
        return when {
            normalized == "UNAUTHENTICATED" || normalized == "INVALID_API_KEY" ||
                normalized == "AUTHENTICATION_ERROR" -> ModelProviderErrorCode.AUTHENTICATION_FAILED

            normalized == "PERMISSION_DENIED" -> ModelProviderErrorCode.PERMISSION_DENIED
            normalized == "FORBIDDEN" -> ModelProviderErrorCode.AUTHORIZATION_FAILED

            // `model_not_found` is the OpenAI-compatible code for a model that does
            // not exist; `NOT_FOUND` is the gRPC-style status Gemini reports.
            normalized == "NOT_FOUND" || normalized == "MODEL_NOT_FOUND" ->
                ModelProviderErrorCode.MODEL_NOT_FOUND

            normalized == "DEADLINE_EXCEEDED" -> ModelProviderErrorCode.TIMEOUT
            normalized == "UNAVAILABLE" -> ModelProviderErrorCode.SERVICE_UNAVAILABLE
            normalized == "INTERNAL" -> ModelProviderErrorCode.SERVER_ERROR
            // `RESOURCE_EXHAUSTED` is deliberately absent: it covers both a short rate
            // limit and a spent balance, so it is resolved from the status and the
            // message rather than guessed from the name.
            else -> null
        }
    }

    /**
     * A 403 that names a permission/policy reason is a policy refusal; a bare 403 is
     * an authorization problem. Both are permanent, but they are reported
     * differently because they are fixed differently.
     */
    private fun permissionOrAuthorization(type: String?, message: String?): ModelProviderErrorCode =
        if (mentions(haystack(type, message), PERMISSION_HINTS)) {
            ModelProviderErrorCode.PERMISSION_DENIED
        } else {
            ModelProviderErrorCode.AUTHORIZATION_FAILED
        }

    /**
     * A 429 that describes a spent balance is treated as exhausted quota rather than
     * a rate to wait out. Only the provider's own wording is used: when the body says
     * nothing conclusive the failure stays `RATE_LIMITED`, which is the safer of the
     * two.
     */
    private fun rateLimitedOrQuota(type: String?, message: String?): ModelProviderErrorCode =
        if (mentions(haystack(type, message), QUOTA_HINTS)) {
            ModelProviderErrorCode.QUOTA_EXHAUSTED
        } else {
            ModelProviderErrorCode.RATE_LIMITED
        }

    /** Lower-cased, credential-free tokens of the error's own type and message. */
    private fun haystack(type: String?, message: String?): String =
        listOfNotNull(type, message).joinToString(" ").lowercase()

    private fun mentions(haystack: String, hints: List<String>): Boolean =
        hints.any { hint -> haystack.contains(hint) }

    private val PERMISSION_HINTS = listOf(
        "permission",
        "policy",
        "not allowed",
        "not permitted",
        "region",
        "blocked by",
    )

    /**
     * Phrases that name a *spent balance* rather than a rate to wait out.
     *
     * Deliberately narrow. "quota exceeded" is not here: providers use it just as
     * often for a per-minute allowance that clears on its own, and mistaking a rate
     * limit for an exhausted balance would stop a request that only needed to wait.
     * When the body says nothing conclusive the failure stays `RATE_LIMITED`, which is
     * the recoverable of the two.
     */
    private val QUOTA_HINTS = listOf(
        "insufficient_quota",
        "insufficient quota",
        "exceeded your current quota",
        "out of credits",
        "credit balance is too low",
        "billing",
        "payment required",
    )
}
