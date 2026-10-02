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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RateLimitedModelGatewayTest {

    private class CountingProvider(
        override val id: String,
        private val rateLimitedTimes: Int = 0,
        private val retryAfterMillis: Long? = null,
        private val usage: ModelUsage? = null,
        private val rateLimitMessage: String = "429",
        private val providerErrorType: String? = null,
    ) : ModelProvider {
        var completeCalls = 0
        var streamCalls = 0

        override fun capabilities(modelId: String): ModelCapabilities =
            ModelCapabilities(streaming = true)

        override suspend fun complete(request: ModelRequest): ModelResponse {
            completeCalls += 1
            if (completeCalls <= rateLimitedTimes) throw rateLimited()
            return ModelResponse(model = request.model, providerId = id, content = "ok", usage = usage)
        }

        override suspend fun stream(
            request: ModelRequest,
            onEvent: (ModelStreamEvent) -> Unit,
        ): ModelResponse {
            streamCalls += 1
            if (streamCalls <= rateLimitedTimes) throw rateLimited()
            onEvent(ModelStreamEvent.Started(request.model, id))
            onEvent(ModelStreamEvent.TextDelta("ok"))
            return ModelResponse(model = request.model, providerId = id, content = "ok", usage = usage)
        }

        private fun rateLimited(): ModelProviderError = ModelProviderError(
            code = ModelProviderErrorCode.RATE_LIMITED,
            message = rateLimitMessage,
            providerId = id,
            httpStatus = 429,
            providerErrorType = providerErrorType,
            retryable = true,
            retryAfterMillis = retryAfterMillis,
        )
    }

    private fun config(
        providerId: String = "groq",
        providerType: ModelProviderType = ModelProviderType.REMOTE_OPENAI_COMPATIBLE,
        model: String = "llama-3.3-70b-versatile",
        baseUrl: String = "https://api.groq.com/openai/v1",
    ) = ModelConfig(
        providerId = providerId,
        baseUrl = baseUrl,
        model = model,
        metadata = mapOf("providerType" to providerType.name),
    )

    private fun gateway(provider: ModelProvider, manager: RateLimitManager): RateLimitedModelGateway {
        val base = DefaultModelGateway()
        base.register(provider)
        return RateLimitedModelGateway(delegate = base, rateLimits = manager)
    }

    private fun manager(clock: RateLimitClock = FakeRateLimitClock()) =
        DefaultRateLimitManager(clock = clock, tracker = DefaultUsageTracker())

    private fun request(config: ModelConfig = config()) =
        ModelRequest(config = config, messages = listOf(ModelMessage.user("hello")))

    @Test
    fun `a 429 is not retried and returns a structured rate-limit error`() = runSuspend {
        val provider = CountingProvider("groq", rateLimitedTimes = 100, retryAfterMillis = 3_000L)
        val limits = manager()
        val gateway = gateway(provider, limits)

        val error = assertFailsWith<ModelProviderError> { gateway.complete(request()) }

        assertEquals(ModelProviderErrorCode.RATE_LIMITED, error.code)
        assertEquals(false, error.retryable)
        assertEquals(3_000L, error.retryAfterMillis)
        assertEquals("llama-3.3-70b-versatile", error.details["modelId"])
        assertEquals(1, provider.completeCalls, "a 429 must not start a retry loop")

        val blocked = assertIs<RateLimitDecision.Blocked>(
            limits.canRequest("groq", "llama-3.3-70b-versatile", 1, 1),
        )
        assertEquals(3_000L, blocked.retryAfterMs)
    }

    @Test
    fun `missing retry-after still fails closed without retrying`() = runSuspend {
        val provider = CountingProvider("groq", rateLimitedTimes = 100, retryAfterMillis = null)
        val gateway = gateway(provider, manager())

        val error = assertFailsWith<ModelProviderError> { gateway.complete(request()) }

        assertEquals(ModelProviderErrorCode.RATE_LIMITED, error.code)
        assertEquals(false, error.retryable)
        assertNull(error.retryAfterMillis)
        assertEquals(1, provider.completeCalls)
    }

    @Test
    fun `a token-based 429 is recorded with token scope`() = runSuspend {
        val provider = CountingProvider(
            id = "groq",
            rateLimitedTimes = 1,
            retryAfterMillis = 1_500L,
            rateLimitMessage = "tokens per minute exceeded",
        )
        val limits = manager()
        val gateway = gateway(provider, limits)

        val error = assertFailsWith<ModelProviderError> { gateway.complete(request()) }

        assertEquals("TOKENS", error.details["scope"])
        val blocked = assertIs<RateLimitDecision.Blocked>(
            limits.canRequest("groq", "llama-3.3-70b-versatile", 1, 1),
        )
        assertEquals(RateLimitKind.TOKENS, blocked.kind)
        assertEquals(1_500L, blocked.retryAfterMs)
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
        val provider = CountingProvider("openai-compatible", rateLimitedTimes = 0)
        val limits = manager()
        limits.updateProfile(
            RateLimitProfile(
                providerId = "openai-compatible",
                requestsPerMinute = 0,
                source = RateLimitSource.APP_CONFIGURED,
            ),
        )
        val gateway = gateway(provider, limits)

        val response = gateway.complete(
            request(config("openai-compatible", ModelProviderType.LOCAL_PHONE, baseUrl = "http://127.0.0.1:8080/v1")),
        )

        assertEquals("ok", response.content)
        assertEquals(1, provider.completeCalls)
    }

    @Test
    fun `usage from the provider is recorded by the manager`() = runSuspend {
        val tracker = DefaultUsageTracker()
        val limits = DefaultRateLimitManager(tracker = tracker)
        val provider = CountingProvider("groq", usage = ModelUsage(totalTokens = 42))
        val gateway = gateway(provider, limits)

        gateway.complete(request())

        val totals = tracker.totals("groq", "llama-3.3-70b-versatile").single()
        assertEquals(1L, totals.requestCount)
        assertEquals(42L, totals.totalTokens)
    }

    @Test
    fun `streaming and non-streaming use the same manager`() = runSuspend {
        val tracker = DefaultUsageTracker()
        val limits = DefaultRateLimitManager(tracker = tracker)
        limits.updateProfile(
            RateLimitProfile(
                providerId = "groq",
                requestsPerMinute = 2,
                source = RateLimitSource.APP_CONFIGURED,
            ),
        )
        val provider = CountingProvider("groq")
        val gateway = gateway(provider, limits)

        gateway.complete(request())
        gateway.stream(request()) { }

        assertEquals(1, provider.completeCalls)
        assertEquals(1, provider.streamCalls)
        assertEquals(2L, tracker.totals("groq", "llama-3.3-70b-versatile").single().requestCount)

        val error = assertFailsWith<ModelProviderError> { gateway.complete(request()) }
        assertEquals(ModelProviderErrorCode.RATE_LIMITED, error.code)
        assertEquals(1, provider.completeCalls, "blocked admission must not call the provider")
    }

    @Test
    fun `gemini groq and local openai-compatible still use the gateway path`() = runSuspend {
        val gemini = CountingProvider("gemini")
        val groq = CountingProvider("groq")
        val local = CountingProvider("openai-compatible")
        val limits = manager()
        val base = DefaultModelGateway()
        base.register(gemini)
        base.register(groq)
        base.register(local)
        val gateway = RateLimitedModelGateway(base, limits)

        gateway.complete(
            request(
                config(
                    "gemini",
                    ModelProviderType.CUSTOM,
                    "gemini-2.5-flash",
                    "https://generativelanguage.googleapis.com/v1beta",
                ),
            ),
        )
        gateway.complete(request(config("groq")))
        gateway.complete(
            request(config("openai-compatible", ModelProviderType.LOCAL_PHONE, "qwen", "http://127.0.0.1:8080/v1")),
        )

        assertEquals(1, gemini.completeCalls)
        assertEquals(1, groq.completeCalls)
        assertEquals(1, local.completeCalls)
    }
}
