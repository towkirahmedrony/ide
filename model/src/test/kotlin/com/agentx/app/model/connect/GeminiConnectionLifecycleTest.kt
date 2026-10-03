package com.agentx.app.model.connect

import com.agentx.app.core.ForgeErrorCode
import com.agentx.app.core.errorOrNull
import com.agentx.app.core.valueOrNull
import com.agentx.app.model.DefaultModelGateway
import com.agentx.app.model.FakeHttpTransport
import com.agentx.app.model.http.HttpResponseSpec
import com.agentx.app.model.manager.DefaultModelManager
import com.agentx.app.model.manager.DefaultModelProviderFactory
import com.agentx.app.model.manager.GatewayModelConnectionRegistry
import com.agentx.app.model.preset.DefaultModelPresetRepository
import com.agentx.app.model.preset.InMemoryModelPresetStore
import com.agentx.app.model.preset.GEMINI_API_KEY_HEADER
import com.agentx.app.model.preset.InMemoryModelSecretStore
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
import com.agentx.app.model.runtime.geminiPreset
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
 * The reported failure, driven through the real production path.
 *
 * The connect flow succeeded at every step it logged — discovery listed the models,
 * the `generateContent` verification answered 200, the credential was stored and the
 * preset was saved — and then, inside the same connect, the runner said
 * "The model endpoint for geminj is not reachable."
 *
 * The cause is in the step after saving: the runner's health check hardcoded
 * `Authorization: Bearer`, while the Gemini API reads its key from `x-goog-api-key`
 * (Google's documented header for both the model list and `generateContent`). The
 * endpoint was reachable the whole time; the check authenticated wrongly.
 *
 * The transport below therefore behaves the way the real API does: it answers only a
 * request carrying the key in Gemini's header and rejects everything else with 401.
 * Every collaborator is the real one — provider discovery, the chat probe, endpoint
 * discovery, the hosted-endpoint runner and the HTTP health checker.
 */
class GeminiConnectionLifecycleTest {

    private val store = InMemoryModelPresetStore()
    private val secrets = InMemoryModelSecretStore()
    private val logs = RecordingLogSink()
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    private val geminiRoot = "https://generativelanguage.googleapis.com"
    private val apiKey = "AIza-test-key"

    private val modelListBody =
        """{"models":[{"name":"models/gemini-3.5-flash"},{"name":"models/gemini-3.5-flash-lite"}]}"""

    private val chatBody =
        """{"candidates":[{"content":{"parts":[{"text":"pong"}]},"finishReason":"STOP"}]}"""

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    /** A transport that mirrors the Gemini API's authentication contract. */
    private fun geminiTransport(): FakeHttpTransport = FakeHttpTransport(
        executeHandler = { request ->
            when {
                // The API-key header Google documents for the Gemini API. A bearer
                // token is not an API key, so the API rejects it — exactly as here.
                request.headers[GEMINI_API_KEY_HEADER] != apiKey ->
                    HttpResponseSpec(401, """{"error":{"message":"API key not valid"}}""")

                request.method == "GET" && request.url.endsWith("/v1beta/models") ->
                    HttpResponseSpec(200, modelListBody)

                request.method == "POST" && request.url.contains(":generateContent") ->
                    HttpResponseSpec(200, chatBody)

                else -> HttpResponseSpec(404, "")
            }
        },
    )

    /** The manager exactly as the host wires it, over the supplied transport. */
    private fun manager(transport: FakeHttpTransport): DefaultModelManager {
        val credentials = StoreBackedModelCredentialResolver(secrets)
        val discovery = DefaultModelEndpointDiscovery(TunnelProviders(), RuntimeOutputBuffer())
        val logger = recordingLogger(logs)
        val runner = HostedEndpointRunner(
            discovery = discovery,
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
                // The same provider factory the runtime chats through, so the
                // verification probe speaks Gemini's own API.
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

    private fun geminiRequest() = ModelConnectRequest(
        displayName = "geminj",
        setupKind = ModelSetupKind.GEMINI,
        credential = apiKey,
        modelIdentifier = "gemini-3.5-flash-lite",
    )

    @Test
    fun `a verified gemini connection stays online after it is saved and started`() = runBlocking {
        val transport = geminiTransport()
        val manager = manager(transport)

        val phases = mutableListOf<ModelConnectPhase>()
        val result = manager.connectQuick(geminiRequest()) { phases += it }

        val connected = assertIs<ModelConnectOutcome.Connected>(
            assertNotNull(
                result.valueOrNull(),
                "connect must not fail after verification succeeded, but was: " +
                    "${result.errorOrNull()?.message}",
            ),
        )
        val preset = connected.preset

        // Provider identity, preset identity, display name, endpoint, API path, model
        // id and credential reference stay separate concepts.
        assertEquals("gemini", preset.providerId)
        assertEquals("gemini", preset.setupKind)
        assertEquals("geminj", preset.displayName)
        assertEquals(geminiRoot, preset.endpoint.explicitUrl)
        assertEquals("/v1beta", preset.normalizedApiBasePath)
        assertEquals("gemini-3.5-flash-lite", preset.modelIdentifier)
        assertFalse(
            assertNotNull(preset.credentialRef).contains(apiKey),
            "the reference is not the secret",
        )

        assertEquals(ModelLifecycleState.ONLINE, connected.status.state)
        assertEquals(ModelRuntimeFailure.NONE, connected.status.failure)
        assertFalse(connected.status.message.contains("is not reachable"))
        assertFalse(
            logs.contains("is not reachable"),
            "the endpoint answered every request; nothing may be reported as unreachable",
        )
        assertEquals(ModelConnectPhase.CONNECTED, phases.last())

        // The runtime routes to the same Gemini connection the probe verified.
        val config = assertNotNull(manager.activeConfig())
        assertEquals("gemini", config.providerId)
        assertEquals("$geminiRoot/v1beta", config.baseUrl)
        assertEquals("gemini-3.5-flash-lite", config.model)
    }

    @Test
    fun `a saved gemini preset reconnects through its persisted endpoint, never its display name`() = runBlocking {
        val first = geminiTransport()
        val connected = assertIs<ModelConnectOutcome.Connected>(
            assertNotNull(manager(first).connectQuick(geminiRequest()).valueOrNull()),
        )

        // A second manager over the same stores stands in for the app being restarted:
        // it only has what was persisted to work from.
        val second = geminiTransport()
        val restarted = manager(second)
        restarted.refresh()

        val stored = assertNotNull(restarted.preset(connected.preset.id))
        assertEquals(geminiRoot, stored.endpoint.explicitUrl)
        assertFalse(stored.endpoint.explicitUrl!!.contains("geminj"))

        restarted.selectModel(stored.id)

        val config = assertNotNull(restarted.activeConfig(), "the saved preset must reconnect")
        assertEquals("$geminiRoot/v1beta", config.baseUrl)
        assertEquals("gemini", config.providerId)

        val requested = assertNotNull(second.lastRequest).url
        assertTrue(requested.contains("generativelanguage.googleapis.com"), requested)
        assertFalse(requested.contains("geminj"), "the display name is never a request target")
        assertNull(second.lastRequest?.headers?.get("Authorization"))
    }

    @Test
    fun `a gemini api key the server rejects is reported as authentication, not as unreachable`() = runBlocking {
        val transport = FakeHttpTransport(
            response = HttpResponseSpec(401, """{"error":{"message":"API key not valid"}}"""),
        )

        val error = assertNotNull(manager(transport).connectQuick(geminiRequest()).errorOrNull())

        assertTrue(error.message!!.contains("authentication"), error.message)
        assertFalse(error.message!!.contains("is not reachable"), "the server answered; it did not time out")
        assertFalse(error.message!!.contains(apiKey), "the credential is never echoed")
    }

    @Test
    fun `a saved gemini preset keeps its identity across a restart`() = runBlocking {
        val connected = assertIs<ModelConnectOutcome.Connected>(
            assertNotNull(manager(geminiTransport()).connectQuick(geminiRequest()).valueOrNull()),
        )

        val reloaded = assertNotNull(manager(geminiTransport()).preset(connected.preset.id))

        assertEquals(connected.preset, reloaded)
        assertTrue(reloaded.isConfigured)
        assertEquals(apiKey, secrets.get(assertNotNull(reloaded.credentialRef)))
    }

    @Test
    fun `a gemini preset whose endpoint is missing is refused with the field that is wrong`() = runBlocking {
        val error = assertNotNull(
            manager(geminiTransport()).createPreset(geminiPreset(endpoint = null), apiKey).errorOrNull(),
        )

        assertEquals(ForgeErrorCode.MODEL_PRESET_INVALID, error.code)
        val errors = assertNotNull(error.details["errors"] as? List<*>)
        assertTrue(
            errors.any { it.toString().contains("endpoint", ignoreCase = true) },
            "the missing field is named instead of an endpoint being reported unreachable",
        )
    }
}
