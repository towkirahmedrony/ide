package com.agentx.app.agent.domain

import com.agentx.app.model.ModelProviderError
import com.agentx.app.model.ModelProviderErrorCode

/**
 * The named category a model failure falls into, whether or not it may trigger a
 * fallback.
 *
 * The agent layer previously answered only "is this temporary?" — a null from
 * `ModelFallbackErrors.triggerFor` meant *every* permanent outcome alike, so an
 * invalid key, a denied permission, a cancelled run and a malformed request were
 * indistinguishable in the runtime's own vocabulary even though they are very
 * different things to a user. This type names them, and every category states
 * [fallbackEligible] explicitly, so "no fallback" is a decision that can be read
 * rather than an absence.
 *
 * It maps the existing [ModelProviderErrorCode] categories rather than replacing
 * them: nothing here reinterprets a provider contract, and a provider that already
 * reports a structured code keeps its meaning.
 *
 * Nothing in this type carries a prompt, a request body or a credential.
 */
enum class FallbackFailureCategory(
    /**
     * Whether a *configured* fallback candidate may be tried after this failure.
     *
     * Only failures where a different model could plausibly answer are eligible.
     * Anything the request itself is responsible for — a bad model id, a malformed
     * body, a rejected credential, a denied permission, a cancelled run — is
     * ineligible, because the same request sent to another model fails the same way
     * and switching would only hide the real problem while spending another
     * provider's quota.
     */
    val fallbackEligible: Boolean,
) {
    /** The provider refused the request because a rate limit applies. */
    RATE_LIMITED(fallbackEligible = true),

    /** AgentX's own admission control knows the scope has no safe headroom left. */
    QUOTA_EXHAUSTED(fallbackEligible = true),

    /** A transient provider-side fault, for example an HTTP 5xx. */
    TEMPORARY_PROVIDER_FAILURE(fallbackEligible = true),

    /** The provider is unreachable or reports itself unavailable. */
    PROVIDER_UNAVAILABLE(fallbackEligible = true),

    /** A connection that is up but not usable for this request. */
    CONNECTION_DEGRADED(fallbackEligible = true),

    /** The request exceeded its time budget. */
    TIMEOUT(fallbackEligible = true),

    /** A transient network or transport failure. */
    NETWORK_FAILURE(fallbackEligible = true),

    /** The credential was rejected. Retrying elsewhere would spend another key. */
    AUTHENTICATION_FAILED(fallbackEligible = false),

    /** The credential is valid but not permitted to use this model. */
    AUTHORIZATION_FAILED(fallbackEligible = false),

    /** The provider rejected the request body or parameters. */
    INVALID_REQUEST(fallbackEligible = false),

    /**
     * The provider answered with something that could not be understood.
     *
     * Kept ineligible, as it always has been: a body the runtime cannot parse is not
     * evidence that another model would answer, and a second provider would be billed
     * to find out the same thing.
     */
    MALFORMED_RESPONSE(fallbackEligible = false),

    /** The named model does not exist at the provider. */
    MODEL_NOT_FOUND(fallbackEligible = false),

    /** The model cannot run this role (capability, disabled, or excluded). */
    MODEL_NOT_ELIGIBLE(fallbackEligible = false),

    /**
     * A required capability is not known to be supported.
     *
     * Distinct from [MODEL_NOT_ELIGIBLE] because it is *ignorance*, not refusal: it
     * is the state that must never be upgraded to "supported" for the sake of a
     * fallback.
     */
    CAPABILITY_UNKNOWN(fallbackEligible = false),

    /** A permission or policy check refused the call. */
    PERMISSION_DENIED(fallbackEligible = false),

    /** The run was cancelled; nothing further may start. */
    USER_CANCELLED(fallbackEligible = false),

    /** The configuration is wrong in a way another model cannot fix. */
    INVALID_CONFIGURATION(fallbackEligible = false),

    /** Classified as nothing more specific. Never a fallback trigger. */
    UNKNOWN(fallbackEligible = false),
    ;

    companion object {

        /**
         * Classifies a provider failure. Every [ModelProviderErrorCode] maps to
         * exactly one category, so no code is silently unhandled.
         */
        fun of(error: ModelProviderError): FallbackFailureCategory = when (error.code) {
            ModelProviderErrorCode.RATE_LIMITED -> RATE_LIMITED
            ModelProviderErrorCode.TIMEOUT -> TIMEOUT
            ModelProviderErrorCode.NETWORK_ERROR -> NETWORK_FAILURE
            ModelProviderErrorCode.CONNECTION_FAILED -> CONNECTION_DEGRADED
            ModelProviderErrorCode.AUTHENTICATION_FAILED -> AUTHENTICATION_FAILED
            ModelProviderErrorCode.INVALID_REQUEST -> INVALID_REQUEST
            ModelProviderErrorCode.INVALID_CONFIG -> INVALID_CONFIGURATION
            ModelProviderErrorCode.UNSUPPORTED -> MODEL_NOT_ELIGIBLE
            ModelProviderErrorCode.PROVIDER_NOT_FOUND,
            ModelProviderErrorCode.DUPLICATE_PROVIDER,
            -> MODEL_NOT_FOUND
            ModelProviderErrorCode.CANCELLED -> USER_CANCELLED
            ModelProviderErrorCode.INVALID_RESPONSE -> MALFORMED_RESPONSE
            ModelProviderErrorCode.PROVIDER_ERROR -> {
                // A 5xx (or a provider fault with no HTTP status) is the provider's
                // problem and may be answered elsewhere; a 4xx is this request's
                // problem and would be repeated identically by any other model.
                val status = error.httpStatus
                if (status == null || status >= 500) TEMPORARY_PROVIDER_FAILURE else INVALID_REQUEST
            }
            ModelProviderErrorCode.UNKNOWN -> UNKNOWN
        }

        /** Classifies any throwable, including a non-provider failure. */
        fun of(error: Throwable): FallbackFailureCategory =
            (error as? ModelProviderError)?.let(::of) ?: UNKNOWN
    }
}
