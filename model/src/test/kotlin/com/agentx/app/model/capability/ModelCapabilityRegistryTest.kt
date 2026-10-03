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
    fun `register stores a discovered model by provider and model id`() {
        val isolated = InMemoryModelCapabilityRegistry()
        isolated.register(
            ModelCapabilityProfile.discovered(
                providerId = ModelProviderIds.GROQ,
                modelId = "allam-2-7b",
                displayName = "Allam 2 7B",
                maxContextTokens = 8_192,
            ),
        )

        val profile = assertNotNull(isolated.get(ModelProviderIds.GROQ, "allam-2-7b"))
        assertEquals("Allam 2 7B", profile.displayName)
        assertEquals(8_192, profile.maxContextTokens)
        assertFalse(profile.known)
        assertEquals(CapabilityProvenance.DISCOVERED, profile.provenance)
        assertEquals(CapabilitySupport.UNKNOWN, profile.toolCalling)
        assertEquals(CapabilitySupport.UNKNOWN, profile.streaming)
        assertFalse(isolated.supports(ModelProviderIds.GROQ, "allam-2-7b", ModelCapability.TOOL_CALLING))
    }

    @Test
    fun `register is idempotent on provider id plus model id`() {
        val isolated = InMemoryModelCapabilityRegistry()
        isolated.register(ModelCapabilityProfile.discovered(ModelProviderIds.GROQ, "allam-2-7b", "Allam"))
        isolated.register(ModelCapabilityProfile.discovered(ModelProviderIds.GROQ, "allam-2-7b", "Allam 2 7B"))
        isolated.register(
            ModelCapabilityProfile(
                providerId = ModelProviderIds.GROQ,
                modelId = "allam-2-7b",
                displayName = "should-not-upgrade",
                toolCalling = CapabilitySupport.SUPPORTED,
                streaming = CapabilitySupport.SUPPORTED,
                known = false,
                provenance = CapabilityProvenance.DISCOVERED,
            ),
        )

        val groq = isolated.models(ModelProviderIds.GROQ).filter { it.modelId == "allam-2-7b" }
        assertEquals(1, groq.size)
        assertEquals("Allam 2 7B", groq.single().displayName)
        assertEquals(CapabilitySupport.UNKNOWN, groq.single().toolCalling)
        assertEquals(CapabilitySupport.UNKNOWN, groq.single().streaming)
        assertFalse(groq.single().known)
    }

    @Test
    fun `hardcoded overlay is not replaced by a later discovery`() {
        val isolated = InMemoryModelCapabilityRegistry()
        val before = assertNotNull(isolated.get(ModelProviderIds.GROQ, "llama-3.3-70b-versatile"))
        isolated.register(
            ModelCapabilityProfile.discovered(
                providerId = ModelProviderIds.GROQ,
                modelId = "llama-3.3-70b-versatile",
                displayName = "Discovered Llama",
            ),
        )
        isolated.register(
            ModelCapabilityProfile.connected(
                providerId = ModelProviderIds.GROQ,
                modelId = "llama-3.3-70b-versatile",
            ),
        )

        val after = assertNotNull(isolated.get(ModelProviderIds.GROQ, "llama-3.3-70b-versatile"))
        assertEquals(before.displayName, after.displayName)
        assertTrue(after.known)
        assertEquals(CapabilityProvenance.HARDCODED, after.provenance)
        assertTrue(after.supports(ModelCapability.TOOL_CALLING))
        assertTrue(after.enabled)
        assertEquals(before.modelId, after.modelId)
    }

    @Test
    fun `refresh does not substitute a different model identity`() {
        val isolated = InMemoryModelCapabilityRegistry()
        isolated.register(ModelCapabilityProfile.discovered(ModelProviderIds.GROQ, "allam-2-7b"))
        isolated.register(ModelCapabilityProfile.discovered(ModelProviderIds.GEMINI, "gemini-9-ultra-preview"))
        isolated.register(ModelCapabilityProfile.discovered(ModelProviderIds.GROQ, "llama-4-scout"))

        assertEquals(ModelProviderIds.GROQ, isolated.profile(ModelProviderIds.GROQ, "allam-2-7b").providerId)
        assertEquals("allam-2-7b", isolated.profile(ModelProviderIds.GROQ, "allam-2-7b").modelId)
        assertEquals(ModelProviderIds.GEMINI, isolated.profile(ModelProviderIds.GEMINI, "gemini-9-ultra-preview").providerId)
        assertNull(isolated.get(ModelProviderIds.GROQ, "gemini-9-ultra-preview"))
        assertNull(isolated.get(ModelProviderIds.GEMINI, "allam-2-7b"))
        assertEquals("llama-4-scout", isolated.profile(ModelProviderIds.GROQ, "llama-4-scout").modelId)
    }

    @Test
    fun `a connected model keeps unknown support until overlayed`() {
        val isolated = InMemoryModelCapabilityRegistry()
        isolated.register(ModelCapabilityProfile.connected(ModelProviderIds.OPENAI_COMPATIBLE, "local-qwen", local = true))

        val profile = assertNotNull(isolated.get(ModelProviderIds.OPENAI_COMPATIBLE, "local-qwen"))
        assertEquals(CapabilityProvenance.CONNECTED, profile.provenance)
        assertTrue(profile.local)
        assertFalse(profile.known)
        assertEquals(CapabilitySupport.UNKNOWN, profile.toolCalling)
        assertFalse(isolated.supports(ModelProviderIds.OPENAI_COMPATIBLE, "local-qwen", ModelCapability.STREAMING))
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

    @Test
    fun `gateway rejects a disabled known model`() {
        val disabled = InMemoryModelCapabilityRegistry()
        disabled.register(
            ModelCapabilityProfile(
                providerId = ModelProviderIds.GROQ,
                modelId = "disabled-model",
                displayName = "Disabled model",
                toolCalling = CapabilitySupport.SUPPORTED,
                streaming = CapabilitySupport.SUPPORTED,
                enabled = false,
            ),
        )
        val gateway = DefaultModelGateway(disabled)
        var executed = false
        gateway.register(object : ModelProvider {
            override val id: String = ModelProviderIds.GROQ
            override fun capabilities(modelId: String): ModelCapabilities =
                ModelCapabilities(streaming = true, toolCalling = true)

            override suspend fun complete(request: ModelRequest): ModelResponse {
                executed = true
                return ModelResponse(model = request.model, providerId = id, content = "should-not-run")
            }

            override suspend fun stream(request: ModelRequest, onEvent: (ModelStreamEvent) -> Unit): ModelResponse =
                complete(request)
        })
        val request = ModelRequest(
            config = config(ModelProviderIds.GROQ, "disabled-model"),
            messages = listOf(ModelMessage.user("hi")),
        )

        val error = assertFailsWith<ModelProviderError> {
            runSuspend { gateway.complete(request) }
        }

        assertEquals(ModelProviderErrorCode.UNSUPPORTED, error.code)
        assertEquals(ModelCapabilityErrors.MODEL_DISABLED, error.providerErrorType)
        assertFalse(executed, "a disabled model must not reach the provider")
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
