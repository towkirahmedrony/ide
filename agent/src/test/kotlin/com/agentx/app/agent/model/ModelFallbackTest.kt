package com.agentx.app.agent.model

import com.agentx.app.agent.catalog.AgentCatalog
import com.agentx.app.agent.domain.AgentEvent
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.domain.AgentStatus
import com.agentx.app.agent.domain.CollectingEventSink
import com.agentx.app.agent.domain.ModelFallbackReason
import com.agentx.app.agent.domain.PermissionLevel
import com.agentx.app.agent.protocol.AgentProtocol
import com.agentx.app.agent.runtime.AgentLoop
import com.agentx.app.agent.runtime.AgentLoopRequest
import com.agentx.app.agent.tools.AgentToolBridge
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
import com.agentx.app.model.ratelimit.DefaultRateLimitManager
import com.agentx.app.model.ratelimit.RateLimitDecision
import com.agentx.app.model.ratelimit.RateLimitProfile
import com.agentx.app.model.ratelimit.RateLimitSource
import com.agentx.app.model.ratelimit.RateLimitedModelGateway
import com.agentx.app.tools.DefaultToolRegistry
import com.agentx.app.tools.DefaultToolRouter
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Controlled, capability- and quota-aware model fallback.
 *
 * The fallback layer is exercised against the real [AgentModelResolver]
 * eligibility system and the real [DefaultRateLimitManager]; model traffic is a
 * fake provider or a plain lambda, so no network request is made.
 */
class ModelFallbackTest {

    // --- fixtures ----------------------------------------------------------

    private fun config(
        provider: String,
        model: String,
        baseUrl: String = "https://$provider.example/v1",
        toolCalling: Boolean = true,
        streaming: Boolean = true,
        local: Boolean = false,
        enabled: Boolean = true,
        stream: Boolean = false,
    ) = ModelConfig(
        providerId = provider,
        baseUrl = baseUrl,
        model = model,
        stream = stream,
        capabilities = ModelCapabilities(
            toolCalling = toolCalling,
            streaming = streaming,
            local = local,
            enabled = enabled,
        ),
    )

    private fun failure(
        code: ModelProviderErrorCode,
        status: Int? = null,
        retryAfterMillis: Long? = null,
    ) = ModelProviderError(
        code = code,
        message = code.name,
        providerId = "test",
        httpStatus = status,
        retryable = true,
        retryAfterMillis = retryAfterMillis,
    )

    private fun manager(vararg profiles: RateLimitProfile): DefaultRateLimitManager {
        val manager = DefaultRateLimitManager()
        runBlocking { profiles.forEach { manager.updateProfile(it) } }
        return manager
    }

    private fun blocked(providerId: String, modelId: String? = null) = RateLimitProfile(
        providerId = providerId,
        modelId = modelId,
        requestsPerMinute = 0,
        source = RateLimitSource.APP_CONFIGURED,
    )

    private fun resolver(
        connections: Map<String, ModelConfig>,
        rateLimits: DefaultRateLimitManager? = null,
    ) = AgentModelResolver(
        connections = { connections },
        capabilityRegistry = InMemoryModelCapabilityRegistry(),
        rateLimitManager = rateLimits,
    )

    private fun policy(
        vararg chain: RoleModelPreference,
        role: AgentRole = AgentRole.MAIN,
        maxAttempts: Int = ModelFallbackPolicy.DEFAULT_MAX_FALLBACK_ATTEMPTS,
    ) = ModelFallbackPolicy(
        automaticFallback = true,
        maxFallbackAttempts = maxAttempts,
        fallbacksByRole = mapOf(role to chain.toList()),
    )

    /** Records every configuration the fallback layer actually called. */
    private class Calls(
        private val behaviour: (ModelConfig) -> String,
    ) {
        val attempted = mutableListOf<ModelConfig>()

        suspend fun invoke(config: ModelConfig): String {
            attempted += config
            return behaviour(config)
        }

        fun providers(): List<String> = attempted.map { it.providerId }
    }

    private val primary = config("gemini", "gemini-x")
    private val groq = config("groq", "groq-y")
    private val cerebras = config("cerebras", "cerebras-z")

    // --- 1. disabled policy ------------------------------------------------

    @Test
    fun `fallback disabled returns the original failure and switches no model`() = runBlocking {
        val sink = CollectingEventSink()
        // The chain is declared, but automaticFallback is off: only the primary may run.
        val calls = Calls { throw failure(ModelProviderErrorCode.NETWORK_ERROR) }
        val fallback = ModelFallback(
            policy = { ModelFallbackPolicy.DISABLED.withFallbacks(AgentRole.MAIN, listOf(RoleModelPreference("groq"))) },
            resolver = resolver(mapOf("groq" to groq)),
        )

        val thrown = assertFailsWith<ModelProviderError> {
            fallback.execute(
                role = AgentRole.MAIN,
                sessionId = "s",
                primary = primary,
                sink = sink,
                call = calls::invoke,
            )
        }
        assertEquals(ModelProviderErrorCode.NETWORK_ERROR, thrown.code)
        assertEquals(listOf("gemini"), calls.providers())
        assertTrue(sink.events.none { it is AgentEvent.ModelFallbackStarted })
    }

    @Test
    fun `automatic fallback is off by default`() {
        val policy = ModelFallbackPolicy.DISABLED
        assertTrue(!policy.automaticFallback)
        assertTrue(policy.fallbacksByRole.isEmpty())
        assertEquals(2, ModelFallbackPolicy.DEFAULT_MAX_FALLBACK_ATTEMPTS)
        assertTrue(!policy.enabledFor(AgentRole.MAIN))
    }

    // --- 2 / 14. eligible fallback selected --------------------------------

    @Test
    fun `an enabled policy selects the first eligible candidate and reports it`() = runBlocking {
        val sink = CollectingEventSink()
        val calls = Calls { if (it.providerId == "gemini") throw failure(ModelProviderErrorCode.NETWORK_ERROR) else "ok:${it.providerId}" }
        val fallback = ModelFallback(
            policy = { policy(RoleModelPreference("groq"), RoleModelPreference("cerebras")) },
            resolver = resolver(mapOf("groq" to groq, "cerebras" to cerebras)),
        )

        val result = fallback.execute(
            role = AgentRole.MAIN, sessionId = "s", primary = primary, sink = sink, call = calls::invoke,
        )

        assertEquals("ok:groq", result)
        assertEquals(listOf("gemini", "groq"), calls.providers())
        val started = assertIs<AgentEvent.ModelFallbackStarted>(
            sink.events.first { it is AgentEvent.ModelFallbackStarted },
        )
        assertEquals("gemini", started.fromProviderId)
        assertEquals("gemini-x", started.fromModelId)
        assertEquals("groq", started.toProviderId)
        assertEquals("groq-y", started.toModelId)
        assertEquals(ModelFallbackReason.NETWORK_FAILURE, started.reason)
        assertEquals(1, started.attempt)
        assertTrue(sink.events.any { it is AgentEvent.ModelFallbackSucceeded && it.toProviderId == "groq" })
    }

    // --- 3. 429 ------------------------------------------------------------

    @Test
    fun `a 429 records the primary and evaluates the fallback while preserving retry-after`() = runBlocking {
        val limits = DefaultRateLimitManager()
        val base = DefaultModelGateway(InMemoryModelCapabilityRegistry())
        var geminiCalls = 0
        base.register(provider("gemini") { geminiCalls += 1; throw failure(ModelProviderErrorCode.RATE_LIMITED, 429, 2_000L) })
        base.register(provider("groq") { ModelResponse(model = "groq-y", providerId = "groq", content = "fallback") })
        val gateway = RateLimitedModelGateway(base, limits)

        val sink = CollectingEventSink()
        val fallback = ModelFallback(
            policy = { policy(RoleModelPreference("groq")) },
            resolver = resolver(mapOf("groq" to groq), rateLimits = limits),
        )
        val result = fallback.execute(
            role = AgentRole.MAIN,
            sessionId = "s",
            primary = primary,
            sink = sink,
            call = { gateway.complete(ModelRequest(it, listOf(ModelMessage.user("hi")))).content },
        )

        assertEquals("fallback", result)
        // The same rate-limited model is never retried immediately.
        assertEquals(1, geminiCalls)
        // Retry-After from the 429 is preserved on the primary's block state.
        val blockedDecision = assertIs<RateLimitDecision.Blocked>(
            limits.canRequest("gemini", "gemini-x", 1, 1),
        )
        assertTrue((blockedDecision.retryAfterMs ?: 0L) > 0L)
        val started = assertIs<AgentEvent.ModelFallbackStarted>(
            sink.events.first { it is AgentEvent.ModelFallbackStarted },
        )
        assertEquals(ModelFallbackReason.RATE_LIMITED, started.reason)
    }

    // --- 4-6. temporary failures ------------------------------------------

    @Test
    fun `a timeout allows fallback`() = runBlocking {
        assertFallsBack(failure(ModelProviderErrorCode.TIMEOUT), ModelFallbackReason.TIMEOUT)
    }

    @Test
    fun `a network failure allows fallback`() = runBlocking {
        assertFallsBack(failure(ModelProviderErrorCode.NETWORK_ERROR), ModelFallbackReason.NETWORK_FAILURE)
    }

    @Test
    fun `a connection failure allows fallback`() = runBlocking {
        assertFallsBack(failure(ModelProviderErrorCode.CONNECTION_FAILED), ModelFallbackReason.NETWORK_FAILURE)
    }

    @Test
    fun `a provider 5xx allows fallback`() = runBlocking {
        assertFallsBack(failure(ModelProviderErrorCode.PROVIDER_ERROR, 503), ModelFallbackReason.PROVIDER_UNAVAILABLE)
    }

    private suspend fun assertFallsBack(primaryFailure: ModelProviderError, expected: ModelFallbackReason) {
        val sink = CollectingEventSink()
        val calls = Calls { if (it.providerId == "gemini") throw primaryFailure else "ok:${it.providerId}" }
        val fallback = ModelFallback(
            policy = { policy(RoleModelPreference("groq")) },
            resolver = resolver(mapOf("groq" to groq)),
        )
        val result = fallback.execute(
            role = AgentRole.MAIN, sessionId = "s", primary = primary, sink = sink, call = calls::invoke,
        )
        assertEquals("ok:groq", result)
        val started = assertIs<AgentEvent.ModelFallbackStarted>(
            sink.events.first { it is AgentEvent.ModelFallbackStarted },
        )
        assertEquals(expected, started.reason)
    }

    // --- 5 / 7-8. permanent errors ----------------------------------------

    @Test
    fun `an invalid api key never triggers fallback`() = runBlocking {
        assertNoFallback(failure(ModelProviderErrorCode.AUTHENTICATION_FAILED, 401))
    }

    @Test
    fun `an invalid model never triggers fallback`() = runBlocking {
        assertNoFallback(failure(ModelProviderErrorCode.PROVIDER_ERROR, 404))
        assertNoFallback(failure(ModelProviderErrorCode.INVALID_REQUEST, 400))
    }

    @Test
    fun `an invalid configuration never triggers fallback`() = runBlocking {
        assertNoFallback(failure(ModelProviderErrorCode.INVALID_CONFIG))
    }

    @Test
    fun `an unsupported capability on the primary never triggers fallback`() = runBlocking {
        assertNoFallback(failure(ModelProviderErrorCode.UNSUPPORTED))
    }

    @Test
    fun `a non-provider exception never triggers fallback`() = runBlocking {
        val sink = CollectingEventSink()
        val attempts = mutableListOf<ModelConfig>()
        val fallback = ModelFallback(
            policy = { policy(RoleModelPreference("groq")) },
            resolver = resolver(mapOf("groq" to groq)),
        )
        assertFailsWith<IllegalStateException> {
            fallback.execute(role = AgentRole.MAIN, sessionId = "s", primary = primary, sink = sink) {
                attempts += it
                throw IllegalStateException("boom")
            }
        }
        assertEquals(listOf("gemini"), attempts.map { it.providerId })
        assertTrue(sink.events.none { it is AgentEvent.ModelFallbackStarted })
    }

    private suspend fun assertNoFallback(primaryFailure: ModelProviderError) {
        val sink = CollectingEventSink()
        val calls = Calls { "unexpected" }
        val fallback = ModelFallback(
            policy = { policy(RoleModelPreference("groq"), RoleModelPreference("cerebras")) },
            resolver = resolver(mapOf("groq" to groq, "cerebras" to cerebras)),
        )
        val thrown = assertFailsWith<ModelProviderError> {
            fallback.execute(
                role = AgentRole.MAIN,
                sessionId = "s",
                primary = primary,
                sink = sink,
                call = { calls.attempted += it; throw primaryFailure },
            )
        }
        assertEquals(primaryFailure.code, thrown.code)
        assertEquals(listOf("gemini"), calls.providers())
        assertTrue(sink.events.none { it is AgentEvent.ModelFallbackStarted })
    }

    // --- 11. tool-call safety ---------------------------------------------

    @Test
    fun `a candidate without tool calling is rejected and never called`() = runBlocking {
        val noTools = config("groq", "groq-y", toolCalling = false)
        val sink = CollectingEventSink()
        val calls = Calls { "unexpected" }
        val fallback = ModelFallback(
            policy = { policy(RoleModelPreference("groq"), role = AgentRole.CODER) },
            resolver = resolver(mapOf("groq" to noTools)),
        )
        val thrown = assertFailsWith<ModelProviderError> {
            fallback.execute(
                role = AgentRole.CODER,
                sessionId = "s",
                primary = primary,
                sink = sink,
                call = { calls.attempted += it; throw failure(ModelProviderErrorCode.NETWORK_ERROR) },
            )
        }
        assertEquals(ModelProviderErrorCode.NETWORK_ERROR, thrown.code)
        assertEquals(listOf("gemini"), calls.providers())
        assertTrue(sink.events.none { it is AgentEvent.ModelFallbackStarted })
    }

    // --- 10 / 12. eligibility of candidates --------------------------------

    @Test
    fun `an already rate-limited candidate is skipped`() = runBlocking {
        val limits = manager(blocked("groq"))
        val sink = CollectingEventSink()
        val calls = Calls { "unexpected" }
        val fallback = ModelFallback(
            policy = { policy(RoleModelPreference("groq"), RoleModelPreference("cerebras")) },
            resolver = resolver(mapOf("groq" to groq, "cerebras" to cerebras), rateLimits = limits),
        )
        val result = fallback.execute(
            role = AgentRole.MAIN,
            sessionId = "s",
            primary = primary,
            sink = sink,
            call = { calls.attempted += it; if (it.providerId == "gemini") throw failure(ModelProviderErrorCode.NETWORK_ERROR) else "ok:${it.providerId}" },
        )
        assertEquals("ok:cerebras", result)
        assertEquals(listOf("gemini", "cerebras"), calls.providers())
    }

    @Test
    fun `a disabled candidate is skipped`() = runBlocking {
        val disabled = config("groq", "groq-y", enabled = false)
        val sink = CollectingEventSink()
        val calls = Calls { if (it.providerId == "gemini") "unexpected" else "ok:${it.providerId}" }
        val fallback = ModelFallback(
            policy = { policy(RoleModelPreference("groq"), RoleModelPreference("cerebras")) },
            resolver = resolver(mapOf("groq" to disabled, "cerebras" to cerebras)),
        )
        val result = fallback.execute(
            role = AgentRole.MAIN,
            sessionId = "s",
            primary = primary,
            sink = sink,
            call = { calls.attempted += it; if (it.providerId == "gemini") throw failure(ModelProviderErrorCode.NETWORK_ERROR) else "ok:${it.providerId}" },
        )
        assertEquals("ok:cerebras", result)
        assertEquals(listOf("gemini", "cerebras"), calls.providers())
    }

    @Test
    fun `a duplicate candidate is never tried twice`() = runBlocking {
        val sink = CollectingEventSink()
        val calls = Calls { if (it.providerId == "gemini") throw failure(ModelProviderErrorCode.NETWORK_ERROR) else throw failure(ModelProviderErrorCode.NETWORK_ERROR) }
        val fallback = ModelFallback(
            policy = { policy(RoleModelPreference("groq"), RoleModelPreference("groq")) },
            resolver = resolver(mapOf("groq" to groq)),
        )
        assertFailsWith<ModelProviderError> {
            fallback.execute(role = AgentRole.MAIN, sessionId = "s", primary = primary, sink = sink, call = calls::invoke)
        }
        assertEquals(listOf("gemini", "groq"), calls.providers())
    }

    @Test
    fun `a candidate whose provider is not connected is skipped`() = runBlocking {
        val sink = CollectingEventSink()
        val calls = Calls { "unexpected" }
        val fallback = ModelFallback(
            policy = { policy(RoleModelPreference("ghost"), RoleModelPreference("groq")) },
            resolver = resolver(mapOf("groq" to groq)),
        )
        val result = fallback.execute(
            role = AgentRole.MAIN,
            sessionId = "s",
            primary = primary,
            sink = sink,
            call = { calls.attempted += it; if (it.providerId == "gemini") throw failure(ModelProviderErrorCode.NETWORK_ERROR) else "ok:${it.providerId}" },
        )
        assertEquals("ok:groq", result)
        assertEquals(listOf("gemini", "groq"), calls.providers())
    }

    // --- 8. bounded attempts ----------------------------------------------

    @Test
    fun `fallback attempts are bounded by the configured maximum`() = runBlocking {
        val sink = CollectingEventSink()
        val calls = Calls { throw failure(ModelProviderErrorCode.NETWORK_ERROR) }
        val fallback = ModelFallback(
            policy = { policy(RoleModelPreference("groq"), RoleModelPreference("cerebras"), maxAttempts = 1) },
            resolver = resolver(mapOf("groq" to groq, "cerebras" to cerebras)),
        )
        assertFailsWith<ModelProviderError> {
            fallback.execute(role = AgentRole.MAIN, sessionId = "s", primary = primary, sink = sink, call = calls::invoke)
        }
        assertEquals(listOf("gemini", "groq"), calls.providers())
        val exhausted = assertIs<AgentEvent.ModelFallbackExhausted>(
            sink.events.first { it is AgentEvent.ModelFallbackExhausted },
        )
        assertEquals(1, exhausted.attempts)
    }

    // --- 15. exhaustion ----------------------------------------------------

    @Test
    fun `all candidates failing reports exhaustion and returns the last failure`() = runBlocking {
        val sink = CollectingEventSink()
        val calls = Calls { if (it.providerId == "gemini") throw failure(ModelProviderErrorCode.TIMEOUT) else throw failure(ModelProviderErrorCode.PROVIDER_ERROR, 500) }
        val fallback = ModelFallback(
            policy = { policy(RoleModelPreference("groq"), RoleModelPreference("cerebras")) },
            resolver = resolver(mapOf("groq" to groq, "cerebras" to cerebras)),
        )
        val thrown = assertFailsWith<ModelProviderError> {
            fallback.execute(role = AgentRole.MAIN, sessionId = "s", primary = primary, sink = sink, call = calls::invoke)
        }
        assertEquals(ModelProviderErrorCode.PROVIDER_ERROR, thrown.code)
        assertEquals(listOf("gemini", "groq", "cerebras"), calls.providers())
        val exhausted = assertIs<AgentEvent.ModelFallbackExhausted>(
            sink.events.first { it is AgentEvent.ModelFallbackExhausted },
        )
        assertEquals(2, exhausted.attempts)
        assertEquals("cerebras", exhausted.providerId)
    }

    // --- 10. streaming safety ---------------------------------------------

    @Test
    fun `a streaming failure before any output may fall back`() = runBlocking {
        val sink = CollectingEventSink()
        val calls = Calls { "unexpected" }
        val fallback = ModelFallback(
            policy = { policy(RoleModelPreference("groq")) },
            resolver = resolver(mapOf("groq" to groq)),
        )
        var produced = false
        val result = fallback.execute(
            role = AgentRole.MAIN,
            sessionId = "s",
            primary = primary,
            sink = sink,
            outputProduced = { produced },
            call = { calls.attempted += it; if (it.providerId == "gemini") throw failure(ModelProviderErrorCode.NETWORK_ERROR) else "ok:groq" },
        )
        assertEquals("ok:groq", result)
        assertEquals(listOf("gemini", "groq"), calls.providers())
    }

    @Test
    fun `a streaming failure after output never starts another model`() = runBlocking {
        val sink = CollectingEventSink()
        val calls = Calls { "unexpected" }
        val fallback = ModelFallback(
            policy = { policy(RoleModelPreference("groq")) },
            resolver = resolver(mapOf("groq" to groq)),
        )
        var produced = false
        val thrown = assertFailsWith<ModelProviderError> {
            fallback.execute(
                role = AgentRole.MAIN,
                sessionId = "s",
                primary = primary,
                sink = sink,
                outputProduced = { produced },
                call = {
                    calls.attempted += it
                    produced = true
                    throw failure(ModelProviderErrorCode.NETWORK_ERROR)
                },
            )
        }
        assertEquals(ModelProviderErrorCode.NETWORK_ERROR, thrown.code)
        assertEquals(listOf("gemini"), calls.providers())
        assertTrue(sink.events.none { it is AgentEvent.ModelFallbackStarted })
    }

    // --- local quotas ------------------------------------------------------

    @Test
    fun `a local candidate is not blocked by remote quota`() = runBlocking {
        val local = config("local", "qwen", baseUrl = "http://127.0.0.1:11434/v1", local = true)
        val limits = manager(blocked("local"))
        val sink = CollectingEventSink()
        val calls = Calls { "unexpected" }
        val fallback = ModelFallback(
            policy = { policy(RoleModelPreference("local"), role = AgentRole.CODER) },
            resolver = resolver(mapOf("local" to local), rateLimits = limits),
        )
        val result = fallback.execute(
            role = AgentRole.CODER,
            sessionId = "s",
            primary = primary,
            sink = sink,
            call = { calls.attempted += it; if (it.providerId == "gemini") throw failure(ModelProviderErrorCode.NETWORK_ERROR) else "ok:${it.providerId}" },
        )
        assertEquals("ok:local", result)
        assertEquals(listOf("gemini", "local"), calls.providers())
    }

    // --- integration through the agent loop --------------------------------

    @Test
    fun `the agent loop sends the same request and tools to the fallback candidate`() = runBlocking {
        val sink = CollectingEventSink()
        val gemini = RecordingProvider("gemini") { throw failure(ModelProviderErrorCode.NETWORK_ERROR) }
        val groqProvider = RecordingProvider("groq") { ModelResponse(model = "groq-y", providerId = "groq", content = "done") }
        val gateway = DefaultModelGateway(InMemoryModelCapabilityRegistry()).also {
            it.register(gemini)
            it.register(groqProvider)
        }
        val fallback = ModelFallback(
            policy = { policy(RoleModelPreference("groq")) },
            resolver = resolver(mapOf("groq" to groq)),
        )
        val result = loop(gateway, fallback).run(
            request = request(primary.copy(stream = false)),
            sink = sink,
            onCancelled = { false },
        )

        assertEquals(AgentStatus.COMPLETED, result.status)
        assertEquals("done", result.summary)
        val fromPrimary = gemini.requests.single()
        val toCandidate = groqProvider.requests.single()
        assertEquals(fromPrimary.messages, toCandidate.messages)
        assertEquals(fromPrimary.tools, toCandidate.tools)
        assertEquals(ModelFallbackReason.NETWORK_FAILURE, (sink.events.first { it is AgentEvent.ModelFallbackStarted } as AgentEvent.ModelFallbackStarted).reason)
    }

    @Test
    fun `the agent loop does not fall back after streaming output has started`() = runBlocking {
        val sink = CollectingEventSink()
        val gemini = StreamingProvider("gemini", content = "partial", afterOutput = failure(ModelProviderErrorCode.NETWORK_ERROR))
        val groqProvider = StreamingProvider("groq", content = "fallback")
        val gateway = DefaultModelGateway(InMemoryModelCapabilityRegistry()).also {
            it.register(gemini)
            it.register(groqProvider)
        }
        val fallback = ModelFallback(
            policy = { policy(RoleModelPreference("groq")) },
            resolver = resolver(mapOf("groq" to groq.copy(stream = true))),
        )
        val result = loop(gateway, fallback).run(
            request = request(primary.copy(stream = true)),
            sink = sink,
            onCancelled = { false },
        )

        assertEquals(AgentStatus.FAILED, result.status)
        assertEquals(1, gemini.streamCalls)
        assertEquals(0, groqProvider.streamCalls)
        assertTrue(sink.events.none { it is AgentEvent.ModelFallbackStarted })
    }

    @Test
    fun `the agent loop falls back when streaming fails before any output`() = runBlocking {
        val sink = CollectingEventSink()
        val gemini = StreamingProvider("gemini", content = "", beforeOutput = failure(ModelProviderErrorCode.NETWORK_ERROR))
        val groqProvider = StreamingProvider("groq", content = "fallback ok")
        val gateway = DefaultModelGateway(InMemoryModelCapabilityRegistry()).also {
            it.register(gemini)
            it.register(groqProvider)
        }
        val fallback = ModelFallback(
            policy = { policy(RoleModelPreference("groq")) },
            resolver = resolver(mapOf("groq" to groq.copy(stream = true))),
        )
        val result = loop(gateway, fallback).run(
            request = request(primary.copy(stream = true)),
            sink = sink,
            onCancelled = { false },
        )

        assertEquals(AgentStatus.COMPLETED, result.status)
        assertEquals("fallback ok", result.summary)
        assertEquals(1, gemini.streamCalls)
        assertEquals(1, groqProvider.streamCalls)
        assertTrue(sink.events.any { it is AgentEvent.ModelFallbackSucceeded && it.toProviderId == "groq" })
    }

    // --- helpers -----------------------------------------------------------

    private fun request(config: ModelConfig) = AgentLoopRequest(
        sessionId = "s",
        parentSessionId = null,
        definition = AgentCatalog.MAIN,
        allowedTools = listOf(AgentProtocol.FINISH_TOOL),
        permissionLevel = PermissionLevel.READ_ONLY,
        maxSteps = 3,
        userPrompt = "go",
        objective = null,
        scopedContext = "",
        workspaceId = null,
        modelConfig = config,
    )

    private fun loop(gateway: com.agentx.app.model.ModelGateway, fallback: ModelFallback): AgentLoop {
        val registry = DefaultToolRegistry()
        return AgentLoop(
            gateway = gateway,
            toolRouter = DefaultToolRouter(registry),
            bridge = AgentToolBridge(registry),
            modelFallback = fallback,
        )
    }

    private fun provider(providerId: String, respond: () -> ModelResponse): ModelProvider = object : ModelProvider {
        override val id: String = providerId
        override fun capabilities(modelId: String): ModelCapabilities =
            ModelCapabilities(streaming = true, toolCalling = true)

        override suspend fun complete(request: ModelRequest): ModelResponse = respond()
        override suspend fun stream(request: ModelRequest, onEvent: (ModelStreamEvent) -> Unit): ModelResponse =
            respond()
    }

    private class RecordingProvider(
        override val id: String,
        private val respond: () -> ModelResponse,
    ) : ModelProvider {
        val requests = mutableListOf<ModelRequest>()
        override fun capabilities(modelId: String): ModelCapabilities =
            ModelCapabilities(streaming = true, toolCalling = true)

        override suspend fun complete(request: ModelRequest): ModelResponse {
            requests += request
            return respond()
        }

        override suspend fun stream(
            request: ModelRequest,
            onEvent: (ModelStreamEvent) -> Unit,
        ): ModelResponse {
            requests += request
            return respond()
        }
    }

    private class StreamingProvider(
        override val id: String,
        private val content: String,
        private val beforeOutput: Throwable? = null,
        private val afterOutput: Throwable? = null,
    ) : ModelProvider {
        var streamCalls = 0
        override fun capabilities(modelId: String): ModelCapabilities =
            ModelCapabilities(streaming = true, toolCalling = true)

        override suspend fun complete(request: ModelRequest): ModelResponse =
            ModelResponse(model = request.model, providerId = id, content = content)

        override suspend fun stream(
            request: ModelRequest,
            onEvent: (ModelStreamEvent) -> Unit,
        ): ModelResponse {
            streamCalls += 1
            beforeOutput?.let { throw it }
            if (content.isNotEmpty()) onEvent(ModelStreamEvent.TextDelta(content))
            afterOutput?.let { throw it }
            return ModelResponse(model = request.model, providerId = id, content = content)
        }
    }
}
