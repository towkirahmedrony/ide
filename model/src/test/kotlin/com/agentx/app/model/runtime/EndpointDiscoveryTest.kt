package com.agentx.app.model.runtime

import com.agentx.app.model.preset.EndpointConfig
import com.agentx.app.model.preset.EndpointDiscoveryMode
import com.agentx.app.model.preset.ModelProviderType
import com.agentx.app.model.preset.TunnelType
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EndpointDiscoveryTest {

    private fun discovery(output: RuntimeOutputBuffer = RuntimeOutputBuffer()) =
        DefaultModelEndpointDiscovery(TunnelProviders(), output)

    @Test
    fun `the tunnel url the runtime printed is detected`() {
        val output = RuntimeOutputBuffer()
        output.append("Uvicorn running on http://127.0.0.1:8000")
        output.append("FORGE_ENDPOINT=https://calm-river-42.trycloudflare.com")

        val outcome = runBlocking { discovery(output).discover(colabPreset()) }

        val found = assertIs<DiscoveryOutcome.Found>(outcome)
        assertEquals("https://calm-river-42.trycloudflare.com", found.endpoint.url)
        assertEquals(EndpointSource.RUNTIME_OUTPUT, found.endpoint.source)
        assertEquals("calm-river-42.trycloudflare.com", found.endpoint.host)
    }

    @Test
    fun `a cloudflare url is detected even without the configured marker`() {
        val output = RuntimeOutputBuffer()
        output.append("|  https://plain-output-99.trycloudflare.com  |")

        val outcome = runBlocking { discovery(output).discover(colabPreset()) }

        assertEquals(
            "https://plain-output-99.trycloudflare.com",
            assertIs<DiscoveryOutcome.Found>(outcome).endpoint.url,
        )
    }

    @Test
    fun `the most recent endpoint wins when the runtime prints several`() {
        val output = RuntimeOutputBuffer()
        output.append("https://first-tunnel.trycloudflare.com")
        output.append("https://second-tunnel.trycloudflare.com")

        val outcome = runBlocking { discovery(output).discover(colabPreset()) }

        assertEquals(
            "https://second-tunnel.trycloudflare.com",
            assertIs<DiscoveryOutcome.Found>(outcome).endpoint.url,
        )
    }

    @Test
    fun `a url that is not a cloudflare quick tunnel is not accepted`() {
        val output = RuntimeOutputBuffer()
        output.append("FORGE_ENDPOINT=https://totally-unrelated.example.com")

        val outcome = runBlocking { discovery(output).discover(colabPreset()) }

        val notFound = assertIs<DiscoveryOutcome.NotFound>(outcome)
        assertTrue(notFound.reason.contains("trycloudflare"), "the reason explains what was expected")
        assertTrue(!notFound.invalidEndpoint, "an absent tunnel is not a configuration error")
    }

    @Test
    fun `no output at all means the runtime has not published an endpoint`() {
        val outcome = runBlocking { discovery().discover(colabPreset()) }

        val notFound = assertIs<DiscoveryOutcome.NotFound>(outcome)
        assertTrue(!notFound.invalidEndpoint)
    }

    @Test
    fun `a configured https endpoint is used as given`() {
        val preset = colabPreset(
            endpointMode = EndpointDiscoveryMode.CONFIGURED_ENDPOINT,
            explicitUrl = "https://my-tunnel.example.com/",
        )

        val outcome = runBlocking { discovery().discover(preset) }

        val found = assertIs<DiscoveryOutcome.Found>(outcome)
        assertEquals("https://my-tunnel.example.com", found.endpoint.url)
        assertEquals(EndpointSource.CONFIGURED, found.endpoint.source)
    }

    @Test
    fun `a configured endpoint that is missing is a configuration problem, not a quiet wait`() {
        val preset = geminiPreset().copy(
            endpoint = EndpointConfig(EndpointDiscoveryMode.CONFIGURED_ENDPOINT),
        )

        val outcome = runBlocking { discovery().discover(preset) }

        val notFound = assertIs<DiscoveryOutcome.NotFound>(outcome)
        assertTrue(notFound.invalidEndpoint, "a missing configured endpoint is not a runtime that is late")
        assertTrue(notFound.reason.contains("endpoint", ignoreCase = true))
    }

    @Test
    fun `a plain http endpoint is refused for a remote provider`() {
        val preset = colabPreset(
            endpointMode = EndpointDiscoveryMode.CONFIGURED_ENDPOINT,
            explicitUrl = "http://my-tunnel.example.com",
        )

        val outcome = runBlocking { discovery().discover(preset) }

        val notFound = assertIs<DiscoveryOutcome.NotFound>(outcome)
        assertTrue(notFound.invalidEndpoint)
        assertTrue(notFound.reason.contains("https"))
        assertTrue(preset.validate().any { it.contains("https") }, "the preset itself reports the problem")
    }

    @Test
    fun `a malformed endpoint is refused instead of being passed on`() {
        for (raw in listOf("not a url", "https://", "ftp://host/path", "https://exa mple.com")) {
            val preset = colabPreset(
                endpointMode = EndpointDiscoveryMode.CONFIGURED_ENDPOINT,
                explicitUrl = raw,
            )

            val outcome = runBlocking { discovery().discover(preset) }

            val notFound = assertIs<DiscoveryOutcome.NotFound>(outcome)
            assertTrue(notFound.invalidEndpoint, "'$raw' must be reported as invalid")
        }
    }

    @Test
    fun `an embedded credential in a url is refused`() {
        val preset = colabPreset(
            endpointMode = EndpointDiscoveryMode.CONFIGURED_ENDPOINT,
            explicitUrl = "https://user:secret@tunnel.example.com",
        )

        val outcome = runBlocking { discovery().discover(preset) }

        assertTrue(assertIs<DiscoveryOutcome.NotFound>(outcome).invalidEndpoint)
    }

    @Test
    fun `a device local model is addressed through its port`() {
        val preset = colabPreset(
            endpointMode = EndpointDiscoveryMode.DEVICE_LOCAL_PORT,
        ).copy(providerType = ModelProviderType.LOCAL_PHONE, serverPort = 8080)

        val outcome = runBlocking { discovery().discover(preset) }

        val found = assertIs<DiscoveryOutcome.Found>(outcome)
        assertEquals("http://127.0.0.1:8080", found.endpoint.url)
        assertEquals(EndpointSource.DEVICE_LOCAL, found.endpoint.source)
    }

    @Test
    fun `a device local model without a port is reported instead of guessed`() {
        val preset = colabPreset(endpointMode = EndpointDiscoveryMode.DEVICE_LOCAL_PORT)
            .copy(providerType = ModelProviderType.LOCAL_PHONE, serverPort = null)

        val outcome = runBlocking { discovery().discover(preset) }

        assertTrue(assertIs<DiscoveryOutcome.NotFound>(outcome).invalidEndpoint)
    }

    @Test
    fun `a manual tunnel uses the configured url and ignores runtime output`() {
        val output = RuntimeOutputBuffer()
        output.append("FORGE_ENDPOINT=https://another-tunnel.trycloudflare.com")
        val preset = colabPreset(
            endpointMode = EndpointDiscoveryMode.RUNTIME_OUTPUT,
            explicitUrl = "https://declared-by-user.example.com",
            tunnelType = TunnelType.MANUAL,
        )

        val outcome = runBlocking { discovery(output).discover(preset) }

        assertEquals(
            "https://declared-by-user.example.com",
            assertIs<DiscoveryOutcome.Found>(outcome).endpoint.url,
        )
    }

    @Test
    fun `cloudflare quick tunnel detection never claims to create a tunnel`() {
        val provider = CloudflareQuickTunnelProvider()

        assertTrue(!provider.createsTunnels)
        assertIs<TunnelDetection.Detected>(provider.validate("https://x.trycloudflare.com"))
        assertIs<TunnelDetection.NotFound>(provider.validate("https://x.ngrok.io"))
    }

    @Test
    fun `a fake tunnel provider keeps discovery independent of the tunnel kind`() {
        val tunnel = FakeTunnelProvider(detectedUrl = "https://faked-endpoint.example.com")
        val discovery = DefaultModelEndpointDiscovery(TunnelProviders(listOf(tunnel)), RuntimeOutputBuffer())

        val outcome = runBlocking { discovery.discover(colabPreset()) }

        assertEquals(
            "https://faked-endpoint.example.com",
            assertIs<DiscoveryOutcome.Found>(outcome).endpoint.url,
        )
        assertEquals(1, tunnel.detections)
    }
}
