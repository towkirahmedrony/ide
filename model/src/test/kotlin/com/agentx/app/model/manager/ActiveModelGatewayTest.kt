package com.agentx.app.model.manager

import com.agentx.app.core.valueOrNull
import com.agentx.app.model.DefaultModelGateway
import com.agentx.app.model.FakeHttpTransport
import com.agentx.app.model.SUCCESS_RESPONSE
import com.agentx.app.model.http.HttpResponseSpec
import com.agentx.app.model.preset.InMemoryModelPresetStore
import com.agentx.app.model.preset.InMemoryModelSecretStore
import com.agentx.app.model.runtime.ModelConnectionPolicy
import com.agentx.app.model.runtime.RuntimeOutputBuffer
import com.agentx.app.model.runtime.colabPreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end model flow without a Colab account, a tunnel or a network.
 *
 * The caller here plays the role of the Main Agent: it only ever sees a
 * [com.agentx.app.model.ModelGateway] and the [com.agentx.app.model.ModelConfig]
 * the manager published. Nothing in this test knows which provider serves the
 * model, which is exactly the property the agent integration relies on.
 */
class ActiveModelGatewayTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    private val gateway = DefaultModelGateway()
    private val transport = FakeHttpTransport(
        response = HttpResponseSpec(statusCode = 200, body = SUCCESS_RESPONSE),
    )
    private val runner = FakeModelRunner()

    private val manager: ModelManager = ModelManagers.create(
        gateway = gateway,
        presetStore = InMemoryModelPresetStore(),
        secretStore = InMemoryModelSecretStore(),
        runtimeOutput = RuntimeOutputBuffer(),
        policy = ModelConnectionPolicy(maxStartAttempts = 2, initialBackoffMillis = 1, maxBackoffMillis = 2),
        runners = listOf(runner),
        monitorEnabled = false,
        providerFactory = OpenAiCompatibleProviderFactory(transport),
        scope = scope,
        ioDispatcher = Dispatchers.Unconfined,
    )

    @AfterTest
    fun tearDown() {
        manager.close()
        scope.cancel()
    }

    private suspend fun addPreset(id: String, model: String) = assertNotNull(
        manager.createPreset(colabPreset(id = id, name = id, model = model)).valueOrNull(),
    )

    @Test
    fun `an agent turn reaches the active model through the gateway alone`() = runBlocking {
        runner.onStart = { onlineResult(it, url = "https://qwen-runner.trycloudflare.com") }
        addPreset("qwen", "qwen2.5-coder-7b-instruct")

        assertNotNull(manager.selectModel("qwen").valueOrNull())
        val config = assertNotNull(manager.activeConfig())

        // The agent-side call path: gateway + config, nothing provider-specific.
        val response = gateway.complete(
            com.agentx.app.model.ModelRequest(
                config = config,
                messages = listOf(com.agentx.app.model.ModelMessage.user("write a test")),
            ),
        )

        assertEquals("Hello!", response.content)
        assertEquals(
            "https://qwen-runner.trycloudflare.com/v1/chat/completions",
            assertNotNull(transport.lastRequest).url,
        )
        assertTrue(transport.lastRequest!!.body!!.contains("\"model\":\"qwen2.5-coder-7b-instruct\""))
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `switching the active model changes the endpoint the same caller uses`() = runBlocking {
        runner.onStart = { onlineResult(it, url = "https://${it.id}-runner.trycloudflare.com") }
        addPreset("qwen", "qwen2.5-coder-7b-instruct")
        addPreset("llama", "llama-3.1-8b-instruct")

        manager.selectModel("qwen")
        gateway.complete(
            com.agentx.app.model.ModelRequest(
                config = assertNotNull(manager.activeConfig()),
                messages = listOf(com.agentx.app.model.ModelMessage.user("hi")),
            ),
        )
        assertEquals(
            "https://qwen-runner.trycloudflare.com/v1/chat/completions",
            assertNotNull(transport.lastRequest).url,
        )

        manager.selectModel("llama")
        gateway.complete(
            com.agentx.app.model.ModelRequest(
                config = assertNotNull(manager.activeConfig()),
                messages = listOf(com.agentx.app.model.ModelMessage.user("hi")),
            ),
        )

        assertEquals(
            "https://llama-runner.trycloudflare.com/v1/chat/completions",
            assertNotNull(transport.lastRequest).url,
        )
        assertTrue(transport.lastRequest!!.body!!.contains("llama-3.1-8b-instruct"))
    }

    @Test
    fun `an ollama model keeps the same provider id and only changes its base path`() = runBlocking {
        runner.onStart = { onlineResult(it, url = "https://ollama-runner.trycloudflare.com") }
        val preset = colabPreset(id = "local", name = "Local", model = "qwen2.5:7b").copy(
            apiProtocol = com.agentx.app.model.preset.ModelApiProtocol.OLLAMA,
        )
        assertNotNull(manager.createPreset(preset).valueOrNull())

        assertNotNull(manager.selectModel("local").valueOrNull())
        val config = assertNotNull(manager.activeConfig())

        assertEquals("openai-compatible", config.providerId, "the protocol decides the provider id")
        assertNotNull(gateway.provider(config.providerId))
        assertEquals("https://ollama-runner.trycloudflare.com/v1", config.baseUrl)
    }
}
