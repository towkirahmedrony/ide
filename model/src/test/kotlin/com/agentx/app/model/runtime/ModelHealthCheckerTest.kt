package com.agentx.app.model.runtime

import com.agentx.app.model.FakeHttpTransport
import com.agentx.app.model.http.HttpResponseSpec
import com.agentx.app.model.preset.EndpointConfig
import com.agentx.app.model.preset.EndpointDiscoveryMode
import com.agentx.app.model.preset.HealthCheckConfig
import com.agentx.app.model.preset.ModelApiProtocol
import com.agentx.app.model.preset.ModelProviderType
import kotlinx.coroutines.runBlocking
import java.net.ConnectException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Health checking must confirm the *model API*, not just that a web server
 * answered on the tunnel URL. These tests pin that behaviour down.
 */
class ModelHealthCheckerTest {

    private val endpoint = ModelEndpoint("https://tunnel-host.trycloudflare.com", EndpointSource.RUNTIME_OUTPUT)

    private var now = 0L

    private fun checker(transport: FakeHttpTransport) = HttpModelHealthChecker(
        transport = transport,
        clock = { now.also { now += 25 } },
    )

    private fun run(
        body: String,
        status: Int = 200,
        preset: com.agentx.app.model.preset.ModelPreset = colabPreset(),
        transport: FakeHttpTransport = FakeHttpTransport(
            response = HttpResponseSpec(statusCode = status, body = body),
        ),
        credential: String? = null,
    ): Pair<ModelHealth, FakeHttpTransport> {
        val health = runBlocking { checker(transport).check(preset, endpoint, credential) }
        return health to transport
    }

    @Test
    fun `a reachable model api is healthy and reports the models it offers`() {
        val (health, transport) = run(
            """{"object":"list","data":[{"id":"qwen2.5-coder-7b-instruct"},{"id":"other-model"}]}""",
        )

        assertEquals(ModelHealthStatus.HEALTHY, health.status)
        assertEquals(listOf("qwen2.5-coder-7b-instruct", "other-model"), health.models)
        assertEquals(25L, health.latencyMillis)
        assertEquals(
            "https://tunnel-host.trycloudflare.com/v1/models",
            assertNotNull(transport.lastRequest).url,
        )
        assertNull(transport.lastRequest?.headers?.get("Authorization"), "no key was configured")
    }

    @Test
    fun `the credential is sent as an authorization header only`() {
        val (health, transport) = run(
            """{"data":[{"id":"qwen2.5-coder-7b-instruct"}]}""",
            credential = "test-secret-value",
        )

        assertEquals(ModelHealthStatus.HEALTHY, health.status)
        assertEquals("Bearer test-secret-value", transport.lastRequest?.headers?.get("Authorization"))
        assertTrue(transport.lastRequest!!.url.contains("tunnel-host"), "the secret is not in the URL")
    }

    @Test
    fun `a web page instead of a model list is not treated as a live model`() {
        val (health, _) = run("<html><body>502 Bad Gateway</body></html>")

        assertEquals(ModelHealthStatus.UNHEALTHY, health.status)
        assertTrue(health.detail.contains("did not return a JSON model list"))
    }

    @Test
    fun `json without a model list does not confirm the model api`() {
        val (health, _) = run("""{"status":"ok"}""")

        assertEquals(ModelHealthStatus.UNHEALTHY, health.status)
        assertTrue(health.detail.contains("no model list"))
    }

    @Test
    fun `an http error is unhealthy and explains the cause`() {
        val (health, _) = run("""{"error":"nope"}""", status = 500)
        assertEquals(ModelHealthStatus.UNHEALTHY, health.status)
        assertTrue(health.detail.contains("500"))

        val (authFailure, _) = run("""{"error":"bad key"}""", status = 401)
        assertTrue(authFailure.detail.contains("credential"))
    }

    @Test
    fun `an unreachable endpoint is unhealthy rather than an exception`() {
        val transport = FakeHttpTransport(response = HttpResponseSpec(statusCode = 200, body = "{}"))
        transport.onExecute = { throw ConnectException("Connection refused") }

        val health = runBlocking { checker(transport).check(colabPreset(), endpoint, null) }

        assertEquals(ModelHealthStatus.UNHEALTHY, health.status)
        assertTrue(health.detail.contains("refused"))
    }

    @Test
    fun `a model that the endpoint does not offer is degraded, not offline`() {
        val (health, _) = run("""{"data":[{"id":"some-other-model"}]}""")

        assertEquals(ModelHealthStatus.DEGRADED, health.status)
        assertTrue(health.detail.contains("qwen2.5-coder-7b-instruct"))
        assertTrue(health.isReachable)
    }

    @Test
    fun `the model list check can be relaxed for runtimes that do not report it`() {
        val preset = colabPreset().copy(
            health = HealthCheckConfig(requireModelInList = false),
        )

        val (health, _) = run("""{"data":[{"id":"some-other-model"}]}""", preset = preset)

        assertEquals(ModelHealthStatus.HEALTHY, health.status)
    }

    @Test
    fun `an empty model list is degraded instead of online`() {
        val (health, _) = run("""{"data":[]}""")

        assertEquals(ModelHealthStatus.DEGRADED, health.status)
        assertTrue(health.detail.contains("no models"))
    }

    @Test
    fun `the ollama protocol checks its own model list path`() {
        val preset = colabPreset().copy(apiProtocol = ModelApiProtocol.OLLAMA)

        val (health, transport) = run("""{"models":[{"name":"qwen2.5-coder-7b-instruct"}]}""", preset = preset)

        assertEquals(ModelHealthStatus.HEALTHY, health.status)
        assertEquals(
            "https://tunnel-host.trycloudflare.com/api/tags",
            transport.lastRequest?.url,
        )
    }

    @Test
    fun `a custom health path overrides the protocol default`() {
        val preset = colabPreset().copy(health = HealthCheckConfig(path = "/healthz"))

        val (health, transport) = run("just text is fine here", preset = preset)

        assertEquals(ModelHealthStatus.HEALTHY, health.status)
        assertEquals("https://tunnel-host.trycloudflare.com/healthz", transport.lastRequest?.url)
    }

    @Test
    fun `a local runtime may use plain http`() {
        val preset = colabPreset(
            endpointMode = EndpointDiscoveryMode.DEVICE_LOCAL_PORT,
        ).copy(
            providerType = ModelProviderType.LOCAL_PHONE,
            serverPort = 8080,
            endpoint = EndpointConfig(mode = EndpointDiscoveryMode.DEVICE_LOCAL_PORT),
        )
        val local = ModelEndpoint("http://127.0.0.1:8080", EndpointSource.DEVICE_LOCAL)

        val health = runBlocking {
            checker(FakeHttpTransport(response = HttpResponseSpec(200, """{"data":[{"id":"qwen2.5-coder-7b-instruct"}]}""")))
                .check(preset, local, null)
        }

        assertEquals(ModelHealthStatus.HEALTHY, health.status)
    }
}
