package com.agentx.app.model.connect

import com.agentx.app.model.FakeHttpTransport
import com.agentx.app.model.http.HttpRequestSpec
import com.agentx.app.model.http.HttpResponseSpec
import com.agentx.app.model.preset.ModelApiProtocol
import kotlinx.coroutines.runBlocking
import java.net.ConnectException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelApiDiscoveryTest {

    private fun discovery(handler: (HttpRequestSpec) -> HttpResponseSpec): ModelApiDiscovery {
        val transport = FakeHttpTransport(executeHandler = handler)
        return ModelApiDiscovery(transport)
    }

    private fun modelsJson(vararg ids: String): String =
        """{"object":"list","data":[${ids.joinToString(",") { """{"id":"$it"}""" }}]}"""

    @Test
    fun `origin without path discovers v1 models`() = runBlocking {
        val seen = mutableListOf<String>()
        val result = discovery { request ->
            seen += request.url
            when (request.url) {
                "https://host.example/v1/models" -> HttpResponseSpec(200, modelsJson("qwen-coder"))
                else -> HttpResponseSpec(404, "")
            }
        }.discover("https://host.example")

        val found = assertIs<DiscoveryResult.Found>(result)
        assertEquals(listOf("qwen-coder"), found.api.modelIds)
        assertEquals("qwen-coder", found.api.selectedModelId)
        assertEquals("/v1", found.api.apiBasePath)
        assertTrue(seen.first().endsWith("/v1/models"))
    }

    @Test
    fun `a single model id is selected automatically`() = runBlocking {
        val result = discovery {
            HttpResponseSpec(200, modelsJson("Qwen/Qwen2.5-Coder-14B-Instruct"))
        }.discover("https://host.example/v1")

        val found = assertIs<DiscoveryResult.Found>(result)
        assertEquals("Qwen/Qwen2.5-Coder-14B-Instruct", found.api.selectedModelId)
    }

    @Test
    fun `multiple model ids without a clear default ask the user`() = runBlocking {
        val result = discovery {
            HttpResponseSpec(200, modelsJson("alpha-chat", "beta-chat"))
        }.discover("https://host.example/v1")

        val choice = assertIs<DiscoveryResult.NeedsModelChoice>(result)
        assertEquals(listOf("alpha-chat", "beta-chat"), choice.modelIds)
        assertNull(choice.api.selectedModelId)
    }

    @Test
    fun `malformed models response is unsupported`() = runBlocking {
        val result = discovery { HttpResponseSpec(200, "<html>nope</html>") }
            .discover("https://host.example/v1")

        val failed = assertIs<DiscoveryResult.Failed>(result)
        assertEquals(DiscoveryFailureKind.UNSUPPORTED, failed.kind)
        assertTrue(failed.message.contains("could not detect a supported API"))
        assertTrue(failed.reachable)
    }

    @Test
    fun `401 is authentication required`() = runBlocking {
        val result = discovery { HttpResponseSpec(401, """{"error":"no"}""") }
            .discover("https://host.example/v1", credential = "bad")

        val failed = assertIs<DiscoveryResult.Failed>(result)
        assertEquals(DiscoveryFailureKind.AUTHENTICATION_REQUIRED, failed.kind)
        assertTrue(failed.reachable)
    }

    @Test
    fun `403 is authentication required`() = runBlocking {
        val result = discovery { HttpResponseSpec(403, "") }.discover("https://host.example")
        assertEquals(DiscoveryFailureKind.AUTHENTICATION_REQUIRED, assertIs<DiscoveryResult.Failed>(result).kind)
    }

    @Test
    fun `404 on every candidate is not a supported api`() = runBlocking {
        val result = discovery { HttpResponseSpec(404, "") }.discover("https://host.example/v1")
        val failed = assertIs<DiscoveryResult.Failed>(result)
        assertTrue(
            failed.kind == DiscoveryFailureKind.NOT_FOUND || failed.kind == DiscoveryFailureKind.UNSUPPORTED,
        )
    }

    @Test
    fun `timeout is reported without a stack trace`() = runBlocking {
        val transport = FakeHttpTransport()
        transport.executeHandler = { throw SocketTimeoutException("read timed out") }
        val result = ModelApiDiscovery(transport).discover("https://host.example")
        val failed = assertIs<DiscoveryResult.Failed>(result)
        assertEquals(DiscoveryFailureKind.TIMEOUT, failed.kind)
        assertTrue(failed.message.contains("did not respond"))
        assertTrue(!failed.message.contains("SocketTimeout"))
    }

    @Test
    fun `unreachable endpoint is reported cleanly`() = runBlocking {
        val transport = FakeHttpTransport()
        transport.executeHandler = { throw ConnectException("Connection refused") }
        val result = ModelApiDiscovery(transport).discover("https://host.example")
        val failed = assertIs<DiscoveryResult.Failed>(result)
        assertEquals(DiscoveryFailureKind.UNREACHABLE, failed.kind)
        assertTrue(failed.message.contains("refused"))
    }

    @Test
    fun `optional api key is omitted from the request`() = runBlocking {
        val transport = FakeHttpTransport(
            executeHandler = { HttpResponseSpec(200, modelsJson("only")) },
        )
        ModelApiDiscovery(transport).discover("https://host.example/v1")
        assertNull(transport.lastRequest?.headers?.get("Authorization"))
    }

    @Test
    fun `authenticated endpoint sends a bearer header`() = runBlocking {
        val transport = FakeHttpTransport(
            executeHandler = { HttpResponseSpec(200, modelsJson("only")) },
        )
        ModelApiDiscovery(transport).discover("https://host.example/v1", credential = "sk-test")
        assertEquals("Bearer sk-test", transport.lastRequest?.headers?.get("Authorization"))
        assertTrue(!transport.lastRequest!!.url.contains("sk-test"))
    }

    @Test
    fun `ollama tags are accepted as a supported protocol`() = runBlocking {
        val result = discovery { request ->
            if (request.url.endsWith("/api/tags")) {
                HttpResponseSpec(200, """{"models":[{"name":"qwen2.5-coder:14b"}]}""")
            } else {
                HttpResponseSpec(404, "")
            }
        }.discover("http://127.0.0.1:11434")

        val found = assertIs<DiscoveryResult.Found>(result)
        assertEquals(ModelApiProtocol.OLLAMA, found.api.protocol)
        assertEquals("qwen2.5-coder:14b", found.api.selectedModelId)
    }

    @Test
    fun `gemini uses the known openai-compatible root`() = runBlocking {
        val transport = FakeHttpTransport(
            executeHandler = { request ->
                assertTrue(request.url.startsWith("https://generativelanguage.googleapis.com/v1beta/openai"))
                HttpResponseSpec(200, modelsJson("gemini-2.0-flash", "gemini-1.5-pro"))
            },
        )
        val result = ModelApiDiscovery(transport).discoverKnown(
            spec = KnownModelProviders.gemini,
            credential = "AIza-test",
        )
        val found = assertIs<DiscoveryResult.Found>(result)
        assertEquals("gemini-2.0-flash", found.api.selectedModelId)
    }

    @Test
    fun `groq uses the known openai-compatible root`() = runBlocking {
        val transport = FakeHttpTransport(
            executeHandler = {
                HttpResponseSpec(200, modelsJson("llama-3.3-70b-versatile", "llama-3.1-8b-instant"))
            },
        )
        val result = ModelApiDiscovery(transport).discoverKnown(
            spec = KnownModelProviders.groq,
            credential = "gsk-test",
        )
        val found = assertIs<DiscoveryResult.Found>(result)
        assertEquals("llama-3.3-70b-versatile", found.api.selectedModelId)
        assertEquals("/v1", found.api.apiBasePath)
    }

    @Test
    fun `gemini without a key is authentication required when the server says so`() = runBlocking {
        val result = discovery { HttpResponseSpec(401, "") }
            .discoverKnown(KnownModelProviders.gemini, credential = null)
        val failed = assertIs<DiscoveryResult.Failed>(result)
        assertEquals(DiscoveryFailureKind.AUTHENTICATION_REQUIRED, failed.kind)
        assertTrue(failed.message.contains("API key"))
    }
}
