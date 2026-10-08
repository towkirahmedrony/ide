package com.agentx.app.model.connect

import com.agentx.app.core.ForgeErrorCode
import com.agentx.app.core.errorOrNull
import com.agentx.app.core.valueOrNull
import com.agentx.app.model.DefaultModelGateway
import com.agentx.app.model.FakeHttpTransport
import com.agentx.app.model.SUCCESS_RESPONSE
import com.agentx.app.model.http.HttpResponseSpec
import com.agentx.app.model.manager.DefaultModelManager
import com.agentx.app.model.manager.FakeModelRunner
import com.agentx.app.model.manager.GatewayModelConnectionRegistry
import com.agentx.app.model.manager.ModelConnectionKind
import com.agentx.app.model.manager.RecordingModelProvider
import com.agentx.app.model.preset.DefaultModelPresetRepository
import com.agentx.app.model.preset.EndpointDiscoveryMode
import com.agentx.app.model.preset.InMemoryModelPresetStore
import com.agentx.app.model.preset.InMemoryModelSecretStore
import com.agentx.app.model.preset.ModelApiProtocol
import com.agentx.app.model.preset.ModelProviderIds
import com.agentx.app.model.preset.NGROK_SKIP_BROWSER_WARNING_HEADER
import com.agentx.app.model.preset.ModelProviderType
import com.agentx.app.model.preset.StoreBackedModelCredentialResolver
import com.agentx.app.model.runtime.ModelLifecycleState
import com.agentx.app.model.runtime.recordingLogger
import com.agentx.app.model.runtime.RecordingLogSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A minimal native Gemini completion, so the probe has something to parse. */
private const val NATIVE_RESPONSE: String =
    """{"candidates":[{"content":{"parts":[{"text":"pong"}]},"finishReason":"STOP"}]}"""

class ModelConnectServiceTest {

    private val store = InMemoryModelPresetStore()
    private val secrets = InMemoryModelSecretStore()
    private val gateway = DefaultModelGateway()
    private val logs = RecordingLogSink()
    private val runner = FakeModelRunner()
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    private var ids = 0

    private fun manager(transport: FakeHttpTransport): DefaultModelManager = DefaultModelManager(
        repository = DefaultModelPresetRepository(
            store = store,
            clock = { 1_000L },
            idFactory = { "generated-${++ids}" },
        ),
        runners = listOf(runner),
        registry = GatewayModelConnectionRegistry(
            gateway = gateway,
            providerFactory = { RecordingModelProvider() },
            logger = recordingLogger(logs),
        ),
        credentials = StoreBackedModelCredentialResolver(secrets),
        secretStore = secrets,
        logger = recordingLogger(logs),
        scope = scope,
        clock = { 1_000L },
        ioDispatcher = Dispatchers.Unconfined,
        monitorEnabled = false,
        transport = transport,
    )

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private fun modelsJson(vararg ids: String): String =
        """{"object":"list","data":[${ids.joinToString(",") { """{"id":"$it"}""" }}]}"""

    private fun openAiTransport(
        listBody: String = modelsJson("Qwen/Qwen2.5-Coder-14B-Instruct"),
        listStatus: Int = 200,
        chatBody: String = SUCCESS_RESPONSE,
        chatStatus: Int = 200,
        nativeBody: String = NATIVE_RESPONSE,
        nativeStatus: Int = 200,
    ): FakeHttpTransport = FakeHttpTransport(
        executeHandler = { request ->
            when {
                request.method == "GET" && request.url.endsWith("/models") ->
                    HttpResponseSpec(listStatus, listBody)
                request.method == "POST" && request.url.endsWith("/chat/completions") ->
                    HttpResponseSpec(chatStatus, chatBody)
                // Gemini's native completion addresses the model in the path.
                request.method == "POST" && request.url.contains(":generateContent") ->
                    HttpResponseSpec(nativeStatus, nativeBody)
                else -> HttpResponseSpec(404, "")
            }
        },
    )

    /** Gemini's own model list: a `models` array whose entries are `models/<id>`. */
    private fun geminiModelsJson(vararg ids: String): String =
        """{"models":[${ids.joinToString(",") { """{"name":"models/$it","displayName":"$it"}""" }}]}"""

    @Test
    fun `successful custom connect saves the discovered id and comes online`() = runBlocking {
        val transport = openAiTransport()
        val manager = manager(transport)

        val phases = mutableListOf<ModelConnectPhase>()
        val result = manager.connectQuick(
            ModelConnectRequest(
                displayName = "Qwen Coder 14B",
                endpoint = "https://xxxx.ngrok-free.app",
            ),
        ) { phases += it }

        val connected = assertIs<ModelConnectOutcome.Connected>(assertNotNull(result.valueOrNull()))
        assertEquals("Qwen Coder 14B", connected.preset.displayName)
        assertEquals("Qwen/Qwen2.5-Coder-14B-Instruct", connected.preset.modelIdentifier)
        assertEquals("https://xxxx.ngrok-free.app", connected.preset.endpoint.explicitUrl)
        assertEquals("/v1", connected.preset.apiBasePath)
        assertEquals(EndpointDiscoveryMode.CONFIGURED_ENDPOINT, connected.preset.endpoint.mode)
        assertEquals(ModelProviderType.REMOTE_OPENAI_COMPATIBLE, connected.preset.providerType)
        assertEquals(ModelLifecycleState.ONLINE, connected.status.state)
        assertEquals(listOf(
            ModelConnectPhase.CONNECTING,
            ModelConnectPhase.DISCOVERING,
            ModelConnectPhase.DETECTING_MODEL,
            ModelConnectPhase.TESTING,
            ModelConnectPhase.CONNECTED,
        ), phases)
        assertEquals(1, runner.startCalls)
        assertEquals(connected.preset.id, manager.state.value.activePresetId)
        assertNotNull(manager.activeConfig())
        assertFalse(logs.contains("sk-"))
    }

    @Test
    fun `chat failure never marks the model online or persists a live connection`() = runBlocking {
        val transport = openAiTransport(chatStatus = 500, chatBody = """{"error":{"message":"boom"}}""")
        val manager = manager(transport)

        val result = manager.connectQuick(
            ModelConnectRequest(displayName = "Qwen", endpoint = "https://host.example/v1"),
        )

        val error = assertNotNull(result.errorOrNull())
        assertEquals(ForgeErrorCode.MODEL_OPERATION_FAILED, error.code)
        assertTrue(store.load().isEmpty())
        assertEquals(0, runner.startCalls)
        assertNull(manager.activeConfig())
    }

    @Test
    fun `401 during discovery does not save a preset`() = runBlocking {
        val transport = openAiTransport(listStatus = 401, listBody = """{"error":"no"}""")
        val manager = manager(transport)

        val error = assertNotNull(
            manager.connectQuick(
                ModelConnectRequest(displayName = "Qwen", endpoint = "https://host.example", credential = "sk-bad"),
            ).errorOrNull(),
        )
        assertTrue(error.message!!.contains("authentication"))
        assertFalse(error.message!!.contains("sk-bad"))
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `multiple models without a default ask the user instead of guessing`() = runBlocking {
        val transport = openAiTransport(listBody = modelsJson("alpha-chat", "beta-chat"))
        val manager = manager(transport)

        val outcome = assertNotNull(
            manager.connectQuick(
                ModelConnectRequest(displayName = "Lab", endpoint = "https://host.example/v1"),
            ).valueOrNull(),
        )
        val choice = assertIs<ModelConnectOutcome.NeedsModelChoice>(outcome)
        assertEquals(listOf("alpha-chat", "beta-chat"), choice.models)
        assertTrue(store.load().isEmpty())
        assertEquals(0, runner.startCalls)
    }

    @Test
    fun `unsupported reachable server explains itself`() = runBlocking {
        val transport = FakeHttpTransport(executeHandler = { HttpResponseSpec(200, "<html>hi</html>") })
        val manager = manager(transport)

        val error = assertNotNull(
            manager.connectQuick(
                ModelConnectRequest(displayName = "Lab", endpoint = "https://host.example"),
            ).errorOrNull(),
        )
        assertTrue(error.message!!.contains("could not detect a supported API"))
    }

    @Test
    fun `optional api key is not required for a custom endpoint`() = runBlocking {
        val transport = openAiTransport()
        val manager = manager(transport)

        val connected = assertIs<ModelConnectOutcome.Connected>(
            assertNotNull(
                manager.connectQuick(
                    ModelConnectRequest(displayName = "Local", endpoint = "http://192.168.1.9:8000"),
                ).valueOrNull(),
            ),
        )
        assertEquals(ModelProviderType.LOCAL_PHONE, connected.preset.providerType)
        assertNull(connected.preset.credentialRef)
    }

    @Test
    fun `authenticated custom endpoint stores a credential reference not the secret`() = runBlocking {
        val transport = openAiTransport()
        val manager = manager(transport)

        val connected = assertIs<ModelConnectOutcome.Connected>(
            assertNotNull(
                manager.connectQuick(
                    ModelConnectRequest(
                        displayName = "Private",
                        endpoint = "https://host.example/v1",
                        credential = "sk-secret-value",
                    ),
                ).valueOrNull(),
            ),
        )
        val ref = assertNotNull(connected.preset.credentialRef)
        assertEquals("sk-secret-value", secrets.get(ref))
        assertFalse(connected.preset.toString().contains("sk-secret-value"))
        assertFalse(logs.contains("sk-secret-value"))
        // The pre-existing structured logging still works alongside the new API
        // traces, and neither one mentions the credential.
        assertTrue(logs.contains("Model credential stored"))
        assertTrue(logs.contains("Active model connection updated"))
    }

    @Test
    fun `gemini requires an api key and connects over its own api, not the compatible surface`() = runBlocking {
        val manager = manager(openAiTransport(listBody = geminiModelsJson("gemini-3.5-flash")))

        val missing = assertNotNull(
            manager.connectQuick(
                ModelConnectRequest(displayName = "Gemini", setupKind = ModelSetupKind.GEMINI),
            ).errorOrNull(),
        )
        assertTrue(missing.message!!.contains("API key"))

        val connected = assertIs<ModelConnectOutcome.Connected>(
            assertNotNull(
                manager.connectQuick(
                    ModelConnectRequest(
                        displayName = "Gemini",
                        setupKind = ModelSetupKind.GEMINI,
                        credential = "AIza-test",
                    ),
                ).valueOrNull(),
            ),
        )
        assertEquals("gemini", connected.preset.setupKind)
        assertEquals("gemini-3.5-flash", connected.preset.modelIdentifier)
        // Gemini's own API, recorded as such: a later completion addresses
        // `models/<model>:generateContent`, never `/v1beta/openai/chat/completions`.
        assertEquals(ModelApiProtocol.GEMINI_NATIVE, connected.preset.apiProtocol)
        val endpoint = connected.preset.endpoint.explicitUrl!!
        assertTrue(endpoint.contains("generativelanguage.googleapis.com"), endpoint)
        assertFalse(endpoint.contains("/openai"), endpoint)
        assertEquals("/v1beta", connected.preset.normalizedApiBasePath)
        assertEquals(ModelProviderType.REMOTE_OPENAI_COMPATIBLE, connected.preset.providerType)
    }

    @Test
    fun `groq requires an api key and uses the known endpoint`() = runBlocking {
        val manager = manager(openAiTransport(listBody = modelsJson("llama-3.3-70b-versatile")))

        val connected = assertIs<ModelConnectOutcome.Connected>(
            assertNotNull(
                manager.connectQuick(
                    ModelConnectRequest(
                        displayName = "Groq",
                        setupKind = ModelSetupKind.GROQ,
                        credential = "gsk-test",
                    ),
                ).valueOrNull(),
            ),
        )
        assertEquals("groq", connected.preset.setupKind)
        assertEquals("llama-3.3-70b-versatile", connected.preset.modelIdentifier)
        assertEquals("https://api.groq.com/openai", connected.preset.endpoint.explicitUrl)
        assertEquals("/v1", connected.preset.apiBasePath)
    }

    @Test
    fun `freellmapi requires an api key, uses the shipped gateway and lists its models`() = runBlocking {
        val transport = openAiTransport(
            listBody = modelsJson("gemini-2.5-flash", "openai/gpt-oss-20b"),
        )
        val manager = manager(transport)

        val missing = assertNotNull(
            manager.connectQuick(
                ModelConnectRequest(displayName = "FreeLLMAPI", setupKind = ModelSetupKind.FREELLMAPI),
            ).errorOrNull(),
        )
        assertTrue(missing.message!!.contains("API key"))

        val connected = assertIs<ModelConnectOutcome.Connected>(
            assertNotNull(
                manager.connectQuick(
                    ModelConnectRequest(
                        displayName = "FreeLLMAPI",
                        setupKind = ModelSetupKind.FREELLMAPI,
                        credential = "fla-test-key",
                        modelIdentifier = "gemini-2.5-flash",
                    ),
                ).valueOrNull(),
            ),
        )

        // API AI, addressed at the shipped gateway. The address is configuration
        // (a preset endpoint), never a constant inside the wire client.
        assertEquals("freellmapi", connected.preset.setupKind)
        assertEquals(ModelProviderIds.FREELMAPI, connected.preset.providerId)
        assertEquals("https://agentx-vgtx.onrender.com/v1", connected.preset.endpoint.explicitUrl)
        assertEquals("", connected.preset.normalizedApiBasePath)
        assertEquals(EndpointDiscoveryMode.CONFIGURED_ENDPOINT, connected.preset.endpoint.mode)
        assertEquals(ModelProviderType.REMOTE_OPENAI_COMPATIBLE, connected.preset.providerType)
        // The existing OpenAI-compatible protocol, not a new one.
        assertEquals(ModelApiProtocol.OPENAI_COMPATIBLE, connected.preset.apiProtocol)

        // Discovery read the gateway's own model list, over Bearer auth.
        val discovery = assertNotNull(transport.requests.firstOrNull { it.method == "GET" })
        assertEquals("https://agentx-vgtx.onrender.com/v1/models", discovery.url)
        assertEquals("Bearer fla-test-key", discovery.headers["Authorization"])

        // The key is stored as a reference: never in the preset, never in the logs.
        val ref = assertNotNull(connected.preset.credentialRef)
        assertEquals("fla-test-key", secrets.get(ref))
        assertFalse(connected.preset.toString().contains("fla-test-key"))
        assertFalse(logs.contains("fla-test-key"))

        // The runtime connection the agent chats over is the gateway base, with the
        // selected model id carried on the request.
        val config = assertNotNull(manager.activeConfig())
        assertEquals("https://agentx-vgtx.onrender.com/v1", config.baseUrl)
        assertEquals("gemini-2.5-flash", config.model)
        assertEquals("fla-test-key", config.apiKey)
        // And it is an API connection, so it is never mistaken for a local endpoint.
        assertEquals(ModelConnectionKind.API, config.connectionKind)
    }

    @Test
    fun `a model listed by the freellmapi gateway is parsed and selectable`() = runBlocking {
        // A FreeLLMAPI /models body is an ordinary OpenAI-compatible list, so the
        // existing parser turns it into the existing model representation — including
        // an id the catalogue's fallback list does not mention.
        val transport = openAiTransport(
            listBody = modelsJson("gemini-2.5-flash", "brand-new-gateway-model"),
        )
        val manager = manager(transport)

        val connected = assertIs<ModelConnectOutcome.Connected>(
            assertNotNull(
                manager.connectQuick(
                    ModelConnectRequest(
                        displayName = "FreeLLMAPI",
                        setupKind = ModelSetupKind.FREELLMAPI,
                        credential = "fla-test-key",
                        modelIdentifier = "brand-new-gateway-model",
                    ),
                ).valueOrNull(),
            ),
        )

        assertEquals("brand-new-gateway-model", connected.preset.modelIdentifier)
        // And that id is what the runtime sends, rather than a gateway default.
        assertEquals("brand-new-gateway-model", assertNotNull(manager.activeConfig()).model)
    }

    @Test
    fun `freellmapi falls back to its catalogue model when none is chosen`() = runBlocking {
        // Several models listed and none chosen: the provider's catalogue preference
        // decides, the same rule Groq and Gemini follow.
        val transport = openAiTransport(modelsJson("gemini-2.5-flash", "openai/gpt-oss-20b"))
        val manager = manager(transport)

        val connected = assertIs<ModelConnectOutcome.Connected>(
            assertNotNull(
                manager.connectQuick(
                    ModelConnectRequest(
                        displayName = "FreeLLMAPI",
                        setupKind = ModelSetupKind.FREELLMAPI,
                        credential = "fla-test-key",
                    ),
                ).valueOrNull(),
            ),
        )

        assertEquals("gemini-2.5-flash", connected.preset.modelIdentifier)
    }

    @Test
    fun `an edited freellmapi address is where the connection is probed and chatted`() = runBlocking {
        // A self-hosted or relocated gateway: the edited address wins over the
        // catalogue default, rather than being ignored.
        val transport = openAiTransport(listBody = modelsJson("gemini-2.5-flash"))
        val manager = manager(transport)

        val connected = assertIs<ModelConnectOutcome.Connected>(
            assertNotNull(
                manager.connectQuick(
                    ModelConnectRequest(
                        displayName = "FreeLLMAPI (self-hosted)",
                        setupKind = ModelSetupKind.FREELLMAPI,
                        endpoint = "https://gateway.example.com/v1",
                        credential = "fla-test-key",
                        modelIdentifier = "gemini-2.5-flash",
                    ),
                ).valueOrNull(),
            ),
        )

        assertEquals("https://gateway.example.com/v1", connected.preset.endpoint.explicitUrl)
        assertEquals("", connected.preset.normalizedApiBasePath)
        val discovery = assertNotNull(transport.requests.firstOrNull { it.method == "GET" })
        assertEquals("https://gateway.example.com/v1/models", discovery.url)
        assertEquals("https://gateway.example.com/v1", assertNotNull(manager.activeConfig()).baseUrl)
    }

    @Test
    fun `saved configuration reloads with display name separate from model id`() = runBlocking {
        val manager = manager(openAiTransport())
        val connected = assertIs<ModelConnectOutcome.Connected>(
            assertNotNull(
                manager.connectQuick(
                    ModelConnectRequest(
                        displayName = "Qwen Coder 14B",
                        endpoint = "https://host.example",
                    ),
                ).valueOrNull(),
            ),
        )

        val restarted = DefaultModelPresetRepository(store, clock = { 2_000L })
        val reloaded = assertNotNull(restarted.find(connected.preset.id))
        assertEquals("Qwen Coder 14B", reloaded.displayName)
        assertEquals("Qwen/Qwen2.5-Coder-14B-Instruct", reloaded.modelIdentifier)
        assertEquals("https://host.example", reloaded.endpoint.explicitUrl)
        assertEquals("/v1", reloaded.apiBasePath)
        assertEquals("custom", reloaded.setupKind)
    }

    // --- custom / local endpoints -------------------------------------------

    /** The id a local server reports for this repository; it must travel unchanged. */
    private val devstral = "hf.co/unsloth/Devstral-Small-2-24B-Instruct-2512-GGUF:Q3_K_M"

    @Test
    fun `an authenticated custom endpoint uses one credential for discovery, verification and the runtime`() = runBlocking {
        val transport = openAiTransport(listBody = modelsJson(devstral))
        val manager = manager(transport)

        val connected = assertIs<ModelConnectOutcome.Connected>(
            assertNotNull(
                manager.connectQuick(
                    ModelConnectRequest(
                        displayName = "Devstral",
                        endpoint = "https://armored-fantasy-stuffing.ngrok-free.dev",
                        credential = "sk-local-secret",
                        modelIdentifier = devstral,
                    ),
                ).valueOrNull(),
            ),
        )

        assertEquals(devstral, connected.preset.modelIdentifier)
        assertEquals("custom", connected.preset.setupKind)

        val discovery = assertNotNull(transport.requests.firstOrNull { it.method == "GET" })
        val verification = assertNotNull(
            transport.requests.firstOrNull { it.url.endsWith("/chat/completions") },
        )

        // The credential the user typed is the one discovery and verification both send.
        assertEquals("Bearer sk-local-secret", discovery.headers["Authorization"])
        assertEquals("Bearer sk-local-secret", verification.headers["Authorization"])
        // And the endpoint's own connection flag travels with both, so a proxy can
        // never let one request through and block the next.
        assertEquals("true", discovery.headers[NGROK_SKIP_BROWSER_WARNING_HEADER])
        assertEquals("true", verification.headers[NGROK_SKIP_BROWSER_WARNING_HEADER])
        // The runtime connection the gateway chats over carries them as well.
        assertEquals(
            "true",
            assertNotNull(manager.activeConfig()).headers[NGROK_SKIP_BROWSER_WARNING_HEADER],
        )
        // A reference is stored, never the secret.
        assertFalse(assertNotNull(connected.preset.credentialRef).contains("sk-local-secret"))
        assertFalse(logs.contains("sk-local-secret"))
    }

    @Test
    fun `an unauthenticated local server connects and sends no credential`() = runBlocking {
        val transport = openAiTransport(listBody = modelsJson("qwen2.5-coder:14b"))
        val manager = manager(transport)

        val connected = assertIs<ModelConnectOutcome.Connected>(
            assertNotNull(
                manager.connectQuick(
                    ModelConnectRequest(
                        displayName = "Local",
                        endpoint = "http://127.0.0.1:8000",
                        modelIdentifier = "qwen2.5-coder:14b",
                    ),
                ).valueOrNull(),
            ),
        )

        assertNull(connected.preset.credentialRef)
        val discovery = assertNotNull(transport.requests.firstOrNull { it.method == "GET" })
        assertNull(discovery.headers["Authorization"], "no key was configured")
        assertEquals("true", discovery.headers[NGROK_SKIP_BROWSER_WARNING_HEADER])
    }

    @Test
    fun `a 403 from a custom endpoint is authentication, not unreachable`() = runBlocking {
        val transport = FakeHttpTransport(
            executeHandler = { HttpResponseSpec(403, "<html>proxy warning</html>") },
        )
        val manager = manager(transport)

        val error = assertNotNull(
            manager.connectQuick(
                ModelConnectRequest(displayName = "Behind a proxy", endpoint = "https://host.example"),
            ).errorOrNull(),
        )

        assertTrue(error.message!!.contains("authentication"))
        assertFalse(error.message!!.contains("unreachable"))
        assertFalse(error.message!!.contains("could not be reached"))
        assertTrue(store.load().isEmpty(), "nothing is stored before the endpoint answers")
    }

    @Test
    fun `a chosen protocol decides which model list the custom endpoint is asked for`() = runBlocking {
        val gets = mutableListOf<String>()
        val manager = manager(
            FakeHttpTransport(
                executeHandler = { request ->
                    if (request.method == "GET") gets += request.url
                    when {
                        request.url.endsWith("/api/tags") ->
                            HttpResponseSpec(200, """{"models":[{"name":"qwen2.5-coder:14b"}]}""")
                        request.url.endsWith("/models") -> HttpResponseSpec(200, modelsJson("only"))
                        request.url.endsWith("/chat/completions") ->
                            HttpResponseSpec(200, SUCCESS_RESPONSE)
                        else -> HttpResponseSpec(404, "")
                    }
                },
            ),
        )

        val connected = assertIs<ModelConnectOutcome.Connected>(
            assertNotNull(
                manager.connectQuick(
                    ModelConnectRequest(
                        displayName = "Ollama",
                        endpoint = "http://127.0.0.1:11434",
                        apiProtocol = ModelApiProtocol.OLLAMA,
                        modelIdentifier = "qwen2.5-coder:14b",
                    ),
                ).valueOrNull(),
            ),
        )

        assertEquals(ModelApiProtocol.OLLAMA, connected.preset.apiProtocol)
        assertEquals(listOf("http://127.0.0.1:11434/api/tags"), gets, "only Ollama's list is asked")
    }

    @Test
    fun `an openai-compatible choice never probes ollama`() = runBlocking {
        val gets = mutableListOf<String>()
        val manager = manager(
            FakeHttpTransport(
                executeHandler = { request ->
                    if (request.method == "GET") gets += request.url
                    when {
                        request.url.endsWith("/api/tags") ->
                            HttpResponseSpec(200, """{"models":[{"name":"qwen2.5-coder:14b"}]}""")
                        request.url.endsWith("/models") -> HttpResponseSpec(200, modelsJson("only"))
                        request.url.endsWith("/chat/completions") ->
                            HttpResponseSpec(200, SUCCESS_RESPONSE)
                        else -> HttpResponseSpec(404, "")
                    }
                },
            ),
        )

        assertIs<ModelConnectOutcome.Connected>(
            assertNotNull(
                manager.connectQuick(
                    ModelConnectRequest(
                        displayName = "Server",
                        endpoint = "http://127.0.0.1:11434",
                        apiProtocol = ModelApiProtocol.OPENAI_COMPATIBLE,
                        modelIdentifier = "only",
                    ),
                ).valueOrNull(),
            ),
        )

        assertTrue(gets.any { it.endsWith("/models") }, gets.toString())
        assertTrue(
            gets.none { it.endsWith("/api/tags") },
            "a server stated to speak the compatible surface is never also asked Ollama's list: $gets",
        )
    }

    @Test
    fun `the display name stays a label, never an endpoint or a model`() = runBlocking {
        val manager = manager(openAiTransport(listBody = modelsJson("only")))

        val connected = assertIs<ModelConnectOutcome.Connected>(
            assertNotNull(
                manager.connectQuick(
                    ModelConnectRequest(
                        displayName = "geminj",
                        endpoint = "https://host.example",
                        modelIdentifier = "only",
                    ),
                ).valueOrNull(),
            ),
        )

        val preset = connected.preset
        assertEquals("geminj", preset.displayName)
        assertFalse(assertNotNull(preset.endpoint.explicitUrl).contains("geminj"))
        assertFalse(preset.modelIdentifier.contains("geminj"))
        assertFalse(preset.providerId.contains("geminj"))
    }

    @Test
    fun `a saved custom preset reconnects from its persisted configuration`() = runBlocking {
        val first = openAiTransport(listBody = modelsJson(devstral))
        val connected = assertIs<ModelConnectOutcome.Connected>(
            assertNotNull(
                manager(first).connectQuick(
                    ModelConnectRequest(
                        displayName = "Devstral",
                        endpoint = "https://armored-fantasy-stuffing.ngrok-free.dev/v1",
                        credential = "sk-local-secret",
                        modelIdentifier = devstral,
                    ),
                ).valueOrNull(),
            ),
        )

        // A second manager over the same stores: only what was persisted survives.
        val restarted = manager(openAiTransport(listBody = modelsJson(devstral)))
        val reloaded = assertNotNull(restarted.preset(connected.preset.id))

        assertEquals("https://armored-fantasy-stuffing.ngrok-free.dev", reloaded.endpoint.explicitUrl)
        assertEquals("/v1", reloaded.normalizedApiBasePath)
        assertEquals(devstral, reloaded.modelIdentifier)
        assertEquals("custom", reloaded.setupKind)
        assertEquals(ModelApiProtocol.OPENAI_COMPATIBLE, reloaded.apiProtocol)
        assertEquals("Devstral", reloaded.displayName)
        assertEquals("sk-local-secret", secrets.get(assertNotNull(reloaded.credentialRef)))
        assertTrue(reloaded.requestHeaders.containsKey(NGROK_SKIP_BROWSER_WARNING_HEADER))

        restarted.selectModel(reloaded.id)

        val config = assertNotNull(restarted.activeConfig())
        assertTrue(config.baseUrl.endsWith("/v1"), config.baseUrl)
        assertFalse(config.baseUrl.contains("/v1/v1"), "the saved path is composed once")
        assertEquals(devstral, config.model)
        assertEquals("sk-local-secret", config.apiKey)
        assertEquals("true", config.headers[NGROK_SKIP_BROWSER_WARNING_HEADER])
    }
}
