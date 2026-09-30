package com.agentx.app.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ModelGatewayTest {

    private class StubProvider(
        override val id: String,
        private val capabilities: ModelCapabilities = ModelCapabilities(streaming = true),
        private val content: String = "ok",
    ) : ModelProvider {
        var completeCalls = 0
        var streamCalls = 0

        override fun capabilities(modelId: String): ModelCapabilities = capabilities

        override suspend fun complete(request: ModelRequest): ModelResponse {
            completeCalls++
            return ModelResponse(model = request.model, providerId = id, content = content)
        }

        override suspend fun stream(request: ModelRequest, onEvent: (ModelStreamEvent) -> Unit): ModelResponse {
            streamCalls++
            onEvent(ModelStreamEvent.Started(request.model, id))
            onEvent(ModelStreamEvent.TextDelta(content))
            onEvent(ModelStreamEvent.Completed(ModelFinishReason.STOP, null))
            return ModelResponse(
                model = request.model,
                providerId = id,
                content = content,
                finishReason = ModelFinishReason.STOP,
            )
        }
    }

    // --- configuration -----------------------------------------------------

    @Test
    fun `config validation accepts a valid configuration`() {
        val config = openAiConfig(
            apiKey = null,
            generation = ModelGenerationSettings(temperature = 0.7, maxOutputTokens = 128),
        )

        assertTrue(config.validate().isEmpty())
    }

    @Test
    fun `config validation reports invalid values`() {
        val config = ModelConfig(
            providerId = "",
            baseUrl = "not-a-url",
            model = "",
            generation = ModelGenerationSettings(temperature = 9.0, topP = 3.0, maxOutputTokens = -1),
            timeoutMillis = 0,
        )

        val errors = config.validate()

        assertTrue(errors.any { it.contains("providerId") })
        assertTrue(errors.any { it.contains("baseUrl") })
        assertTrue(errors.any { it.contains("model") })
        assertTrue(errors.any { it.contains("temperature") })
        assertTrue(errors.any { it.contains("topP") })
        assertTrue(errors.any { it.contains("maxOutputTokens") })
        assertTrue(errors.any { it.contains("timeoutMillis") })
    }

    @Test
    fun `gateway rejects an invalid request before dispatch`() {
        val gateway = DefaultModelGateway()
        val provider = StubProvider("openai-compatible")
        gateway.register(provider)
        val config = openAiConfig(model = "")

        val error = assertFailsWith<ModelProviderError> {
            runSuspend { gateway.complete(request(config, ModelMessage.user("hi"))) }
        }

        assertEquals(ModelProviderErrorCode.INVALID_CONFIG, error.code)
        assertEquals(0, provider.completeCalls)
    }

    // --- messages and requests --------------------------------------------

    @Test
    fun `request creation carries messages and generation settings`() {
        val config = openAiConfig(generation = ModelGenerationSettings(temperature = 0.2, maxOutputTokens = 64))
        val request = ModelRequest(
            config = config,
            messages = listOf(ModelMessage.system("be brief"), ModelMessage.user("hello")),
        )

        assertEquals(2, request.messages.size)
        assertEquals(ModelRole.SYSTEM, request.messages[0].role)
        assertEquals(ModelRole.USER, request.messages[1].role)
        assertEquals("local-model", request.model)
        assertEquals(0.2, request.effectiveGeneration.temperature)
        assertEquals(64, request.effectiveGeneration.maxOutputTokens)
    }

    @Test
    fun `per-request generation overrides config defaults`() {
        val config = openAiConfig(generation = ModelGenerationSettings(temperature = 0.2))
        val request = ModelRequest(
            config = config,
            messages = listOf(ModelMessage.user("hello")),
            generation = ModelGenerationSettings(temperature = 0.9),
        )

        assertEquals(0.9, request.effectiveGeneration.temperature)
        assertEquals(0.2, config.generation.temperature)
    }

    // --- provider selection ------------------------------------------------

    @Test
    fun `gateway resolves the provider named by the config`() {
        val gateway = DefaultModelGateway()
        val first = StubProvider("alpha")
        val second = StubProvider("beta")
        gateway.register(first)
        gateway.register(second)

        val resolved = gateway.resolve(request(openAiConfig(providerId = "beta"), ModelMessage.user("hi")))

        assertSame(second, resolved)
        assertEquals(listOf("alpha", "beta"), gateway.providers().map { it.id })
        assertSame(first, gateway.provider("alpha"))
    }

    @Test
    fun `gateway reports an unknown provider`() {
        val gateway = DefaultModelGateway()
        gateway.register(StubProvider("known"))

        val error = assertFailsWith<ModelProviderError> {
            runSuspend { gateway.complete(request(openAiConfig(providerId = "missing"), ModelMessage.user("hi"))) }
        }

        assertEquals(ModelProviderErrorCode.PROVIDER_NOT_FOUND, error.code)
        assertNull(gateway.resolve(request(openAiConfig(providerId = "missing"), ModelMessage.user("hi"))))
    }

    @Test
    fun `gateway rejects duplicate provider ids`() {
        val gateway = DefaultModelGateway()
        gateway.register(StubProvider("dup"))

        val error = assertFailsWith<ModelProviderError> { gateway.register(StubProvider("dup")) }

        assertEquals(ModelProviderErrorCode.DUPLICATE_PROVIDER, error.code)
    }

    @Test
    fun `gateway unregisters providers`() {
        val gateway = DefaultModelGateway()
        gateway.register(StubProvider("temp"))

        assertTrue(gateway.unregister("temp"))
        assertNull(gateway.provider("temp"))
        assertFalse(gateway.unregister("temp"))
    }

    @Test
    fun `gateway delegates complete to the selected provider`() {
        val gateway = DefaultModelGateway()
        val provider = StubProvider("openai-compatible", content = "delegated")
        gateway.register(provider)

        val response = runSuspend {
            gateway.complete(request(openAiConfig(), ModelMessage.user("hi")))
        }

        assertEquals("delegated", response.content)
        assertEquals("openai-compatible", response.providerId)
        assertEquals(1, provider.completeCalls)
    }

    // --- capabilities ------------------------------------------------------

    @Test
    fun `gateway reports provider capabilities`() {
        val gateway = DefaultModelGateway()
        gateway.register(
            StubProvider(
                "openai-compatible",
                capabilities = ModelCapabilities(streaming = true, toolCalling = true, vision = true),
            ),
        )

        val capabilities = gateway.capabilities(request(openAiConfig(), ModelMessage.user("hi")))

        assertTrue(capabilities.streaming)
        assertTrue(capabilities.toolCalling)
        assertTrue(capabilities.vision)
        assertFalse(capabilities.structuredOutput)
    }

    @Test
    fun `config capability override wins over the provider`() {
        val gateway = DefaultModelGateway()
        gateway.register(StubProvider("openai-compatible", capabilities = ModelCapabilities(streaming = false)))
        val config = openAiConfig(capabilities = ModelCapabilities(streaming = true))

        val capabilities = gateway.capabilities(request(config, ModelMessage.user("hi")))

        assertTrue(capabilities.streaming)
    }

    @Test
    fun `gateway rejects streaming when the provider does not support it`() {
        val gateway = DefaultModelGateway()
        val provider = StubProvider("openai-compatible", capabilities = ModelCapabilities(streaming = false))
        gateway.register(provider)
        val config = openAiConfig(stream = true)

        val error = assertFailsWith<ModelProviderError> {
            runSuspend { gateway.stream(request(config, ModelMessage.user("hi"))) { } }
        }

        assertEquals(ModelProviderErrorCode.UNSUPPORTED, error.code)
        assertEquals(0, provider.streamCalls)
    }

    @Test
    fun `gateway streams through a capable provider`() {
        val gateway = DefaultModelGateway()
        val provider = StubProvider("openai-compatible", content = "streamed")
        gateway.register(provider)
        val events = mutableListOf<ModelStreamEvent>()

        val response = runSuspend {
            gateway.stream(request(openAiConfig(stream = true), ModelMessage.user("hi"))) { events += it }
        }

        assertEquals("streamed", response.content)
        assertTrue(events.any { it is ModelStreamEvent.Started })
        assertTrue(events.any { it is ModelStreamEvent.TextDelta })
        assertNotNull(events.filterIsInstance<ModelStreamEvent.Completed>().firstOrNull())
    }

    @Test
    fun `gateway recovers a content JSON tool call from a provider that did not parse it`() {
        val gateway = DefaultModelGateway()
        gateway.register(
            StubProvider(
                "openai-compatible",
                content = """{"name":"read_file","arguments":{"path":"package.json"}}""",
            ),
        )

        val response = runSuspend {
            gateway.complete(request(openAiConfig(), ModelMessage.user("inspect")))
        }

        assertEquals("read_file", response.toolCalls.single().name)
        assertTrue(response.content.isBlank())
        assertEquals(ModelFinishReason.TOOL_CALLS, response.finishReason)
    }
}
