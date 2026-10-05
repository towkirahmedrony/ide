package com.agentx.app.model.manager

import com.agentx.app.core.valueOrNull
import com.agentx.app.model.DefaultModelGateway
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.ModelMessage
import com.agentx.app.model.ModelProviderError
import com.agentx.app.model.ModelProviderErrorCode
import com.agentx.app.model.ModelRequest
import com.agentx.app.model.preset.DefaultModelPresetRepository
import com.agentx.app.model.preset.EndpointConfig
import com.agentx.app.model.preset.EndpointDiscoveryMode
import com.agentx.app.model.preset.InMemoryModelPresetStore
import com.agentx.app.model.preset.InMemoryModelSecretStore
import com.agentx.app.model.preset.ModelApiProtocol
import com.agentx.app.model.preset.ModelPreset
import com.agentx.app.model.preset.ModelProviderIds
import com.agentx.app.model.preset.ModelProviderType
import com.agentx.app.model.preset.StoreBackedModelCredentialResolver
import com.agentx.app.model.ratelimit.CatalogRateLimitProfileRegistrar
import com.agentx.app.model.ratelimit.DefaultRateLimitManager
import com.agentx.app.model.ratelimit.DefaultUsageTracker
import com.agentx.app.model.ratelimit.FakeRateLimitClock
import com.agentx.app.model.ratelimit.RateLimitDecision
import com.agentx.app.model.ratelimit.RateLimitPolicy
import com.agentx.app.model.ratelimit.RateLimitProfile
import com.agentx.app.model.ratelimit.RateLimitSource
import com.agentx.app.model.ratelimit.RateLimitedModelGateway
import com.agentx.app.model.runtime.RecordingLogSink
import com.agentx.app.model.runtime.customPreset
import com.agentx.app.model.runtime.recordingLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The whole point of the task: a provider's quota must reach the *live* manager
 * through the real connection lifecycle, and the real gateway must then admit
 * requests against it before they are dispatched.
 *
 * These tests drive the production objects — [DefaultModelManager], the real
 * [GatewayModelConnectionRegistry], the real [DefaultModelGateway] and the real
 * [DefaultRateLimitManager] — and script only the provider and the runner. A profile
 * that exists in a catalog but never arrives here is not an implemented quota, so
 * every assertion below reads the manager the gateway actually consults.
 */
class RateLimitProfileWiringTest {

    private val documentedModel = "openai/gpt-oss-120b"
    private val documentedRpm = 30

    private val store = InMemoryModelPresetStore()
    private val secrets = InMemoryModelSecretStore()
    private val base = DefaultModelGateway()
    private val logs = RecordingLogSink()
    private val runner = FakeModelRunner()
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    private val providers = LinkedHashMap<String, RecordingModelProvider>()
    private val rateLimits = DefaultRateLimitManager(
        clock = FakeRateLimitClock(),
        tracker = DefaultUsageTracker(),
    )
    private val admitted = RateLimitedModelGateway(base, rateLimits)
    private val managers = mutableListOf<DefaultModelManager>()
    private var ids = 0

    @AfterTest
    fun tearDown() {
        managers.forEach(DefaultModelManager::close)
        managers.clear()
        scope.cancel()
    }

    // --- harness -----------------------------------------------------------

    private fun manager(): DefaultModelManager {
        val created = DefaultModelManager(
            repository = DefaultModelPresetRepository(
                store = store,
                clock = { NOW },
                idFactory = { "generated-${++ids}" },
            ),
            runners = listOf(runner),
            registry = GatewayModelConnectionRegistry(
                gateway = admitted,
                providerFactory = { preset ->
                    RecordingModelProvider(id = preset.providerId)
                        .also { providers[preset.connectionId] = it }
                },
                logger = recordingLogger(logs),
            ),
            credentials = StoreBackedModelCredentialResolver(secrets),
            secretStore = secrets,
            logger = recordingLogger(logs),
            scope = scope,
            clock = { NOW },
            ioDispatcher = Dispatchers.Unconfined,
            monitorEnabled = false,
            // The margin is set to zero here so the assertions can compare against the
            // provider's own number; the default policy is covered separately.
            quotaRegistrar = CatalogRateLimitProfileRegistrar(
                manager = rateLimits,
                policy = RateLimitPolicy(safetyMargin = 0.0),
            ),
        )
        managers += created
        runner.onStart = { preset -> onlineResult(preset, url = "https://${preset.id}.example.dev") }
        return created
    }

    private suspend fun DefaultModelManager.save(preset: ModelPreset): ModelPreset =
        assertNotNull(createPreset(preset).valueOrNull())

    /** A hosted provider with a documented quota. */
    private fun groq(id: String, model: String) = ModelPreset(
        id = id,
        displayName = "Groq",
        providerType = ModelProviderType.REMOTE_OPENAI_COMPATIBLE,
        modelIdentifier = model,
        apiProtocol = ModelApiProtocol.OPENAI_COMPATIBLE,
        apiBasePath = "/v1",
        endpoint = EndpointConfig(EndpointDiscoveryMode.CONFIGURED_ENDPOINT, "https://api.groq.com/openai"),
        setupKind = ModelProviderIds.GROQ,
    )

    /** A server the user runs themselves; it has no provider quota at all. */
    private fun localServer(id: String, model: String) =
        customPreset(id = id, name = "Local Ollama", model = model, endpoint = "http://127.0.0.1:11434").copy(
            apiProtocol = ModelApiProtocol.OLLAMA,
            providerType = ModelProviderType.LOCAL_PHONE,
        )

    private fun request(config: ModelConfig): ModelRequest =
        ModelRequest(config = config, messages = listOf(ModelMessage.user("hello")))

    private suspend fun DefaultModelManager.connect(id: String): ModelConfig {
        assertNotNull(selectModel(id).valueOrNull())
        return assertNotNull(connections()[id])
    }

    // --- profile registration reaches the live manager ---------------------

    @Test
    fun `connecting a provider publishes its documented quota to the live manager`() = runBlocking {
        assertTrue(rateLimits.profiles().isEmpty(), "nothing is configured before a connection exists")

        val manager = manager()
        manager.save(groq("groq-a", documentedModel))
        manager.connect("groq-a")

        val profiles = rateLimits.profiles()
        assertTrue(profiles.isNotEmpty(), "a connected provider must configure the live manager")
        assertTrue(
            profiles.all { it.accountId == "groq-a" },
            "the quota belongs to this connection, not to the provider family",
        )
        assertNotNull(rateLimits.profile(ModelProviderIds.GROQ, documentedModel, "groq-a"))
    }

    @Test
    fun `the published quota is what the gateway admits against`() = runBlocking {
        val manager = manager()
        manager.save(groq("groq-a", documentedModel))
        val config = manager.connect("groq-a")
        val provider = assertNotNull(providers["groq-a"])

        repeat(documentedRpm) { admitted.complete(request(config)) }

        assertEquals(documentedRpm, provider.requests.size, "the provider is used up to its configured ceiling")

        val error = assertFailsWith<ModelProviderError> { admitted.complete(request(config)) }
        assertEquals(ModelProviderErrorCode.RATE_LIMITED, error.code)
        assertEquals(false, error.retryable, "a blocked admission is not a retry prompt")
        assertEquals(
            documentedRpm,
            provider.requests.size,
            "admission happens before dispatch: the refused request never reached the provider",
        )
    }

    @Test
    fun `headroom is reported at the connection identity`() = runBlocking {
        val manager = manager()
        manager.save(groq("groq-a", documentedModel))
        manager.connect("groq-a")

        val headroom = rateLimits.headroom(
            providerId = ModelProviderIds.GROQ,
            modelId = documentedModel,
            estimatedInputTokens = 10,
            estimatedOutputTokens = 10,
            accountId = "groq-a",
        )

        assertEquals(false, headroom.local)
        assertIs<RateLimitDecision.Allowed>(headroom.decision)
        assertTrue(headroom.dimensions.isNotEmpty(), "the configured dimensions are visible")
    }

    // --- local connections -------------------------------------------------

    @Test
    fun `a local connection is never given a remote quota`() = runBlocking {
        val manager = manager()
        manager.save(localServer("local-a", "qwen2.5-coder-14b"))
        val config = manager.connect("local-a")

        assertTrue(
            rateLimits.profiles().none { it.accountId == "local-a" },
            "a local runtime consumes no remote quota and is not configured as if it did",
        )

        val provider = assertNotNull(providers["local-a"])
        repeat(documentedRpm * 2) { admitted.complete(request(config)) }
        assertEquals(documentedRpm * 2, provider.requests.size, "a local model is not throttled")
    }

    @Test
    fun `a configured remote limit never throttles a local connection`() = runBlocking {
        // The strongest form of the exclusion: even a zero-limit profile on the
        // provider family must not reach a local runtime.
        rateLimits.updateProfile(
            RateLimitProfile(
                providerId = ModelProviderIds.OPENAI_COMPATIBLE,
                requestsPerMinute = 0,
                source = RateLimitSource.APP_CONFIGURED,
            ),
        )
        val manager = manager()
        manager.save(localServer("local-a", "qwen2.5-coder-14b"))
        val config = manager.connect("local-a")

        admitted.complete(request(config))

        assertEquals(1, assertNotNull(providers["local-a"]).requests.size)
    }

    // --- connection isolation ----------------------------------------------

    @Test
    fun `two connections of one provider family never share a quota`() = runBlocking {
        val manager = manager()
        manager.save(groq("groq-a", documentedModel))
        manager.save(groq("groq-b", documentedModel))
        val first = manager.connect("groq-a")
        val second = manager.connect("groq-b")

        repeat(documentedRpm) { admitted.complete(request(first)) }

        val blocked = assertIs<RateLimitDecision.Blocked>(
            rateLimits.canRequest(ModelProviderIds.GROQ, documentedModel, 10, 10, accountId = "groq-a"),
        )
        assertTrue(blocked.reason.contains("requests per minute"), blocked.reason)
        assertIs<RateLimitDecision.Allowed>(
            rateLimits.canRequest(ModelProviderIds.GROQ, documentedModel, 10, 10, accountId = "groq-b"),
        )

        // And the second connection still works end to end.
        admitted.complete(request(second))
        assertEquals(1, assertNotNull(providers["groq-b"]).requests.size)
    }

    // --- routing is unchanged ----------------------------------------------

    @Test
    fun `each connection still routes to its own provider`() = runBlocking {
        val manager = manager()
        manager.save(groq("groq-a", documentedModel))
        manager.save(localServer("local-a", "qwen2.5-coder-14b"))
        val remote = manager.connect("groq-a")
        val local = manager.connect("local-a")

        admitted.complete(request(remote))
        admitted.complete(request(local))

        assertEquals(1, assertNotNull(providers["groq-a"]).requests.size)
        assertEquals(1, assertNotNull(providers["local-a"]).requests.size)
        assertEquals(documentedModel, providers.getValue("groq-a").requests.single().model)
        assertEquals("qwen2.5-coder-14b", providers.getValue("local-a").requests.single().model)
    }

    private companion object {
        const val NOW = 1_000L
    }
}
