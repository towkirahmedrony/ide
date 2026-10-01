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
import com.agentx.app.model.runSuspend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RateLimitedModelGatewayTest {

    private class FlakyProvider(
        override val id: String,
        private val rateLimitedTimes: Int,
        private val retryAfterMillis: Long? = null,
        private val usage: ModelUsage? = null,
    ) : ModelProvider {
        var calls = 0

        override fun capabilities(modelId: String): ModelCapabilities =
            ModelCapabilities(streaming = true)

        override suspend fun complete(request: ModelRequest): ModelResponse {
            calls += 1
            if (calls <= rateLimitedTimes) throw rateLimited(retryAfterMillis)
            return ModelResponse(model = request.model, providerId = id, content = "ok", usage = usage)
        }

        override suspend fun stream(
            request: ModelRequest,
            onEvent: (ModelStreamEvent) -> Unit,
        ): ModelResponse {
            calls += 1
            if (calls <= rateLimitedTimes) throw rateLimited(retryAfterMillis)
            onEvent(ModelStreamEvent.Started(request.model, id))
            onEvent(ModelStreamEvent.TextDelta("ok"))
            return ModelResponse(model = request.model, providerId = id, content = "ok", usage = usage)
        }

        private fun rateLimited(retryAfter: Long?): ModelProviderError = ModelProviderError(
            code = ModelProviderErrorCode.RATE_LIMITED,
            message = "429",
            providerId = id,
            httpStatus = 429,
            retryable = true,
            retryAfterMillis = retryAfter,
        )
    }

    private fun config(
        providerId: String = "groq",
        providerType: ModelProviderType = ModelProviderType.REMOTE_OPENAI_COMPATIBLE,
    ) = ModelConfig(
        providerId = providerId,
        baseUrl = "https://api.groq.com/openai/v1",
        model = "llama-3.3-70b-versatile",
        metadata = mapOf("providerType" to providerType.name),
    )

    private fun gateway(
        provider: ModelProvider,
        manager: RateLimitManager,
        clock: RateLimitClock = FakeRateLimitClock(),
        maxAttempts: Int = 4,
    ): RateLimitedModelGateway {
        val base = DefaultModelGateway()
        base.register(provider)
        return RateLimitedModelGateway(
            delegate = base,
            rateLimits = manager,
            backoff = RateLimitBackoff(maxAttempts = maxAttempts, baseDelayMillis = 500L, random = { 0.0 }),
            clock = clock,
        )
    }

    private fun manager(clock: RateLimitClock = FakeRateLimitClock()) = DefaultRateLimitManager(
        clock = clock,
        tracker = DefaultUsageTracker(),
        unknownRemoteLimits = null,
    )

    private fun request(config: ModelConfig = config()) =
        ModelRequest(config = config, messages = listOf(ModelMessage.user("hello")))

    @Test
    fun `a 429 is retried centrally and then succeeds`() = runSuspend {
        val clock = FakeRateLimitClock()
        val provider = FlakyProvider("groq", rateLimitedTimes = 1)
        val gateway = gateway(provider, manager(clock), clock)

        val response = gateway.complete(request())

        assertEquals("ok", response.content)
        assertEquals(2, provider.calls)
        assertEquals(listOf(500L), clock.sleeps)
    }

    @Test
    fun `retry-after is respected`() = runSuspend {
        val clock = FakeRateLimitClock()
        val provider = FlakyProvider("groq", rateLimitedTimes = 1, retryAfterMillis = 3_000L)
        val gateway = gateway(provider, manager(clock), clock)

        gateway.complete(request())

        assertEquals(listOf(3_000L), clock.sleeps)
    }

    @Test
    fun `a persistent 429 ends with a clear rate-limit error and never loops forever`() = runSuspend {
        val clock = FakeRateLimitClock()
        val provider = FlakyProvider("groq", rateLimitedTimes = 100)
        val gateway = gateway(provider, manager(clock), clock, maxAttempts = 3)

        val error = assertFailsWith<ModelProviderError> { gateway.complete(request()) }

        assertEquals(ModelProviderErrorCode.RATE_LIMITED, error.code)
        assertTrue(error.message!!.contains("persisted after"), error.message)
        assertEquals(3, provider.calls, "no more attempts than the budget allows")
        assertEquals(2, clock.sleeps.size)
    }

    @Test
    fun `a non rate-limit error is not retried`() = runSuspend {
        val provider = object : ModelProvider {
            override val id = "groq"
            var calls = 0

            override fun capabilities(modelId: String) = ModelCapabilities(streaming = true)

            override suspend fun complete(request: ModelRequest): ModelResponse {
                calls += 1
                throw ModelProviderError(ModelProviderErrorCode.INVALID_REQUEST, "bad", id, httpStatus = 400)
            }

            override suspend fun stream(request: ModelRequest, onEvent: (ModelStreamEvent) -> Unit) =
                complete(request)
        }
        val gateway = gateway(provider, manager())

        val error = assertFailsWith<ModelProviderError> { gateway.complete(request()) }

        assertEquals(ModelProviderErrorCode.INVALID_REQUEST, error.code)
        assertEquals(1, provider.calls)
    }

    @Test
    fun `a local provider is not rate limited even with a zero remote limit`() = runSuspend {
        val provider = FlakyProvider("openai-compatible", rateLimitedTimes = 0)
        val limits = manager()
        limits.updateProfile(
            RateLimitProfile(
                providerId = "openai-compatible",
                requestsPerMinute = 0,
                source = RateLimitSource.APP_CONFIGURED,
            ),
        )
        val gateway = gateway(provider, limits)

        val response = gateway.complete(request(config("openai-compatible", ModelProviderType.LOCAL_PHONE)))

        assertEquals("ok", response.content)
        assertEquals(1, provider.calls)
    }

    @Test
    fun `usage from the provider is recorded by the manager`() = runSuspend {
        val tracker = DefaultUsageTracker()
        val limits = DefaultRateLimitManager(tracker = tracker, unknownRemoteLimits = null)
        val provider = FlakyProvider("groq", rateLimitedTimes = 0, usage = ModelUsage(totalTokens = 42))
        val gateway = gateway(provider, limits)

        gateway.complete(request())

        val totals = tracker.totals("groq", "llama-3.3-70b-versatile").single()
        assertEquals(1L, totals.requestCount)
        assertEquals(42L, totals.totalTokens)
    }

    @Test
    fun `streaming retries only before any event is forwarded`() = runSuspend {
        val clock = FakeRateLimitClock()
        val provider = FlakyProvider("groq", rateLimitedTimes = 1)
        val gateway = gateway(provider, manager(clock), clock)
        val events = mutableListOf<ModelStreamEvent>()

        val response = gateway.stream(request()) { events += it }

        assertEquals("ok", response.content)
        // Started + TextDelta from the successful attempt only.
        assertEquals(2, events.size)
    }
}
