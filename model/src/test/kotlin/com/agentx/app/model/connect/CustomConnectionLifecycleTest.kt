package com.agentx.app.model.connect

import com.agentx.app.core.errorOrNull
import com.agentx.app.core.valueOrNull
import com.agentx.app.model.DefaultModelGateway
import com.agentx.app.model.FakeHttpTransport
import com.agentx.app.model.http.HttpRequestSpec
import com.agentx.app.model.http.HttpResponseSpec
import com.agentx.app.model.manager.DefaultModelManager
import com.agentx.app.model.manager.DefaultModelProviderFactory
import com.agentx.app.model.manager.GatewayModelConnectionRegistry
import com.agentx.app.model.preset.DefaultModelPresetRepository
import com.agentx.app.model.preset.InMemoryModelPresetStore
import com.agentx.app.model.preset.InMemoryModelSecretStore
import com.agentx.app.model.preset.ModelApiProtocol
import com.agentx.app.model.preset.NGROK_SKIP_BROWSER_WARNING_HEADER
import com.agentx.app.model.preset.StoreBackedModelCredentialResolver
import com.agentx.app.model.runtime.DefaultModelEndpointDiscovery
import com.agentx.app.model.runtime.HostedEndpointRunner
import com.agentx.app.model.runtime.HttpModelHealthChecker
import com.agentx.app.model.runtime.ModelConnectionPolicy
import com.agentx.app.model.runtime.ModelLifecycleState
import com.agentx.app.model.runtime.ModelRuntimeFailure
import com.agentx.app.model.runtime.RecordingLogSink
import com.agentx.app.model.runtime.RuntimeOutputBuffer
import com.agentx.app.model.runtime.TunnelProviders
import com.agentx.app.model.runtime.recordingLogger
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

/**
 * The reported Custom/Local failure, driven through the real production path.
 *
 * The endpoint was reachable, but every discovery request came back 403: the user's
 * server sits behind a free Ngrok tunnel, whose interstitial answers 403 to any
 * automated client that does not announce itself, and there was nowhere in the Local
 * flow to configure a credential either.
 *
 * The server below behaves the way that stack does — it rejects an unflagged request
 * outright, and rejects an unauthenticated one when a key is configured — while every
 * collaborator is the real one: provider discovery, the chat probe, endpoint
 * discovery, the hosted-endpoint runner and the HTTP health checker.
 */
class CustomConnectionLifecycleTest {

    private val store = InMemoryModelPresetStore()
    private val secrets = InMemoryModelSecretStore()
    private val logs = RecordingLogSink()
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    private val host = "https://armored-fantasy-stuffing.ngrok-free.dev"
    private val apiKey = "sk-local-secret"
    private val model = "hf.co/unsloth/Devstral-Small-2-24B-Instruct-2512-GGUF:Q3_K_M"
    private val requests = mutableListOf<HttpRequestSpec>()

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    /**
     * An OpenAI-compatible server behind a tunnel that inspects clients.
     *
     * @param requiresKey whether the server itself also wants the bearer credential.
     */
    private fun server(requiresKey: Boolean): FakeHttpTransport = FakeHttpTransport(
        executeHandler = { request ->
            requests += request
            val flagged = request.headers[NGROK_SKIP_BROWSER_WARNING_HEADER] == "true"
            val authorized = request.headers["Authorization"] == "Bearer $apiKey"
            when {
                // The proxy's warning page, exactly as an unflagged client sees it.
                !flagged -> HttpResponseSpec(403, "<html>You are about to visit</html>")
                requiresKey && !authorized -> HttpResponseSpec(401, """{"error":"no key"}""")
                request.method == "GET" && request.url.endsWith("/models") ->
                    HttpResponseSpec(200, """{"object":"list","data":[{"id":"$model"}]}""")
                request.method == "POST" && request.url.endsWith("/chat/completions") ->
                    HttpResponseSpec(
                        200,
                        """{"choices":[{"index":0,"message":{"role":"assistant","content":"pong"}}]}""",
                    )
                else -> HttpResponseSpec(404, "")
            }
        },
    )

    private fun manager(transport: FakeHttpTransport): DefaultModelManager {
        val credentials = StoreBackedModelCredentialResolver(secrets)
        val logger = recordingLogger(logs)
        val runner = HostedEndpointRunner(
            discovery = DefaultModelEndpointDiscovery(TunnelProviders(), RuntimeOutputBuffer()),
            credentials = credentials,
            policy = ModelConnectionPolicy(initialBackoffMillis = 0, maxBackoffMillis = 0),
            healthChecker = HttpModelHealthChecker(transport),
            clock = { 1_000L },
            logger = logger,
            sleep = { },
        )
        return DefaultModelManager(
            repository = DefaultModelPresetRepository(store, clock = { 1_000L }),
            runners = listOf(runner),
            registry = GatewayModelConnectionRegistry(
                gateway = DefaultModelGateway(),
                providerFactory = DefaultModelProviderFactory(transport),
                logger = logger,
            ),
            credentials = credentials,
            secretStore = secrets,
            logger = logger,
            scope = scope,
            clock = { 1_000L },
            ioDispatcher = Dispatchers.Unconfined,
            monitorEnabled = false,
            transport = transport,
        )
    }

    private fun request(credential: String? = null) = ModelConnectRequest(
        displayName = "Devstral",
        endpoint = host,
        credential = credential,
        modelIdentifier = model,
    )

    @Test
    fun `an unauthenticated local server behind a tunnel connects`() = runBlocking {
        val transport = server(requiresKey = false)

        val connected = assertIs<ModelConnectOutcome.Connected>(
            assertNotNull(
                manager(transport).connectQuick(request()).valueOrNull(),
                "a reachable server must not be reported as unreachable",
            ),
        )

        assertEquals(model, connected.preset.modelIdentifier)
        assertEquals(ModelApiProtocol.OPENAI_COMPATIBLE, connected.preset.apiProtocol)
        assertEquals(ModelLifecycleState.ONLINE, connected.status.state)
        assertEquals(ModelRuntimeFailure.NONE, connected.status.failure)
        assertNull(connected.preset.credentialRef, "no key was configured")
        assertTrue(requests.isNotEmpty())
        assertTrue(
            requests.all { it.headers[NGROK_SKIP_BROWSER_WARNING_HEADER] == "true" },
            "every request announces itself, so the tunnel cannot 403 one of them",
        )
        assertFalse(logs.contains(apiKey))
    }

    @Test
    fun `an authenticated custom endpoint connects and its saved preset reconnects after a restart`() = runBlocking {
        val first = server(requiresKey = true)
        val connected = assertIs<ModelConnectOutcome.Connected>(
            assertNotNull(
                manager(first).connectQuick(request(credential = apiKey)).valueOrNull(),
            ),
        )

        val preset = connected.preset
        assertEquals(model, preset.modelIdentifier)
        assertEquals("custom", preset.setupKind)
        assertEquals(host, preset.endpoint.explicitUrl)
        assertEquals(ModelLifecycleState.ONLINE, connected.status.state)
        assertEquals("sk-local-secret", secrets.get(assertNotNull(preset.credentialRef)))
        // The credential is stored encrypted under a reference, never in the preset.
        assertFalse(preset.credentialRef!!.contains(apiKey))

        // Discovery, verification and the start-time health check all authenticated.
        val listRequests = first.requests.filter { it.method == "GET" }
        val chatRequests = first.requests.filter { it.url.endsWith("/chat/completions") }
        assertTrue(listRequests.isNotEmpty())
        assertTrue(chatRequests.isNotEmpty())
        assertTrue(first.requests.all { it.headers["Authorization"] == "Bearer $apiKey" })

        // A second manager over the same stores stands in for the app restarting: it
        // only has what was persisted, and it must rebuild the same connection.
        val second = server(requiresKey = true)
        requests.clear()
        val restarted = manager(second)
        restarted.refresh()

        val reloaded = assertNotNull(restarted.preset(preset.id))
        assertEquals(preset.endpoint.explicitUrl, reloaded.endpoint.explicitUrl)
        assertEquals(preset.modelIdentifier, reloaded.modelIdentifier)
        assertEquals(preset.apiProtocol, reloaded.apiProtocol)
        assertEquals(preset.credentialRef, reloaded.credentialRef)
        assertEquals(preset.displayName, reloaded.displayName)

        restarted.selectModel(reloaded.id)

        val config = assertNotNull(restarted.activeConfig(), "the saved preset must reconnect")
        assertEquals("$host/v1", config.baseUrl)
        assertEquals(model, config.model)
        assertEquals(apiKey, config.apiKey)
        assertEquals("true", config.headers[NGROK_SKIP_BROWSER_WARNING_HEADER])

        val health = assertNotNull(requests.firstOrNull { it.method == "GET" }).url
        assertTrue(health.startsWith(host), health)
        assertFalse(health.contains("Devstral"), "the display name is never a request target")
        assertTrue(requests.all { it.headers[NGROK_SKIP_BROWSER_WARNING_HEADER] == "true" })
        assertTrue(requests.all { it.headers["Authorization"] == "Bearer $apiKey" })
        assertFalse(logs.contains(apiKey), "the credential is never logged")
    }

    @Test
    fun `a server that refuses an unauthenticated request asks for a key instead of reporting unreachable`() = runBlocking {
        // Flagged, so the proxy lets it through, but the server itself wants a key.
        val transport = FakeHttpTransport(
            executeHandler = { request ->
                val flagged = request.headers[NGROK_SKIP_BROWSER_WARNING_HEADER] == "true"
                if (!flagged) {
                    HttpResponseSpec(403, "<html>You are about to visit</html>")
                } else {
                    HttpResponseSpec(401, """{"error":"no key"}""")
                }
            },
        )

        val result = manager(transport).connectQuick(request())
        val error = assertNotNull(result.errorOrNull())

        assertTrue(error.message!!.contains("authentication"), error.message)
        assertTrue(error.message!!.contains("API key"), error.message)
        assertFalse(error.message!!.contains("unreachable"), "the server answered; it did not time out")
        assertTrue(store.load().isEmpty(), "nothing is saved before the endpoint answers")
    }
}
