package com.agentx.app.agent.model

import com.agentx.app.agent.domain.AgentEvent
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.domain.CollectingEventSink
import com.agentx.app.agent.domain.FallbackFailureCategory
import com.agentx.app.agent.domain.ModelFallbackReason
import com.agentx.app.core.logging.ForgeLogger
import com.agentx.app.core.logging.ForgeLoggers
import com.agentx.app.core.logging.LogLevel
import com.agentx.app.core.logging.LogRecord
import com.agentx.app.core.logging.LogSink
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
import com.agentx.app.model.capability.InMemoryModelCapabilityRegistry
import com.agentx.app.model.error.ProviderErrorClassifier
import com.agentx.app.model.ratelimit.DefaultRateLimitManager
import com.agentx.app.model.ratelimit.RateLimitClock
import com.agentx.app.model.ratelimit.RateLimitedModelGateway
import com.agentx.app.model.retry.RetryVerdict
import com.agentx.app.model.retry.TransientRetryPolicy
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The seam between the two recovery mechanisms, which is the whole point of this
 * phase:
 *
 * ```text
 * request → attempt → transient? → retry the SAME model (bounded)
 *                  → retry budget spent → throw
 *                  → fall back to a CONFIGURED candidate, if the policy allows
 *                  → otherwise → structured failure
 * ```
 *
 * Retry runs inside the gateway, below the fallback layer, so "retry first, and only
 * then fall back" is the only order that can happen. These tests pin that order, that
 * an exhausted retry is what the chain sees, and that an unconfigured role never has
 * a model chosen for it.
 */
class RetryFallbackBoundaryTest {

    /** Records each backoff instead of waiting, so the tests stay instant. */
    private class TestClock : RateLimitClock {
        val sleeps = mutableListOf<Long>()
        override fun nowMillis(): Long = 0L
        override suspend fun sleep(millis: Long) { sleeps += millis }
    }

    private class CapturingSink : LogSink {
        val records = mutableListOf<LogRecord>()
        override fun write(record: LogRecord) { records += record }
    }

    private fun logger(sink: LogSink): ForgeLogger = ForgeLoggers.create(LogLevel.INFO, sink)

    private class CountingProvider(
        override val id: String,
        private val failWith: (() -> Throwable)? = null,
        private val content: String = "ok",
    ) : ModelProvider {
        var calls = 0

        override fun capabilities(modelId: String): ModelCapabilities =
            ModelCapabilities(streaming = true, toolCalling = true)

        override suspend fun complete(request: ModelRequest): ModelResponse {
            calls += 1
            failWith?.let { throw it() }
            return ModelResponse(model = request.model, providerId = id, content = content)
        }

        override suspend fun stream(
            request: ModelRequest,
            onEvent: (ModelStreamEvent) -> Unit,
        ): ModelResponse = complete(request)
    }

    private fun config(provider: String, model: String) = ModelConfig(
        providerId = provider,
        baseUrl = "https://$provider.example/v1",
        model = model,
        capabilities = ModelCapabilities(toolCalling = true, streaming = true, enabled = true),
    )

    private val primary = config("gemini", "gemini-x")
    private val groq = config("groq", "groq-y")

    private fun timeout() = ModelProviderError(
        code = ModelProviderErrorCode.TIMEOUT,
        message = "the model request timed out",
        providerId = "gemini",
        httpStatus = 408,
    )

    private fun policy(vararg chain: RoleModelPreference) = ModelFallbackPolicy(
        automaticFallback = true,
        maxFallbackAttempts = ModelFallbackPolicy.DEFAULT_MAX_FALLBACK_ATTEMPTS,
        fallbacksByRole = mapOf(AgentRole.MAIN to chain.toList()),
    )

    private fun resolver(connections: Map<String, ModelConfig>, limits: DefaultRateLimitManager) =
        AgentModelResolver(
            connections = { connections },
            capabilityRegistry = InMemoryModelCapabilityRegistry(),
            rateLimitManager = limits,
        )

    private fun request(config: ModelConfig) =
        ModelRequest(config = config, messages = listOf(ModelMessage.user("hi")))

    // --- J. retry then fallback ---------------------------------------------

    @Test
    fun `an exhausted retry is what the configured fallback follows`() = runBlocking {
        val limits = DefaultRateLimitManager(clock = TestClock())
        val wait = TestClock()
        val logs = CapturingSink()
        val base = DefaultModelGateway(InMemoryModelCapabilityRegistry())
        val primaryProvider = CountingProvider("gemini", failWith = { timeout() })
        val candidateProvider = CountingProvider("groq", content = "fallback")
        base.register(primaryProvider)
        base.register(candidateProvider)

        val gateway = RateLimitedModelGateway(
            delegate = base,
            rateLimits = limits,
            retry = TransientRetryPolicy(maximumAttempts = 3),
            wait = wait,
            logger = logger(logs),
        )
        val sink = CollectingEventSink()
        val fallback = ModelFallback(
            policy = { policy(RoleModelPreference("groq")) },
            resolver = resolver(mapOf("groq" to groq), limits),
            logger = logger(logs),
        )

        val result = fallback.execute(
            role = AgentRole.MAIN,
            sessionId = "s",
            primary = primary,
            sink = sink,
        ) { target -> gateway.complete(request(target)).content }

        assertEquals("fallback", result)
        // The same model was retried to its bound first: a fallback is never started
        // before the retry budget is spent.
        assertEquals(3, primaryProvider.calls, "the primary is retried to its bound")
        assertEquals(2, wait.sleeps.size, "one backoff between each pair of attempts")
        assertEquals(1, candidateProvider.calls, "the candidate runs once the retries are spent")

        val started = assertIs<AgentEvent.ModelFallbackStarted>(
            sink.events.first { it is AgentEvent.ModelFallbackStarted },
        )
        assertEquals(ModelFallbackReason.TIMEOUT, started.reason)
        assertEquals("gemini", started.fromProviderId)
        assertEquals("groq", started.toProviderId)

        // The log stream says the chain began *after* retries, and how many: that is
        // what makes "attempts 1-3 timed out, then we fell back" legible rather than
        // implied by the call counts alone.
        val chainStart = logs.records.first { it.message == "Model fallback started" }
        assertEquals(3, chainStart.fields["retryAttempts"])
        assertEquals(3, chainStart.fields["retryMaxAttempts"])
        assertEquals(RetryVerdict.ATTEMPTS_EXHAUSTED.name, chainStart.fields["retryOutcome"])
        assertEquals(true, chainStart.fields["retriesExhausted"])
    }

    // --- K. retry without fallback ------------------------------------------

    @Test
    fun `with no fallback configured an exhausted retry fails and selects no model`() = runBlocking {
        val limits = DefaultRateLimitManager(clock = TestClock())
        val wait = TestClock()
        val base = DefaultModelGateway(InMemoryModelCapabilityRegistry())
        val primaryProvider = CountingProvider("gemini", failWith = { timeout() })
        val candidateProvider = CountingProvider("groq", content = "fallback")
        base.register(primaryProvider)
        base.register(candidateProvider)

        val gateway = RateLimitedModelGateway(
            delegate = base,
            rateLimits = limits,
            retry = TransientRetryPolicy(maximumAttempts = 3),
            wait = wait,
        )
        val sink = CollectingEventSink()
        val fallback = ModelFallback(policy = { ModelFallbackPolicy.DISABLED }, resolver = resolver(emptyMap(), limits))

        val error = assertFailsWith<ModelProviderError> {
            fallback.execute(role = AgentRole.MAIN, sessionId = "s", primary = primary, sink = sink) { target ->
                gateway.complete(request(target)).content
            }
        }

        assertEquals(ModelProviderErrorCode.TIMEOUT, error.code)
        assertEquals(3, primaryProvider.calls)
        assertEquals(0, candidateProvider.calls, "an unconfigured role must not have a model chosen for it")
        assertTrue(sink.events.none { it is AgentEvent.ModelFallbackStarted || it is AgentEvent.ModelFallbackSucceeded })
        assertEquals(3, error.details["attempts"])
        assertEquals(RetryVerdict.ATTEMPTS_EXHAUSTED.name, error.details["retryOutcome"])
    }

    @Test
    fun `a declared but unconnected candidate is never substituted after retries`() = runBlocking {
        val limits = DefaultRateLimitManager(clock = TestClock())
        val wait = TestClock()
        val base = DefaultModelGateway(InMemoryModelCapabilityRegistry())
        val primaryProvider = CountingProvider("gemini", failWith = { timeout() })
        base.register(primaryProvider)

        val gateway = RateLimitedModelGateway(
            delegate = base,
            rateLimits = limits,
            retry = TransientRetryPolicy(maximumAttempts = 2),
            wait = wait,
            logger = logger(CapturingSink()),
        )
        val sink = CollectingEventSink()
        // The chain names groq, but no groq connection exists: the retry bound is still
        // honoured and the request then fails rather than being answered elsewhere.
        val fallback = ModelFallback(
            policy = { policy(RoleModelPreference("groq")) },
            resolver = resolver(emptyMap(), limits),
            logger = logger(CapturingSink()),
        )

        val error = assertFailsWith<ModelProviderError> {
            fallback.execute(role = AgentRole.MAIN, sessionId = "s", primary = primary, sink = sink) { target ->
                gateway.complete(request(target)).content
            }
        }

        assertEquals(ModelProviderErrorCode.TIMEOUT, error.code)
        assertEquals(2, primaryProvider.calls)
        assertTrue(sink.events.none { it is AgentEvent.ModelFallbackStarted })
    }

    // --- the cross-layer invariant ------------------------------------------

    @Test
    fun `everything worth retrying is also something another model could answer`() {
        // The two taxonomies are separate on purpose — one is about repeating a
        // request, the other about answering it elsewhere — but they must not
        // contradict each other. A failure the retry layer considers transient is by
        // definition a fault of the attempt, not of the request, so a configured
        // fallback must be allowed to pick it up.
        val statuses = mapOf(
            ModelProviderErrorCode.TIMEOUT to 408,
            ModelProviderErrorCode.SERVER_ERROR to 500,
            ModelProviderErrorCode.SERVICE_UNAVAILABLE to 503,
            ModelProviderErrorCode.PROVIDER_ERROR to 500,
        )

        ModelProviderErrorCode.entries.forEach { code ->
            val status = statuses[code]
            if (!ProviderErrorClassifier.isTransient(code, status)) return@forEach
            val category = FallbackFailureCategory.of(
                ModelProviderError(code = code, message = "t", httpStatus = status),
            )
            assertTrue(
                category.fallbackEligible,
                "$code is retryable, so a configured fallback must be able to follow it (was $category)",
            )
        }
    }

    @Test
    fun `a rate limit is retry-ineligible but still fallback-eligible`() {
        // The boundary stated as one assertion: a 429 is not repeated against the same
        // model (the manager owns its cooldown), yet it remains exactly what the P1-3
        // fallback policy is allowed to move away from.
        val rateLimited = ModelProviderError(
            code = ModelProviderErrorCode.RATE_LIMITED,
            message = "slow down",
            httpStatus = 429,
        )

        assertTrue(!ProviderErrorClassifier.isTransient(ModelProviderErrorCode.RATE_LIMITED, 429))
        assertTrue(FallbackFailureCategory.of(rateLimited).fallbackEligible)
        assertEquals(ModelFallbackReason.RATE_LIMITED, ModelFallbackErrors.triggerFor(rateLimited))
    }
}
