package com.agentx.app.model.manager

import com.agentx.app.model.DefaultModelGateway
import com.agentx.app.model.ModelMessage
import com.agentx.app.model.ModelProviderError
import com.agentx.app.model.ModelProviderErrorCode
import com.agentx.app.model.ModelStreamEvent
import com.agentx.app.model.openAiConfig
import com.agentx.app.model.preset.ModelApiProtocol
import com.agentx.app.model.preset.ModelPreset
import com.agentx.app.model.preset.ModelProviderIds
import com.agentx.app.model.preset.ModelProviderType
import com.agentx.app.model.request
import com.agentx.app.model.runSuspend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The provider runtime must hold several connections at once. These tests drive
 * the real [DefaultModelGateway] and the real connection registry with
 * lightweight recording providers, so routing is proven by what each provider
 * actually received — not by inspecting constructors.
 */
class MultiProviderConnectionTest {

    private val gateway = DefaultModelGateway()

    private val registry = GatewayModelConnectionRegistry(
        gateway = gateway,
        providerFactory = { preset -> RecordingModelProvider(id = preset.providerId) },
    )

    private fun preset(
        id: String,
        model: String,
        setupKind: String = "custom",
        providerType: ModelProviderType = ModelProviderType.REMOTE_OPENAI_COMPATIBLE,
        protocol: ModelApiProtocol = ModelApiProtocol.OPENAI_COMPATIBLE,
    ): ModelPreset = ModelPreset(
        id = id,
        displayName = id,
        providerType = providerType,
        modelIdentifier = model,
        apiProtocol = protocol,
        setupKind = setupKind,
    )

    private fun connect(preset: ModelPreset, url: String, credential: String? = null) =
        registry.connect(preset, endpoint(url), credential)

    private fun gemini() = preset("gemini-preset", "gemini-1.5-pro", setupKind = "gemini")

    private fun groq() = preset("groq-preset", "llama-3.3-70b-versatile", setupKind = "groq")

    private fun localQwen() = preset(
        "local-qwen",
        "qwen2.5-coder-14b",
        providerType = ModelProviderType.LOCAL_PHONE,
    )

    // --- coexistence -------------------------------------------------------

    @Test
    fun `gemini groq and a local openai-compatible model coexist`() {
        val gemini = connect(gemini(), "https://generativelanguage.example")
        val groq = connect(groq(), "https://api.groq.example")
        val local = connect(localQwen(), "http://127.0.0.1:11434")

        assertEquals(ModelProviderIds.GEMINI, gemini.providerId)
        assertEquals(ModelProviderIds.GROQ, groq.providerId)
        assertEquals(ModelProviderIds.OPENAI_COMPATIBLE, local.providerId)
        // Connections are keyed by their persisted identity, not the provider family.
        assertEquals("gemini-preset", gemini.connectionId)
        assertEquals("groq-preset", groq.connectionId)
        assertEquals("local-qwen", local.connectionId)
        assertEquals(
            setOf("gemini-preset", "groq-preset", "local-qwen"),
            registry.connections().keys,
        )
        assertNotNull(gateway.provider("gemini-preset"))
        assertNotNull(gateway.provider("groq-preset"))
        assertNotNull(gateway.provider("local-qwen"))
        assertEquals(3, gateway.providers().size)
    }

    @Test
    fun `connecting groq does not remove gemini`() {
        val gemini = connect(gemini(), "https://generativelanguage.example")
        connect(groq(), "https://api.groq.example")

        assertSame(
            gateway.provider("gemini-preset"),
            gateway.provider(gemini.connectionId),
        )
        assertEquals(gemini.baseUrl, registry.connections()["gemini-preset"]?.baseUrl)
    }

    @Test
    fun `replacing groq keeps gemini and the local provider untouched`() {
        connect(gemini(), "https://generativelanguage.example")
        connect(localQwen(), "http://127.0.0.1:11434")

        connect(groq(), "https://first.groq.example")
        val replaced = connect(groq(), "https://second.groq.example")

        assertEquals("https://second.groq.example/v1", replaced.baseUrl)
        assertEquals(
            setOf("gemini-preset", "groq-preset", "local-qwen"),
            registry.connections().keys,
        )
        assertEquals(
            "https://generativelanguage.example/v1",
            registry.connections()["gemini-preset"]?.baseUrl,
        )
        assertEquals(
            "http://127.0.0.1:11434/v1",
            registry.connections()["local-qwen"]?.baseUrl,
        )
    }

    // --- provider selection -------------------------------------------------

    @Test
    fun `each config resolves to its own provider and reaches only that provider`() {
        val geminiConfig = connect(gemini(), "https://generativelanguage.example")
        val groqConfig = connect(groq(), "https://api.groq.example")
        val localConfig = connect(localQwen(), "http://127.0.0.1:11434")

        val geminiProvider = assertNotNull(gateway.provider(geminiConfig.connectionId)) as RecordingModelProvider
        val groqProvider = assertNotNull(gateway.provider(groqConfig.connectionId)) as RecordingModelProvider
        val localProvider = assertNotNull(gateway.provider(localConfig.connectionId)) as RecordingModelProvider

        assertSame(geminiProvider, gateway.resolve(request(geminiConfig, ModelMessage.user("hi"))))
        assertSame(groqProvider, gateway.resolve(request(groqConfig, ModelMessage.user("hi"))))
        assertSame(localProvider, gateway.resolve(request(localConfig, ModelMessage.user("hi"))))

        runSuspend { gateway.complete(request(geminiConfig, ModelMessage.user("hello"))) }
        runSuspend { gateway.complete(request(localConfig, ModelMessage.user("hello"))) }

        assertEquals(1, geminiProvider.requests.size)
        assertEquals(0, groqProvider.requests.size)
        assertEquals(listOf("qwen2.5-coder-14b"), localProvider.requests.map { it.model })
    }

    @Test
    fun `a missing provider produces a structured error`() {
        connect(groq(), "https://api.groq.example")

        val error = kotlin.test.assertFailsWith<ModelProviderError> {
            runSuspend {
                gateway.complete(
                    request(openAiConfig(providerId = ModelProviderIds.GEMINI), ModelMessage.user("hi")),
                )
            }
        }

        assertEquals(ModelProviderErrorCode.PROVIDER_NOT_FOUND, error.code)
        assertEquals(ModelProviderIds.GEMINI, error.providerId)
        assertNull(gateway.resolve(request(openAiConfig(providerId = ModelProviderIds.GEMINI), ModelMessage.user("hi"))))
    }

    // --- isolation and lifecycle -------------------------------------------

    @Test
    fun `disconnecting one provider leaves the others connected`() {
        connect(gemini(), "https://generativelanguage.example")
        connect(groq(), "https://api.groq.example")
        connect(localQwen(), "http://127.0.0.1:11434")

        assertTrue(registry.disconnect("groq-preset"))

        assertEquals(
            setOf("gemini-preset", "local-qwen"),
            registry.connections().keys,
        )
        assertNull(gateway.provider("groq-preset"))
        assertNotNull(gateway.provider("gemini-preset"))
        assertNotNull(gateway.provider("local-qwen"))
        assertFalse(registry.isConnected("groq-preset"))
        assertFalse(registry.disconnect("groq-preset"), "a second disconnect is a no-op")
    }

    @Test
    fun `the active config tracks the most recently connected provider only`() {
        connect(gemini(), "https://generativelanguage.example")
        val local = connect(localQwen(), "http://127.0.0.1:11434")

        assertEquals(local.baseUrl, registry.activeConfig()?.baseUrl)
        assertEquals("local-qwen", registry.activePresetId())

        registry.disconnect("gemini-preset")
        assertEquals(local.baseUrl, registry.activeConfig()?.baseUrl, "a different provider is untouched")
    }

    // --- backward compatibility --------------------------------------------

    @Test
    fun `a single-provider configuration still behaves as before`() {
        val config = connect(localQwen(), "http://127.0.0.1:11434", credential = "local-key")

        assertEquals(config, registry.activeConfig())
        assertEquals(1, registry.connections().size)
        assertEquals("local-key", config.apiKey)
        assertEquals("local-qwen", registry.activePresetId())

        assertTrue(registry.disconnect("local-qwen"))
        assertNull(registry.activeConfig())
        assertNull(gateway.provider("local-qwen"))
    }

    @Test
    fun `an ollama preset keeps the openai-compatible identity`() {
        val ollama = preset(
            "ollama",
            "qwen2.5:7b",
            protocol = ModelApiProtocol.OLLAMA,
        )
        val config = connect(ollama, "http://127.0.0.1:11434")

        assertEquals(ModelProviderIds.OPENAI_COMPATIBLE, config.providerId)
        assertEquals(ModelProviderIds.OPENAI_COMPATIBLE, ollama.providerId)
    }

    @Test
    fun `streaming stays on the selected provider`() {
        connect(gemini(), "https://generativelanguage.example")
        connect(groq(), "https://api.groq.example")

        val geminiProvider = assertNotNull(gateway.provider("gemini-preset")) as RecordingModelProvider
        val events = mutableListOf<ModelStreamEvent>()
        val config = assertNotNull(registry.connection("gemini-preset")).copy(stream = true)

        val response = runSuspend {
            gateway.stream(request(config, ModelMessage.user("hi"))) { events += it }
        }

        assertEquals("ok", response.content)
        assertTrue(events.any { it is ModelStreamEvent.Started })
        assertTrue(events.any { it is ModelStreamEvent.TextDelta })
        assertEquals(1, geminiProvider.requests.size)
    }

    @Test
    fun `the gateway keeps several providers registered side by side`() {
        val first = RecordingModelProvider(id = "alpha")
        val second = RecordingModelProvider(id = "beta")
        gateway.register(first)
        gateway.register(second)

        assertEquals(listOf("alpha", "beta"), gateway.providers().map { it.id })
        assertSame(first, gateway.provider("alpha"))
        assertSame(second, gateway.provider("beta"))

        val response = runSuspend {
            gateway.complete(request(openAiConfig(providerId = "beta"), ModelMessage.user("hi")))
        }
        assertEquals(1, second.requests.size)
        assertEquals(0, first.requests.size)
    }
}
