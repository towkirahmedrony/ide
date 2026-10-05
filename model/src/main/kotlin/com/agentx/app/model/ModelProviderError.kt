package com.agentx.app.model

/**
 * Stable, coarse-grained categories for model failures.
 *
 * This is the transport-facing half of the classification: it is what a provider
 * can state about a single response. The named, decision-facing half lives in
 * `FallbackFailureCategory`, which maps every member of this enum onto exactly one
 * category; nothing here decides what to do about a failure.
 *
 * The categories are deliberately not "4xx" and "5xx": a rejected credential, a
 * request the provider refused, and a model id the provider does not know are
 * three different problems with three different answers, and collapsing them would
 * make "retry", "fall back" and "fail now" indistinguishable.
 */
enum class ModelProviderErrorCode {
    INVALID_CONFIG,
    INVALID_REQUEST,
    INVALID_RESPONSE,

    /** The credential was rejected (HTTP 401 / `UNAUTHENTICATED`). */
    AUTHENTICATION_FAILED,

    /**
     * The credential is valid but not permitted to make this call (HTTP 403 /
     * generic `FORBIDDEN`). Retrying it, here or elsewhere, changes nothing.
     */
    AUTHORIZATION_FAILED,

    /** A policy check refused the call (provider policy, region, quota policy). */
    PERMISSION_DENIED,

    /** The provider refused the request because a short-term rate limit applies. */
    RATE_LIMITED,

    /**
     * The provider refused the request because the account's quota/credit is spent
     * (`insufficient_quota`, `RESOURCE_EXHAUSTED` with billing semantics).
     *
     * Distinct from [RATE_LIMITED] because waiting is not the answer: a new minute
     * does not return an exhausted balance.
     */
    QUOTA_EXHAUSTED,

    /** The request exceeded its time budget (HTTP 408/504, socket timeout). */
    TIMEOUT,

    /** A transport-level network fault that may well not repeat. */
    NETWORK_ERROR,

    /** The endpoint could not be reached at all. */
    CONNECTION_FAILED,

    /** The provider answered 5xx without saying anything more specific. */
    SERVER_ERROR,

    /** The provider explicitly reports itself unavailable or overloaded (HTTP 503). */
    SERVICE_UNAVAILABLE,

    /** The named model or chat route does not exist at the provider (HTTP 404/410). */
    MODEL_NOT_FOUND,

    /** A provider fault that does not fit a more specific category. */
    PROVIDER_ERROR,

    /** The model cannot run this request (capability, disabled, or excluded). */
    UNSUPPORTED,

    /** The run was cancelled by the user or the runtime. */
    CANCELLED,

    DUPLICATE_PROVIDER,

    /** No registered connection matches the request's connection identity. */
    PROVIDER_NOT_FOUND,

    UNKNOWN,
}

/**
 * Normalized provider failure. Every provider maps its own errors (HTTP status,
 * transport exceptions, malformed payloads) onto this single type so callers
 * never depend on a specific backend.
 *
 * [details] must never contain credentials or raw request headers.
 */
class ModelProviderError(
    val code: ModelProviderErrorCode,
    message: String,
    val providerId: String? = null,
    val httpStatus: Int? = null,
    /** Provider-specific error type/code, kept for diagnostics. */
    val providerErrorType: String? = null,
    /**
     * Whether the bounded same-model retry layer may repeat this exact request.
     *
     * It is the retry policy's own answer, set once by
     * [com.agentx.app.model.error.ProviderErrorClassifier.isTransient] rather than by
     * each call site, so "may this be repeated?" has one definition. A `429` is
     * therefore `false` even though the request will work later: a rate limit is
     * governed by [com.agentx.app.model.ratelimit.RateLimitManager], which records the
     * cooldown the provider asked for, and repeating it here would bypass that.
     */
    val retryable: Boolean = false,
    /**
     * Provider-supplied `Retry-After` hint in milliseconds, when the response
     * carried one. Only the rate-limit retry path reads it; it is never logged
     * with a request body or header.
     */
    val retryAfterMillis: Long? = null,
    val details: Map<String, Any?> = emptyMap(),
    cause: Throwable? = null,
) : RuntimeException(message, cause) {

    override fun toString(): String =
        "ModelProviderError(code=$code, provider=$providerId, status=$httpStatus, message=$message)"
}
