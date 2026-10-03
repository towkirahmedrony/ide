package com.agentx.app.model.runtime

import com.agentx.app.model.preset.InMemoryModelSecretStore
import com.agentx.app.model.preset.StoreBackedModelCredentialResolver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The lifecycle state machine shared by every runner. Tests drive it through the
 * real [ColabRunner] with fake discovery/health collaborators, so the retry,
 * timeout and cancellation behaviour under test is the production code path.
 */
class ModelRunnerLifecycleTest {

    private val sleeps = mutableListOf<Long>()

    private val credentials = StoreBackedModelCredentialResolver(InMemoryModelSecretStore())

    private fun policy(
        startAttempts: Int = 3,
        reconnectAttempts: Int = 3,
    ) = ModelConnectionPolicy(
        maxStartAttempts = startAttempts,
        maxReconnectAttempts = reconnectAttempts,
        initialBackoffMillis = 10,
        maxBackoffMillis = 100,
        backoffMultiplier = 2.0,
    )

    private fun colabRunner(
        discovery: ModelEndpointDiscovery,
        health: ModelHealthChecker,
        policy: ModelConnectionPolicy = policy(),
        sleep: suspend (Long) -> Unit = { sleeps += it },
    ): ColabRunner = ColabRunner(
        discovery = discovery,
        credentials = credentials,
        policy = policy,
        healthChecker = health,
        clock = { 1_000L },
        logger = recordingLogger(RecordingLogSink()),
        sleep = sleep,
    )

    @Test
    fun `a reachable runtime moves through starting and connecting to online`() {
        val observed = mutableListOf<ModelLifecycleState>()
        lateinit var runner: ColabRunner
        val preset = colabPreset()

        val discovery = FakeEndpointDiscovery { preset ->
            observed += runner.status(preset.id).state
            found()
        }
        val health = FakeHealthChecker { _, _ ->
            observed += runner.status(preset.id).state
            healthy(listOf(preset.modelIdentifier))
        }
        runner = colabRunner(discovery, health)

        val result = runBlocking { runner.start(preset) }

        assertEquals(ModelLifecycleState.STARTING, observed.first())
        assertEquals(ModelLifecycleState.CONNECTING, observed.last())
        assertEquals(ModelLifecycleState.ONLINE, result.status.state)
        assertEquals(ModelRuntimeFailure.NONE, result.status.failure)
        assertTrue(result.succeeded)
        assertNotNull(result.status.endpoint)
        assertEquals(1, discovery.calls.size)
        assertTrue(sleeps.isEmpty(), "a runtime that answered immediately must not wait")
        assertEquals(ModelLifecycleState.ONLINE, runner.status(preset.id).state)
    }

    @Test
    fun `a runtime that never appears fails after a bounded number of attempts`() {
        val preset = colabPreset()
        val discovery = FakeEndpointDiscovery()
        val health = FakeHealthChecker()
        val runner = colabRunner(discovery, health, policy(startAttempts = 4))

        val result = runBlocking { runner.start(preset) }

        assertEquals(4, discovery.calls.size, "attempts must be bounded")
        assertEquals(3, sleeps.size, "the delay only happens between attempts")
        assertEquals(ModelLifecycleState.FAILED, result.status.state)
        assertEquals(ModelRuntimeFailure.RUNTIME_NOT_DETECTED, result.status.failure)
        assertTrue(result.status.awaitingRuntime, "the user must be told to open the Model Runner")
        assertTrue(result.status.message.contains("Model runtime stopped"))
        assertTrue(health.checked.isEmpty(), "no endpoint means no health request")
    }

    @Test
    fun `backoff grows between attempts and never exceeds the cap`() {
        val preset = colabPreset()
        val runner = colabRunner(
            FakeEndpointDiscovery(),
            FakeHealthChecker(),
            policy(startAttempts = 6),
        )

        runBlocking { runner.start(preset) }

        assertEquals(listOf(10L, 20L, 40L, 80L, 100L), sleeps)
    }

    @Test
    fun `an endpoint whose model API does not answer ends failed`() {
        val preset = colabPreset()
        val discovery = FakeEndpointDiscovery { found() }
        val health = FakeHealthChecker { _, _ -> unhealthy("The endpoint answered but did not return a JSON model list") }
        val runner = colabRunner(discovery, health, policy(startAttempts = 2))

        val result = runBlocking { runner.start(preset) }

        assertEquals(2, health.checked.size, "each attempt re-checks the model API")
        assertEquals(ModelLifecycleState.FAILED, result.status.state)
        // The endpoint *was* reached: reporting this as "nothing was detected" hid the
        // real reason (a rejected credential, a path that is not a model list) behind a
        // network-sounding message.
        assertEquals(ModelRuntimeFailure.MODEL_API_UNREACHABLE, result.status.failure)
        assertTrue(result.status.detail!!.contains("JSON model list"))
        assertEquals(
            result.status.detail,
            result.status.message,
            "the model API's own reason is what the user is shown",
        )
    }

    @Test
    fun `a rejected credential is reported as such instead of as an unreachable endpoint`() {
        val preset = colabPreset()
        val discovery = FakeEndpointDiscovery { found() }
        val health = FakeHealthChecker { _, _ ->
            unhealthy("The model endpoint rejected the credential (HTTP 401)")
        }
        val runner = colabRunner(discovery, health, policy(startAttempts = 2))

        val result = runBlocking { runner.start(preset) }

        assertEquals(ModelRuntimeFailure.MODEL_API_UNREACHABLE, result.status.failure)
        assertTrue(result.status.message.contains("credential"))
        assertTrue(result.status.message.contains("401"))
        assertTrue(!result.status.message.contains("is not reachable"))
    }

    @Test
    fun `a degraded model API is reachable and reported as degraded`() {
        val preset = colabPreset()
        val runner = colabRunner(
            FakeEndpointDiscovery { found() },
            FakeHealthChecker { _, _ -> degraded("does not offer the configured model") },
        )

        val result = runBlocking { runner.start(preset) }

        assertEquals(ModelLifecycleState.DEGRADED, result.status.state)
        assertEquals(ModelRuntimeFailure.MODEL_API_UNREACHABLE, result.status.failure)
        assertTrue(result.succeeded, "a degraded model is still usable")
    }

    @Test
    fun `an already healthy model is not restarted`() {
        val preset = colabPreset()
        val discovery = FakeEndpointDiscovery { found() }
        val health = FakeHealthChecker()
        val runner = colabRunner(discovery, health)

        val first = runBlocking { runner.start(preset) }
        val second = runBlocking { runner.start(preset) }

        assertEquals(ModelLifecycleState.ONLINE, first.status.state)
        assertEquals(ModelLifecycleState.ONLINE, second.status.state)
        assertEquals(1, discovery.calls.size, "the endpoint must not be rediscovered")
        // Re-checking the known endpoint is fine; re-detecting or restarting is not.
        assertEquals(2, health.checked.size)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun `reconnect re-detects the endpoint and is bounded`() {
        val preset = colabPreset()
        val discovery = FakeEndpointDiscovery()
        val runner = colabRunner(discovery, FakeHealthChecker(), policy(reconnectAttempts = 3))

        val result = runBlocking { runner.reconnect(preset) }

        assertEquals(3, discovery.calls.size)
        assertEquals(ModelLifecycleState.FAILED, result.status.state)
        assertEquals(ModelRuntimeFailure.RUNTIME_NOT_DETECTED, result.status.failure)
    }

    @Test
    fun `reconnect brings a healthy runtime back online`() {
        val preset = colabPreset()
        var healthyNow = false
        val discovery = FakeEndpointDiscovery { if (healthyNow) found() else DiscoveryOutcome.NotFound("runtime is gone") }
        val runner = colabRunner(discovery, FakeHealthChecker())

        healthyNow = false
        assertEquals(ModelLifecycleState.FAILED, runBlocking { runner.start(preset) }.status.state)

        healthyNow = true
        val reconnected = runBlocking { runner.reconnect(preset) }

        assertEquals(ModelLifecycleState.ONLINE, reconnected.status.state)
        assertNotNull(reconnected.status.endpoint)
    }

    @Test
    fun `stopping releases the endpoint and states that the runtime was not stopped`() {
        val preset = colabPreset()
        val runner = colabRunner(FakeEndpointDiscovery { found() }, FakeHealthChecker())

        runBlocking { runner.start(preset) }
        val stopped = runBlocking { runner.stop(preset) }

        assertEquals(ModelLifecycleState.STOPPED, stopped.status.state)
        assertNull(stopped.status.endpoint)
        assertTrue(stopped.status.message.contains("not stopped"))
        assertTrue(stopped.status.awaitingRuntime)
    }

    @Test
    fun `an incomplete preset fails without contacting anything`() {
        val preset = colabPreset(model = "")
        val discovery = FakeEndpointDiscovery { found() }
        val health = FakeHealthChecker()
        val runner = colabRunner(discovery, health)

        val result = runBlocking { runner.start(preset) }

        assertEquals(ModelLifecycleState.FAILED, result.status.state)
        assertEquals(ModelRuntimeFailure.INVALID_PRESET, result.status.failure)
        assertTrue(discovery.calls.isEmpty())
        assertTrue(health.checked.isEmpty())
    }

    @Test
    fun `a disabled preset is not started`() {
        val preset = colabPreset().copy(enabled = false)
        val discovery = FakeEndpointDiscovery { found() }
        val runner = colabRunner(discovery, FakeHealthChecker())

        val result = runBlocking { runner.start(preset) }

        assertEquals(ModelRuntimeFailure.DISABLED, result.status.failure)
        assertTrue(discovery.calls.isEmpty())
    }

    @Test
    fun `a cancelled attempt ends in disconnected instead of spinning`() = runBlocking {
        val preset = colabPreset()
        val discovery = FakeEndpointDiscovery()
        val hang = CompletableDeferred<Unit>()
        val runner = colabRunner(discovery, FakeHealthChecker(), policy(startAttempts = 5), sleep = { hang.await() })

        val job = launch(Dispatchers.Default) { runner.start(preset) }
        while (discovery.calls.isEmpty()) delay(5)
        job.cancelAndJoin()

        val status = runner.status(preset.id)
        assertEquals(ModelLifecycleState.DISCONNECTED, status.state)
        assertEquals(ModelRuntimeFailure.CANCELLED, status.failure)
    }

    @Test
    fun `an operation that exceeds the timeout ends in failed`() {
        val preset = colabPreset()
        val discovery = FakeEndpointDiscovery()
        val runner = colabRunner(
            discovery = discovery,
            health = FakeHealthChecker(),
            policy = ModelConnectionPolicy(
                maxStartAttempts = 5,
                initialBackoffMillis = 500,
                maxBackoffMillis = 500,
                operationTimeoutMillis = 60,
            ),
            sleep = { delay(it) },
        )

        val result = runBlocking { runner.start(preset) }

        assertEquals(ModelLifecycleState.FAILED, result.status.state)
        assertEquals(ModelRuntimeFailure.TIMEOUT, result.status.failure)
    }

    @Test
    fun `health check without an endpoint reports the discovery reason`() {
        val preset = colabPreset()
        val runner = colabRunner(FakeEndpointDiscovery(), FakeHealthChecker())

        val health = runBlocking { runner.healthCheck(preset) }

        assertEquals(ModelHealthStatus.UNHEALTHY, health.status)
        assertEquals(ModelLifecycleState.DISCONNECTED, runner.status(preset.id).state)
    }
}
