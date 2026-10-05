package com.agentx.app.agent.domain

import com.agentx.app.agent.model.ModelFallbackErrors
import com.agentx.app.model.ModelProviderError
import com.agentx.app.model.ModelProviderErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Failure classification is what decides whether a configured fallback is allowed to
 * run, so every category states its answer explicitly instead of leaving "no
 * fallback" to be inferred from a null.
 */
class FallbackFailureCategoryTest {

    private fun error(code: ModelProviderErrorCode, status: Int? = null) = ModelProviderError(
        code = code,
        message = "test",
        httpStatus = status,
    )

    @Test
    fun `temporary execution failures are eligible`() {
        val eligible = listOf(
            error(ModelProviderErrorCode.RATE_LIMITED),
            error(ModelProviderErrorCode.TIMEOUT),
            error(ModelProviderErrorCode.NETWORK_ERROR),
            error(ModelProviderErrorCode.CONNECTION_FAILED),
            error(ModelProviderErrorCode.PROVIDER_ERROR, status = 500),
            error(ModelProviderErrorCode.PROVIDER_ERROR, status = 503),
            error(ModelProviderErrorCode.PROVIDER_ERROR, status = null),
        )

        eligible.forEach {
            assertTrue(
                FallbackFailureCategory.of(it).fallbackEligible,
                "${it.code}/${it.httpStatus} may be answered by another model",
            )
        }
    }

    @Test
    fun `failures another model cannot fix are ineligible`() {
        val ineligible = listOf(
            error(ModelProviderErrorCode.AUTHENTICATION_FAILED),
            error(ModelProviderErrorCode.INVALID_REQUEST),
            error(ModelProviderErrorCode.INVALID_CONFIG),
            error(ModelProviderErrorCode.UNSUPPORTED),
            error(ModelProviderErrorCode.INVALID_RESPONSE),
            error(ModelProviderErrorCode.PROVIDER_NOT_FOUND),
            error(ModelProviderErrorCode.DUPLICATE_PROVIDER),
            error(ModelProviderErrorCode.CANCELLED),
            error(ModelProviderErrorCode.UNKNOWN),
            // A 4xx provider fault is this request's problem, not the provider's.
            error(ModelProviderErrorCode.PROVIDER_ERROR, status = 400),
            error(ModelProviderErrorCode.PROVIDER_ERROR, status = 404),
        )

        ineligible.forEach {
            assertTrue(
                !FallbackFailureCategory.of(it).fallbackEligible,
                "${it.code}/${it.httpStatus} must not be answered by another model",
            )
        }
    }

    @Test
    fun `every provider error code maps to a named category`() {
        ModelProviderErrorCode.entries.forEach { code ->
            val category = FallbackFailureCategory.of(error(code))
            assertTrue(
                category != FallbackFailureCategory.UNKNOWN || code == ModelProviderErrorCode.UNKNOWN,
                "$code must be classified as something specific",
            )
        }
    }

    @Test
    fun `a cancelled run is never a fallback trigger`() {
        assertEquals(FallbackFailureCategory.USER_CANCELLED, FallbackFailureCategory.of(error(ModelProviderErrorCode.CANCELLED)))
        assertNull(ModelFallbackErrors.triggerFor(error(ModelProviderErrorCode.CANCELLED)))
    }

    @Test
    fun `an unknown capability is classified apart from a refusal`() {
        // Ignorance and refusal are different states, and neither may become
        // "supported" just because a primary model failed.
        assertTrue(FallbackFailureCategory.CAPABILITY_UNKNOWN.name == "CAPABILITY_UNKNOWN")
        assertTrue(!FallbackFailureCategory.CAPABILITY_UNKNOWN.fallbackEligible)
        assertTrue(!FallbackFailureCategory.MODEL_NOT_ELIGIBLE.fallbackEligible)
    }

    @Test
    fun `the trigger vocabulary is unchanged for every existing category`() {
        // P1-2 and earlier phases depend on exactly this mapping; naming the
        // categories must not move any of them across the line.
        assertEquals(ModelFallbackReason.RATE_LIMITED, ModelFallbackErrors.triggerFor(error(ModelProviderErrorCode.RATE_LIMITED)))
        assertEquals(ModelFallbackReason.TIMEOUT, ModelFallbackErrors.triggerFor(error(ModelProviderErrorCode.TIMEOUT)))
        assertEquals(ModelFallbackReason.NETWORK_FAILURE, ModelFallbackErrors.triggerFor(error(ModelProviderErrorCode.NETWORK_ERROR)))
        assertEquals(ModelFallbackReason.NETWORK_FAILURE, ModelFallbackErrors.triggerFor(error(ModelProviderErrorCode.CONNECTION_FAILED)))
        assertEquals(
            ModelFallbackReason.PROVIDER_UNAVAILABLE,
            ModelFallbackErrors.triggerFor(error(ModelProviderErrorCode.PROVIDER_ERROR, status = 502)),
        )

        listOf(
            ModelProviderErrorCode.AUTHENTICATION_FAILED,
            ModelProviderErrorCode.INVALID_REQUEST,
            ModelProviderErrorCode.INVALID_CONFIG,
            ModelProviderErrorCode.UNSUPPORTED,
            ModelProviderErrorCode.INVALID_RESPONSE,
            ModelProviderErrorCode.PROVIDER_NOT_FOUND,
            ModelProviderErrorCode.DUPLICATE_PROVIDER,
            ModelProviderErrorCode.CANCELLED,
            ModelProviderErrorCode.UNKNOWN,
        ).forEach { code ->
            assertNull(ModelFallbackErrors.triggerFor(error(code)), "$code must stay permanent")
        }
        assertNull(ModelFallbackErrors.triggerFor(error(ModelProviderErrorCode.PROVIDER_ERROR, status = 422)))
    }

    @Test
    fun `a non-provider exception is never a fallback trigger`() {
        val category = FallbackFailureCategory.of(IllegalStateException("boom"))

        assertEquals(FallbackFailureCategory.UNKNOWN, category)
        assertTrue(!category.fallbackEligible)
        assertNull(ModelFallbackErrors.triggerFor(IllegalStateException("boom")))
        assertNotNull(FallbackFailureCategory.of(error(ModelProviderErrorCode.TIMEOUT)))
    }
}
