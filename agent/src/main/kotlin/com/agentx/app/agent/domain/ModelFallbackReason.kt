package com.agentx.app.agent.domain

/**
 * Why a controlled fallback to another model was attempted.
 *
 * Only temporary execution failures produce a reason; a permanent configuration
 * problem (invalid key, invalid model, malformed request, unsupported
 * capability) never does and is returned to the caller unchanged. This is purely
 * operational information — it never carries a prompt, a request body or a
 * credential.
 */
enum class ModelFallbackReason {
    /** The provider answered HTTP 429 (or the local admission control blocked it). */
    RATE_LIMITED,

    /** The provider/model request timed out. */
    TIMEOUT,

    /** A transient network or connection failure. */
    NETWORK_FAILURE,

    /** A temporary provider-side failure (for example an HTTP 5xx). */
    PROVIDER_UNAVAILABLE,
}
