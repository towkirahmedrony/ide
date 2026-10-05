package com.agentx.app.model.error

import com.agentx.app.model.ModelProviderErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The classification layer is what every later decision reads, so it is pinned here
 * status by status. The audit's complaint was that "many errors collapse into generic
 * UNKNOWN"; these tests exist so that cannot happen again silently.
 */
class ProviderErrorClassifierTest {

    // --- HTTP status -> category -------------------------------------------

    @Test
    fun `a rejected credential is an authentication failure`() {
        assertEquals(
            ModelProviderErrorCode.AUTHENTICATION_FAILED,
            ProviderErrorClassifier.forHttpStatus(401, message = "Invalid API key"),
        )
    }

    @Test
    fun `a forbidden call is an authorization failure, not an authentication one`() {
        // 403 means the credential was accepted and the call was still refused. Calling
        // that "authentication failed" sends the user to fix a key that works.
        assertEquals(
            ModelProviderErrorCode.AUTHORIZATION_FAILED,
            ProviderErrorClassifier.forHttpStatus(403, message = "no access to this model"),
        )
    }

    @Test
    fun `a forbidden call that names a policy is a permission denial`() {
        assertEquals(
            ModelProviderErrorCode.PERMISSION_DENIED,
            ProviderErrorClassifier.forHttpStatus(403, "PERMISSION_DENIED", "the caller does not have permission"),
        )
    }

    @Test
    fun `a missing model is a model-not-found failure`() {
        assertEquals(ModelProviderErrorCode.MODEL_NOT_FOUND, ProviderErrorClassifier.forHttpStatus(404))
        assertEquals(ModelProviderErrorCode.MODEL_NOT_FOUND, ProviderErrorClassifier.forHttpStatus(410))
        assertEquals(
            ModelProviderErrorCode.MODEL_NOT_FOUND,
            ProviderErrorClassifier.forHttpStatus(400, "model_not_found", "the model does not exist"),
        )
    }

    @Test
    fun `a request timeout is a timeout`() {
        assertEquals(ModelProviderErrorCode.TIMEOUT, ProviderErrorClassifier.forHttpStatus(408))
        assertEquals(ModelProviderErrorCode.TIMEOUT, ProviderErrorClassifier.forHttpStatus(504))
        assertEquals(ModelProviderErrorCode.TIMEOUT, ProviderErrorClassifier.forHttpStatus(500, "DEADLINE_EXCEEDED"))
    }

    @Test
    fun `a rate limit is not confused with a spent quota`() {
        assertEquals(ModelProviderErrorCode.RATE_LIMITED, ProviderErrorClassifier.forHttpStatus(429, message = "slow down"))
        // No conclusive wording: the recoverable reading wins, because a wrong
        // "exhausted" would stop a request that only had to wait.
        assertEquals(
            ModelProviderErrorCode.RATE_LIMITED,
            ProviderErrorClassifier.forHttpStatus(429, "RESOURCE_EXHAUSTED", "quota exceeded"),
        )
        assertEquals(
            ModelProviderErrorCode.QUOTA_EXHAUSTED,
            ProviderErrorClassifier.forHttpStatus(429, "insufficient_quota", "You exceeded your current quota"),
        )
    }

    @Test
    fun `server faults keep their own categories`() {
        assertEquals(ModelProviderErrorCode.SERVER_ERROR, ProviderErrorClassifier.forHttpStatus(500))
        assertEquals(ModelProviderErrorCode.SERVER_ERROR, ProviderErrorClassifier.forHttpStatus(502))
        assertEquals(ModelProviderErrorCode.SERVICE_UNAVAILABLE, ProviderErrorClassifier.forHttpStatus(503))
        assertEquals(ModelProviderErrorCode.SERVICE_UNAVAILABLE, ProviderErrorClassifier.forHttpStatus(500, "UNAVAILABLE"))
        assertEquals(ModelProviderErrorCode.SERVER_ERROR, ProviderErrorClassifier.forHttpStatus(500, "INTERNAL"))
    }

    @Test
    fun `a malformed request is an invalid request`() {
        listOf(400, 405, 406, 409, 413, 415, 422, 428).forEach { status ->
            assertEquals(
                ModelProviderErrorCode.INVALID_REQUEST,
                ProviderErrorClassifier.forHttpStatus(status),
                "HTTP $status is this request's problem",
            )
        }
    }

    @Test
    fun `an unrecognised status is still named rather than left unknown`() {
        assertEquals(ModelProviderErrorCode.PROVIDER_ERROR, ProviderErrorClassifier.forHttpStatus(418))
        // Every status the classifier can see maps to a real category, never UNKNOWN.
        (100..599).forEach { status ->
            assertTrue(
                ProviderErrorClassifier.forHttpStatus(status) != ModelProviderErrorCode.UNKNOWN,
                "HTTP $status must be classified",
            )
        }
    }

    // --- retry eligibility --------------------------------------------------

    @Test
    fun `only plausibly-transient failures are retryable`() {
        val retryable = listOf(
            ModelProviderErrorCode.TIMEOUT,
            ModelProviderErrorCode.NETWORK_ERROR,
            ModelProviderErrorCode.CONNECTION_FAILED,
            ModelProviderErrorCode.SERVER_ERROR,
            ModelProviderErrorCode.SERVICE_UNAVAILABLE,
        )
        retryable.forEach {
            assertTrue(ProviderErrorClassifier.isTransient(it), "$it may not repeat")
        }

        val permanent = listOf(
            ModelProviderErrorCode.AUTHENTICATION_FAILED,
            ModelProviderErrorCode.AUTHORIZATION_FAILED,
            ModelProviderErrorCode.PERMISSION_DENIED,
            ModelProviderErrorCode.INVALID_REQUEST,
            ModelProviderErrorCode.INVALID_CONFIG,
            ModelProviderErrorCode.MODEL_NOT_FOUND,
            ModelProviderErrorCode.UNSUPPORTED,
            ModelProviderErrorCode.CANCELLED,
            ModelProviderErrorCode.INVALID_RESPONSE,
            ModelProviderErrorCode.PROVIDER_NOT_FOUND,
            ModelProviderErrorCode.DUPLICATE_PROVIDER,
            ModelProviderErrorCode.UNKNOWN,
            // Rate limiting is the manager's business, not the retry layer's.
            ModelProviderErrorCode.RATE_LIMITED,
            ModelProviderErrorCode.QUOTA_EXHAUSTED,
        )
        permanent.forEach {
            assertFalse(ProviderErrorClassifier.isTransient(it), "$it must not be repeated")
        }
    }

    @Test
    fun `an unclassified provider fault is judged by its status`() {
        assertTrue(ProviderErrorClassifier.isTransient(ModelProviderErrorCode.PROVIDER_ERROR, httpStatus = null))
        assertTrue(ProviderErrorClassifier.isTransient(ModelProviderErrorCode.PROVIDER_ERROR, httpStatus = 500))
        assertFalse(ProviderErrorClassifier.isTransient(ModelProviderErrorCode.PROVIDER_ERROR, httpStatus = 400))
        assertFalse(ProviderErrorClassifier.isTransient(ModelProviderErrorCode.PROVIDER_ERROR, httpStatus = 429))
    }

    @Test
    fun `classification returns a category and can carry no payload`() {
        // The type and message are inputs only. The return type is a bare enum, so this
        // layer has nowhere to put a credential, a request body or a raw provider
        // payload even if one reached it.
        val category: ModelProviderErrorCode = ProviderErrorClassifier.forHttpStatus(
            401,
            "invalid_request_error",
            "Incorrect API key provided: sk-super-secret",
        )
        assertEquals(ModelProviderErrorCode.AUTHENTICATION_FAILED, category)
    }
}
