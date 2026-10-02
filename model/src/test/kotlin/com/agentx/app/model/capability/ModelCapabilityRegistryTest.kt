package com.agentx.app.model.capability

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
import com.agentx.app.model.ModelToolSpec
import com.agentx.app.model.preset.ModelProviderIds
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelCapabilityRegistryTest {

    private val registry = InMemoryModelCapabilityRegistry()

    private fun config(
        providerId: String,
        model: String,
        capabilities: ModelCapabilities? = null,
        baseUrl: String = "https://$providerId.example/v1",
    ) = ModelConfig(
        providerId = providerId,
        baseUrl = baseUrl,
        model = model,
        capabilities = capabilities,
    )

    @Test
    fun `known gemini models are looked up authoritatively`() {
        val profile = assertNotNull(registry.get(ModelProviderIds.GEMINI, "gemini-3.5-flash"))
        assertEquals("Gemini 3.5 Flash", profile.displayName)
        assertTrue(profile.known)
        assertTrue(profile.supports(ModelCapability.TOOL_CALLING))
        assertTrue(profile.supports(ModelCapability.STREAMING))
        assertFalse(profile.local)
        assertTrue(profile.enabled)
    }

    @Test
    fun `known groq models are looked up authoritatively`() {
        assertTrue(registry.supports(ModelProviderIds.GROQ, "openai/gpt-oss-120b", ModelCapability.TOOL_CALLING))
        assertTrue(registry.supports(ModelProviderIds.GROQ, "openai/gpt-oss-20b", ModelCapability.STREAMING))
        assertTrue(registry.supports(ModelProviderIds.GROQ, "qwen/qwen3.8-27b", ModelCapability.TOOL_CALLING))
        assertTrue(registry.supports(ModelProviderIds.GROQ, "llama-3.3-70b-versatile", ModelCapability.TOOL_CALLING))
    }

    @Test
    fun `unknown model lookup is conservative`() {
        assertNull(registry.get(ModelProviderIds.GROQ, "allam-2-7b"))
        val profile = registry.profile(ModelProviderIds.GROQ, "allam-2-7b")
        assertFalse(profile.known)
        assertEquals(CapabilitySupport.UNKNOWN, profile.toolCalling)
        assertFalse(registry.supports(ModelProviderIds.GROQ, "allam-2-7b", ModelCapability.TOOL_CALLING))
    }

    @Test
    fun `tool calling is supported for registered models`() {
        assertTrue(registry.supports(ModelProviderIds.GEMINI, "gemini-2.5-flash", ModelCapability.TOOL_CALLING))
        assertTrue(registry.supports(ModelProviderIds.GEMINI, "gemini-2.5-flash-lite", ModelCapability.TOOL_CALLING))
        assertTrue(registry.supports(ModelProviderIds.GEMINI, "gemini-3-flash-preview", ModelCapability.TOOL_CALLING))
        assertTrue(registry.supports(ModelProviderIds.GEMINI, "gemini-3.1-flash-lite", ModelCapability.TOOL_CALLING))
        assertTrue(registry.supports(ModelProviderIds.GEMINI, "gemini-3.5-flash-lite", ModelCapability.TOOL_CALLING))
    }

    @Test
    fun `tool calling is unsupported for unknown models`() {
        assertFalse(registry.supports(ModelProviderIds.GROQ, "allam-2-7b", ModelCapability.TOOL_CALLING))
        assertEquals(
            CapabilitySupport.UNKNOWN,
            registry.support(ModelProviderIds.OPENAI_COMPATIBLE, "mystery-model", ModelCapability.TOOL_CALLING),
        )
    }

    @Test
    fun `local qwen is marked local and not remote`() {
        val colon = assertNotNull(registry.get(ModelProviderIds.OPENAI_COMPATIBLE, "qwen2.5-coder:14b"))
        val hyphen = assertNotNull(registry.get(ModelProviderIds.OPENAI_COMPATIBLE, "qwen2.5-coder-14b"))
        assertTrue(colon.local)
        assertTrue(hyphen.local)
        assertTrue(colon.supports(ModelCapability.TOOL_CALLING))
        assertTrue(colon.supports(ModelCapability.STREAMING))
        assertTrue(
            config(
                ModelProviderIds.OPENAI_COMPATIBLE,
                "qwen2.5-coder:14b",
                baseUrl = "http://localhost:11434/v1",
            ).isLocalRuntime(registry),
        )
        assertFalse(
            config(ModelProviderIds.GROQ, "llama-3.3-70b-versatile").isLocalRuntime(registry),
        )
    }

    @Test
    fun `provider identities stay separate`() {
        val providers = registry.providers()
        assertTrue(ModelProviderIds.GEMINI in providers)
        assertTrue(ModelProviderIds.GROQ in providers)
        assertTrue(ModelProviderIds.OPENAI_COMPATIBLE in providers)
        assertTrue(ModelProviderIds.CEREBRAS in providers)
        assertTrue(ModelProviderIds.MISTRAL in providers)
        assertTrue(ModelProviderIds.OPENROUTER in providers)
        assertTrue(ModelProviderIds.CLOUDFLARE in providers)
        assertTrue(ModelProviderIds.NVIDIA_NIM in providers)

        assertEquals(ModelProviderIds.GEMINI, registry.profile(ModelProviderIds.GEMINI, "gemini-3.5-flash").providerId)
        assertEquals(ModelProviderIds.GROQ, registry.profile(ModelProviderIds.GROQ, "openai/gpt-oss-20b").providerId)
        assertEquals(
            ModelProviderIds.CEREBRAS,
            registry.profile(ModelProviderIds.CEREBRAS, "cerebras").providerId,
        )
        assertFalse(registry.supports(ModelProviderIds.CEREBRAS, "cerebras", ModelCapability.TOOL_CALLING))
        assertFalse(registry.supports(ModelProviderIds.OPENROUTER, "openrouter", ModelCapability.TOOL_CALLING))
        assertFalse(registry.supports(ModelProviderIds.NVIDIA_NIM, "nvidia-nim", ModelCapability.TOOL_CALLING))
    }

    @Test
    fun `a dynamically discovered model does not become tool capable`() {
        val discovered = registry.profile(ModelProviderIds.GROQ, "llama-4-invented")
        assertFalse(discovered.known)
        assertFalse(discovered.supports(ModelCapability.TOOL_CALLING))
        assertEquals(CapabilitySupport.UNKNOWN, discovered.toolCalling)
    }

    @Test
    fun `a config override is explicit knowledge`() {
        val override = ModelCapabilities(toolCalling = true, streaming = true)
        val capabilities = registry.capabilitiesFor(config(ModelProviderIds.GROQ, "allam-2-7b", override))
        assertTrue(capabilities.toolCalling)
        assertTrue(capabilities.streaming)
    }

    @Test
    fun `gateway rejects tool schemas for a model without tool calling`() {
        val gateway = DefaultModelGateway(registry)
        gateway.register(object : ModelProvider {
            override val id: String = ModelProviderIds.GROQ
            override fun capabilities(modelId: String): ModelCapabilities =
                ModelCapabilities(streaming = true, toolCalling = true)

            override suspend fun complete(request: ModelRequest): ModelResponse =
                ModelResponse(model = request.model, providerId = id, content = "should-not-run")

            override suspend fun stream(request: ModelRequest, onEvent: (ModelStreamEvent) -> Unit): ModelResponse =
                complete(request)
        })
        val request = ModelRequest(
            config = config(ModelProviderIds.GROQ, "allam-2-7b"),
            messages = listOf(ModelMessage.user("hi")),
            tools = listOf(ModelToolSpec(name = "read_file")),
        )

        val error = assertFailsWith<ModelProviderError> {
            runSuspend { gateway.complete(request) }
        }

        assertEquals(ModelProviderErrorCode.UNSUPPORTED, error.code)
        assertEquals(ModelCapabilityErrors.CODE, error.providerErrorType)
        assertTrue(error.message.orEmpty().contains("allam-2-7b"))
        assertTrue(error.message.orEmpty().contains("toolCalling"))
        assertEquals("toolCalling", error.details[ModelCapabilityErrors.DETAIL_CAPABILITY])
    }

    @Test
    fun `gateway allows tool schemas for a known tool capable model`() {
        val gateway = DefaultModelGateway(registry)
        gateway.register(object : ModelProvider {
            override val id: String = ModelProviderIds.GROQ
            override fun capabilities(modelId: String): ModelCapabilities =
                ModelCapabilities(streaming = true, toolCalling = false)

            override suspend fun complete(request: ModelRequest): ModelResponse =
                ModelResponse(model = request.model, providerId = id, content = "ok")

            override suspend fun stream(request: ModelRequest, onEvent: (ModelStreamEvent) -> Unit): ModelResponse =
                complete(request)
        })
        val request = ModelRequest(
            config = config(ModelProviderIds.GROQ, "openai/gpt-oss-20b"),
            messages = listOf(ModelMessage.user("hi")),
            tools = listOf(ModelToolSpec(name = "read_file")),
        )

        val response = runSuspend { gateway.complete(request) }
        assertEquals("ok", response.content)
    }

    @Test
    fun `unknown discovered models remain usable without tools`() {
        val gateway = DefaultModelGateway(registry)
        gateway.register(object : ModelProvider {
            override val id: String = ModelProviderIds.GROQ
            override fun capabilities(modelId: String): ModelCapabilities =
                ModelCapabilities(streaming = true, toolCalling = true)

            override suspend fun complete(request: ModelRequest): ModelResponse =
                ModelResponse(model = request.model, providerId = id, content = "chat")

            override suspend fun stream(request: ModelRequest, onEvent: (ModelStreamEvent) -> Unit): ModelResponse =
                complete(request)
        })
        val chat = ModelRequest(
            config = config(ModelProviderIds.GROQ, "allam-2-7b"),
            messages = listOf(ModelMessage.user("hi")),
        )

        val response = runSuspend { gateway.complete(chat) }
        assertEquals("chat", response.content)
        assertFalse(gateway.capabilities(chat).toolCalling)
    }

    @Test
    fun `local openai compatible config still resolves`() {
        val capabilities = registry.capabilitiesFor(
            config(
                ModelProviderIds.OPENAI_COMPATIBLE,
                "qwen2.5-coder-14b",
                baseUrl = "http://localhost:11434/v1",
            ),
        )
        assertTrue(capabilities.toolCalling)
        assertTrue(capabilities.streaming)
        assertTrue(capabilities.local)
    }

    private fun <T> runSuspend(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context: CoroutineContext = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) {
                outcome = result
            }
        })
        return checkNotNull(outcome) { "The test coroutine suspended unexpectedly" }.getOrThrow()
    }
}
