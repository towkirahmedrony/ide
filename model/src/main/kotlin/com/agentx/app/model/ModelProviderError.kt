package com.agentx.app.model

/** Stable, coarse-grained categories for model failures. */
enum class ModelProviderErrorCode {
    INVALID_CONFIG,
    INVALID_REQUEST,
    INVALID_RESPONSE,
    AUTHENTICATION_FAILED,
    RATE_LIMITED,
    TIMEOUT,
    NETWORK_ERROR,
    CONNECTION_FAILED,
    PROVIDER_ERROR,
    UNSUPPORTED,
    CANCELLED,
    DUPLICATE_PROVIDER,
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
    val retryable: Boolean = false,
    val details: Map<String, Any?> = emptyMap(),
    cause: Throwable? = null,
) : RuntimeException(message, cause) {

    override fun toString(): String =
        "ModelProviderError(code=$code, provider=$providerId, status=$httpStatus, message=$message)"
}
