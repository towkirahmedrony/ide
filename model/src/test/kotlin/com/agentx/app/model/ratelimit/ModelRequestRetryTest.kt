package com.agentx.app.model.ratelimit

import com.agentx.app.model.DefaultModelGateway
import com.agentx.app.model.ModelCapabilities
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.ModelMessage
import com.agentx.app.model.ModelProvider
import com.agentx.app.model.ModelProviderError
import com.agentx.app.model.ModelProviderErrorCode
import com.agentx.app.model.ModelRequest
import com.agentx.app.model.ModelResponse
import com.agentx.app.model.ModelStreamEvent
import com.agentx.app.model.ModelUsage
import com.agentx.app.model.preset.ModelProviderType
import com.agentx.app.model.retry.RetryVerdict
import com.agentx.app.model.retry.TransientRetryPolicy
import com.agentx.app.model.runSuspend
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The bounded transient retry, exercised through the real gateway.
 *
 * Retry lives *below* the fallback layer, so everything here is about a single
 * logical request that must end either in a served response or in one structured
 * failure — never in a loop, never in a second model, and never in a request the
 * quota manager did not admit.
 *
 * The layer explicitly does **not** cover tool execution, git or GitHub writes:
 * those never pass through [RateLimitedModelGateway], they have their own
 * permission and idempotency rules, and a shell command with a side effect must not
 * be repeated because it failed. The last test below pins that boundary from the
 * only side this layer can see — a failure that is not a provider failure is passed
 * straight through.
 */
class ModelRequestRetryTest {

    // --- harness -----------------------------------------------------------

    /** A provider whose first [failures] calls throw, and which then succeeds. */
    private class ScriptedProvider(
        override val id: String = "groq",
        private val failures: List<Throwable> = emptyList(),
        private val streamFailures: List<Throwable> = failures,
        private val streamEmitsBeforeFailing: String? = null,
        private val content: String = "ok",
    ) : ModelProvider {
        var completeCalls = 0
        var streamCalls = 0

        override fun capabilities(modelId: String): ModelCapabilities = ModelCapabilities(streaming = true)

        override suspend fun complete(request: ModelRequest): ModelResponse {
            val index = completeCalls
            completeCalls += 1
            failures.getOrNull(index)?.let { throw it }
            return ModelResponse(model = request.model, providerId = id, content = content, usage = ModelUsage(totalTokens = 5))
        }

        override suspend fun stream(
            request: ModelRequest,
            onEvent: (ModelStreamEvent) -> Unit,
        ): ModelResponse {
            val index = streamCalls
            streamCalls += 1
            onEvent(ModelStreamEvent.Started(request.model, id))
            val failure = streamFailures.getOrNull(index)
            if (failure != null) {
                // A partial response: output reaches the wire, then the stream breaks.
                streamEmitsBeforeFailing?.let { partial ->
                    onEvent(ModelStreamEvent.TextDelta(partial))
                }
                throw failure
            }
            onEvent(ModelStreamEvent.TextDelta(content))
            return ModelResponse(model = request.model, providerId = id, content = content)
        }
    }

    /**
     * A [RateLimitManager] that records every admission question and can start
     * refusing after a set number of them, so "a retry re-enters admission control"
     * and "a retry cannot bypass it" are both observable rather than assumed.
     */
    private class RecordingRateLimits(
        private val delegate: RateLimitManager,
        private val allowAdmissions: Int = Int.MAX_VALUE,
    ) : RateLimitManager by delegate {

        val canRequestCalls = mutableListOf<String>()
        val reserveCalls = mutableListOf<String>()

        override suspend fun canRequest(
            providerId: String,
            modelId: String,
            estimatedInputTokens: Int,
            estimatedOutputTokens: Int,
            accountId: String?,
            local: Boolean,
        ): RateLimitDecision {
            canRequestCalls += "$providerId/$modelId"
            if (canRequestCalls.size > allowAdmissions) {
                return RateLimitDecision.Blocked(
                    providerId = providerId,
                    modelId = modelId,
                    retryAfterMs = 1_000L,
                    kind = RateLimitKind.REQUEST,
                    reason = "test: admission withdrawn",
                )
            }
            return delegate.canRequest(providerId, modelId, estimatedInputTokens, estimatedOutputTokens, accountId, local)
        }

        override suspend fun reserve(
            providerId: String,
            modelId: String,
            estimatedInputTokens: Int,
            estimatedOutputTokens: Int,
            accountId: String?,
            local: Boolean,
        ): RateLimitReservation {
            reserveCalls += "$providerId/$modelId"
            return delegate.reserve(providerId, modelId, estimatedInputTokens, estimatedOutputTokens, accountId, local)
        }
    }

    private class Harness(
        val provider: ModelProvider,
        val limits: RateLimitManager = RecordingRateLimits(DefaultRateLimitManager(clock = FakeRateLimitClock())),
        maxAttempts: Int = 4,
    ) {
        /** Records each backoff instead of actually waiting. */
        val wait = FakeRateLimitClock()
        val gateway: RateLimitedModelGateway = DefaultModelGateway().let { base ->
            base.register(provider)
            RateLimitedModelGateway(
                delegate = base,
                rateLimits = limits,
                retry = TransientRetryPolicy(maximumAttempts = maxAttempts),
                wait = wait,
            )
        }

        val backoffs: List<Long> get() = wait.sleeps
    }

    private fun config() = ModelConfig(
        providerId = "groq",
        baseUrl = "https://api.groq.com/openai/v1",
        model = "llama-3.3-70b-versatile",
        metadata = mapOf("providerType" to ModelProviderType.REMOTE_OPENAI_COMPATIBLE.name),
    )

    private fun request() = ModelRequest(config = config(), messages = listOf(ModelMessage.user("hello")))

    private fun providerError(
        code: ModelProviderErrorCode,
        status: Int? = null,
        retryAfterMillis: Long? = null,
    ) = ModelProviderError(
        code = code,
        message = "${code.name} from the provider",
        providerId = "groq",
        httpStatus = status,
        retryable = code == ModelProviderErrorCode.TIMEOUT,
        retryAfterMillis = retryAfterMillis,
    )

    // --- A. timeout ---------------------------------------------------------

    @Test
    fun `a transient timeout is retried and the next attempt is served`() = runSuspend {
        val provider = ScriptedProvider(failures = listOf(providerError(ModelProviderErrorCode.TIMEOUT, 408)))
        val harness = Harness(provider)

        val response = harness.gateway.complete(request())

        assertEquals("ok", response.content)
        assertEquals(2, provider.completeCalls, "one retry after the timeout")
        assertEquals(1, harness.backoffs.size, "exactly one backoff")
        assertTrue(harness.backoffs.single() > 0L, "the backoff must be a real wait")
    }

    @Test
    fun `retries are bounded and end in one structured failure`() = runSuspend {
        val provider = ScriptedProvider(failures = List(10) { providerError(ModelProviderErrorCode.TIMEOUT, 408) })
        val harness = Harness(provider, maxAttempts = 4)

        val error = assertFailsWith<ModelProviderError> { harness.gateway.complete(request()) }

        assertEquals(ModelProviderErrorCode.TIMEOUT, error.code)
        assertEquals(4, provider.completeCalls, "the attempt budget is a hard bound")
        assertEquals(3, harness.backoffs.size, "one wait between each pair of attempts")
        assertEquals(4, error.details["attempts"])
        assertEquals(4, error.details["maxAttempts"])
        assertEquals(RetryVerdict.ATTEMPTS_EXHAUSTED.name, error.details["retryOutcome"])
    }

    // --- B. network failure -------------------------------------------------

    @Test
    fun `a network failure is retried with an increasing backoff`() = runSuspend {
        val provider = ScriptedProvider(failures = List(3) { providerError(ModelProviderErrorCode.NETWORK_ERROR) })
        val harness = Harness(provider, maxAttempts = 4)

        assertEquals("ok", harness.gateway.complete(request()).content)
        assertEquals(4, provider.completeCalls)
        val backoffs = harness.backoffs
        assertEquals(3, backoffs.size)
        assertEquals(backoffs.sorted(), backoffs, "the wait must not shrink: $backoffs")
        assertTrue(backoffs.last() > backoffs.first(), "the wait must grow: $backoffs")
    }

    @Test
    fun `a dropped connection is retried`() = runSuspend {
        val provider = ScriptedProvider(failures = listOf(providerError(ModelProviderErrorCode.CONNECTION_FAILED)))
        val harness = Harness(provider)

        assertEquals("ok", harness.gateway.complete(request()).content)
        assertEquals(2, provider.completeCalls)
    }

    // --- C. 5xx -------------------------------------------------------------

    @Test
    fun `500 502 and 503 are all transient and are retried`() = runSuspend {
        val cases = listOf(
            ModelProviderErrorCode.SERVER_ERROR to 500,
            ModelProviderErrorCode.SERVER_ERROR to 502,
            ModelProviderErrorCode.SERVICE_UNAVAILABLE to 503,
        )
        cases.forEach { (code, status) ->
            val provider = ScriptedProvider(failures = listOf(providerError(code, status)))
            val harness = Harness(provider)

            assertEquals("ok", harness.gateway.complete(request()).content, "HTTP $status must be retried")
            assertEquals(2, provider.completeCalls, "HTTP $status must be retried once")
        }
    }

    // --- D, E, F, G. permanent client failures ------------------------------

    @Test
    fun `401 403 404 and 400 are never retried`() = runSuspend {
        val cases = listOf(
            ModelProviderErrorCode.AUTHENTICATION_FAILED to 401,
            ModelProviderErrorCode.AUTHORIZATION_FAILED to 403,
            ModelProviderErrorCode.MODEL_NOT_FOUND to 404,
            ModelProviderErrorCode.INVALID_REQUEST to 400,
        )
        cases.forEach { (code, status) ->
            val provider = ScriptedProvider(failures = List(5) { providerError(code, status) })
            val harness = Harness(provider)

            val error = assertFailsWith<ModelProviderError> { harness.gateway.complete(request()) }

            assertEquals(code, error.code, "HTTP $status must keep its category")
            assertEquals(1, provider.completeCalls, "HTTP $status must not be retried")
            assertTrue(harness.backoffs.isEmpty(), "HTTP $status must not back off")
        }
    }

    // --- H. 429 -------------------------------------------------------------

    @Test
    fun `a 429 is recorded with the manager and never storms`() = runSuspend {
        val provider = ScriptedProvider(
            failures = List(5) { providerError(ModelProviderErrorCode.RATE_LIMITED, 429, retryAfterMillis = 3_000L) },
        )
        val limits = DefaultRateLimitManager(clock = FakeRateLimitClock())
        val harness = Harness(provider, limits = limits)

        val error = assertFailsWith<ModelProviderError> { harness.gateway.complete(request()) }

        assertEquals(ModelProviderErrorCode.RATE_LIMITED, error.code)
        assertEquals(false, error.retryable, "a rate limit is admitted by the manager, not repeated here")
        assertEquals(1, provider.completeCalls, "a 429 must not start a retry loop")
        assertTrue(harness.backoffs.isEmpty(), "a 429 must not back off")

        // The provider's Retry-After is what the manager now enforces, so the *next*
        // request is refused for that long instead of hammering the endpoint.
        val blocked = assertIs<RateLimitDecision.Blocked>(
            limits.canRequest("groq", "llama-3.3-70b-versatile", 1, 1),
        )
        assertEquals(3_000L, blocked.retryAfterMs)
    }

    @Test
    fun `a spent quota is classified apart from a rate limit and is not retried`() = runSuspend {
        val provider = ScriptedProvider(
            failures = List(5) {
                ModelProviderError(
                    code = ModelProviderErrorCode.QUOTA_EXHAUSTED,
                    message = "You exceeded your current quota",
                    providerId = "groq",
                    httpStatus = 429,
                )
            },
        )
        val harness = Harness(provider)

        val error = assertFailsWith<ModelProviderError> { harness.gateway.complete(request()) }

        assertEquals(ModelProviderErrorCode.QUOTA_EXHAUSTED, error.code)
        assertEquals(1, provider.completeCalls)
    }

    // --- rate-limit integration ---------------------------------------------

    @Test
    fun `every attempt re-enters admission control`() = runSuspend {
        val provider = ScriptedProvider(failures = listOf(providerError(ModelProviderErrorCode.TIMEOUT, 408)))
        val limits = RecordingRateLimits(DefaultRateLimitManager(clock = FakeRateLimitClock()))
        val harness = Harness(provider, limits = limits)

        harness.gateway.complete(request())

        assertEquals(2, provider.completeCalls)
        // The retry did not ride on the first attempt's decision or reservation.
        assertEquals(2, limits.canRequestCalls.size, "admission must be re-checked per attempt")
        assertEquals(2, limits.reserveCalls.size, "each attempt takes its own reservation")
    }

    @Test
    fun `a retry is refused by admission control rather than dispatched`() = runSuspend {
        val provider = ScriptedProvider(failures = listOf(providerError(ModelProviderErrorCode.TIMEOUT, 408)))
        // The first request is admitted; by the time the retry asks, the scope has no
        // headroom left. The retry must not be sent anyway.
        val limits = RecordingRateLimits(DefaultRateLimitManager(clock = FakeRateLimitClock()), allowAdmissions = 1)
        val harness = Harness(provider, limits = limits)

        val error = assertFailsWith<ModelProviderError> { harness.gateway.complete(request()) }

        assertEquals(ModelProviderErrorCode.RATE_LIMITED, error.code)
        assertEquals(1, provider.completeCalls, "a retry must not bypass the quota manager")
        assertEquals(2, limits.canRequestCalls.size)
        assertEquals(1, limits.reserveCalls.size, "a refused request reserves nothing")
    }

    // --- L. cancellation ----------------------------------------------------

    @Test
    fun `cancellation during a backoff starts no further attempt`() = runSuspend {
        val provider = ScriptedProvider(failures = List(5) { providerError(ModelProviderErrorCode.TIMEOUT, 408) })
        val base = DefaultModelGateway()
        base.register(provider)
        val gateway = RateLimitedModelGateway(
            delegate = base,
            rateLimits = DefaultRateLimitManager(clock = FakeRateLimitClock()),
            retry = TransientRetryPolicy(maximumAttempts = 4),
            // The user stopped the run while the app was waiting to retry.
            wait = CancellingRateLimitClock(cancelAfter = 1),
        )

        val cancellation = assertFailsWith<CancellationException> { gateway.complete(request()) }

        assertTrue(cancellation.message.orEmpty().contains("test cancellation"))
        assertEquals(1, provider.completeCalls, "no attempt may start after cancellation")
    }

    // --- M. streaming -------------------------------------------------------

    @Test
    fun `a stream that failed before emitting anything is retried`() = runSuspend {
        val provider = ScriptedProvider(
            streamFailures = listOf(providerError(ModelProviderErrorCode.TIMEOUT, 408)),
        )
        val harness = Harness(provider)
        val events = mutableListOf<ModelStreamEvent>()

        val response = harness.gateway.stream(request()) { events += it }

        assertEquals("ok", response.content)
        assertEquals(2, provider.streamCalls, "nothing reached the user, so a retry is safe")
        assertEquals(listOf("ok"), events.filterIsInstance<ModelStreamEvent.TextDelta>().map { it.text })
    }

    @Test
    fun `a stream that failed after emitting output is never restarted`() = runSuspend {
        val provider = ScriptedProvider(
            streamFailures = List(5) { providerError(ModelProviderErrorCode.TIMEOUT, 408) },
            streamEmitsBeforeFailing = "partial answer",
        )
        val harness = Harness(provider)
        val deltas = mutableListOf<String>()

        val error = assertFailsWith<ModelProviderError> {
            harness.gateway.stream(request()) { event ->
                if (event is ModelStreamEvent.TextDelta) deltas += event.text
            }
        }

        assertEquals(ModelProviderErrorCode.TIMEOUT, error.code)
        assertEquals(1, provider.streamCalls, "a partially emitted response must not be restarted")
        assertTrue(harness.backoffs.isEmpty(), "no backoff may start after partial output")
        // The user sees exactly what the failed attempt produced, once.
        assertEquals(listOf("partial answer"), deltas)
        assertEquals(RetryVerdict.OUTPUT_ALREADY_EMITTED.name, error.details["retryOutcome"])
    }

    // --- N. side-effect boundary --------------------------------------------

    @Test
    fun `a failure that is not a provider failure is passed straight through`() = runSuspend {
        // Tool execution, git and GitHub writes never reach this gateway, so this layer
        // has no way to repeat them. What it must also not do is invent a retry for
        // anything else that happens to fail on the model path.
        val thrown = IllegalStateException("a non-provider failure")
        val provider = ScriptedProvider(failures = listOf(thrown))
        val harness = Harness(provider)

        val error = assertFailsWith<IllegalStateException> { harness.gateway.complete(request()) }

        assertSame(thrown, error)
        assertEquals(1, provider.completeCalls)
        assertTrue(harness.backoffs.isEmpty())
    }
}
