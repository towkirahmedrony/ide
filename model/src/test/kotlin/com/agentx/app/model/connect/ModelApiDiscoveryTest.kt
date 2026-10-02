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
import kotlin.test.assertFalse
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
    fun `a cold tunnel that times out once is retried and then connects`() = runBlocking {
        var calls = 0
        val transport = FakeHttpTransport(
            executeHandler = { request ->
                calls++
                // The first request is what brings a free tunnel up: the app gives
                // up on it while the server still logs a 200 afterwards.
                if (calls == 1) throw SocketTimeoutException("read timed out")
                if (request.url.endsWith("/v1/models")) {
                    HttpResponseSpec(200, modelsJson("qwen-coder"))
                } else {
                    HttpResponseSpec(404, "")
                }
            },
        )

        val result = ModelApiDiscovery(transport).discover("https://host.example")

        val found = assertIs<DiscoveryResult.Found>(result)
        assertEquals("qwen-coder", found.api.selectedModelId)
        assertEquals(2, calls)
    }

    @Test
    fun `a timeout that survives the retry still explains what to do`() = runBlocking {
        val transport = FakeHttpTransport()
        transport.executeHandler = { throw SocketTimeoutException("read timed out") }

        val result = ModelApiDiscovery(transport).discover("https://host.example")

        val failed = assertIs<DiscoveryResult.Failed>(result)
        assertEquals(DiscoveryFailureKind.TIMEOUT, failed.kind)
        assertTrue(failed.message.contains("try again"))
    }

    @Test
    fun `the overall deadline bounds the candidate fan-out`() = runBlocking {
        val transport = FakeHttpTransport()
        transport.executeHandler = { throw SocketTimeoutException("read timed out") }
        var now = 0L

        val result = ModelApiDiscovery(
            transport = transport,
            timeoutRetries = 0,
            overallTimeoutMillis = 10_000,
            clock = { now += 6_000; now },
        ).discover("https://host.example")

        assertEquals(DiscoveryFailureKind.TIMEOUT, assertIs<DiscoveryResult.Failed>(result).kind)
        // Two candidates times two protocols would be four attempts; the deadline
        // stops the fan-out instead of letting retries run away.
        assertEquals(2, transport.requests.size)
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
    fun `gemini lists models at its own model list, not on the openai-compatible path`() = runBlocking {
        val transport = FakeHttpTransport(
            executeHandler = { request ->
                // The compatible surface the chat uses has no /models route, so the
                // list is read from the Gemini API and authenticated with its header.
                assertEquals("https://generativelanguage.googleapis.com/v1beta/models", request.url)
                assertEquals("AIza-test", request.headers["x-goog-api-key"])
                assertFalse(request.headers.containsKey("Authorization"))
                HttpResponseSpec(
                    200,
                    geminiModelsJson(geminiModel("gemini-3.1-flash"), geminiModel("gemini-3.5-flash")),
                )
            },
        )
        val result = ModelApiDiscovery(transport).discoverKnown(
            spec = KnownModelProviders.gemini,
            credential = "AIza-test",
            preferredModelId = "gemini-3.1-flash",
        )

        val found = assertIs<DiscoveryResult.Found>(result)
        // The endpoint the preset keeps is Gemini's own API root, and it is recorded
        // as the native protocol: the OpenAI-compatible surface is a different
        // endpoint and is never derived from the model-list path.
        assertEquals("https://generativelanguage.googleapis.com", found.api.rootUrl)
        assertEquals("/v1beta", found.api.apiBasePath)
        assertEquals(ModelApiProtocol.GEMINI_NATIVE, found.api.protocol)
        assertFalse(found.api.rootUrl.contains("/openai"))
        // `models/<id>` and `<id>` resolve to the same model.
        assertEquals(listOf("gemini-3.1-flash", "gemini-3.5-flash"), found.api.modelIds)
        assertEquals("gemini-3.1-flash", found.api.selectedModelId)
    }

    @Test
    fun `a model list that parses to nothing is reported, not replaced by the fallback`() = runBlocking {
        val transport = FakeHttpTransport(executeHandler = { HttpResponseSpec(200, "{\"models\":[]}") })

        val result = ModelApiDiscovery(transport).discoverKnown(
            spec = KnownModelProviders.gemini,
            credential = "AIza-test",
        )

        val failed = assertIs<DiscoveryResult.Failed>(result)
        assertEquals(DiscoveryFailureKind.MALFORMED, failed.kind)
        assertTrue(failed.message.contains("/v1beta/models"), failed.message)
        // The built-in compatibility list must not be presented as discovery output.
        assertFalse(failed.message.contains(KnownModelProviders.gemini.suggestedModels.first()))
    }

    @Test
    fun `the reported request path carries no credential`() {
        // What a failure message and a diagnostic log are allowed to show: the host
        // and the path, never a query string and never a key.
        assertEquals(
            "generativelanguage.googleapis.com/v1beta/models",
            diagnosticPath("https://generativelanguage.googleapis.com/v1beta/models"),
        )
        assertEquals(
            "host.example/models",
            diagnosticPath("https://host.example/models?key=AIza-not-this"),
        )
    }

    @Test
    fun `a genuine gemini failure falls back to the built-in compatibility list`() = runBlocking {
        val transport = FakeHttpTransport(executeHandler = { HttpResponseSpec(404, "") })

        val result = ModelApiDiscovery(transport).discoverKnown(
            spec = KnownModelProviders.gemini,
            credential = "AIza-test",
        )

        val found = assertIs<DiscoveryResult.Found>(result)
        // The fallback is marked as such, so it is never mistaken for discovery output.
        assertTrue(found.api.catalogFallback)
        assertEquals(KnownModelProviders.gemini.suggestedModels, found.api.modelIds)
        assertEquals("gemini-3.5-flash", found.api.selectedModelId)
        assertEquals("https://generativelanguage.googleapis.com", found.api.rootUrl)
        assertEquals(ModelApiProtocol.GEMINI_NATIVE, found.api.protocol)
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

/** Gemini's model list root. */
private fun geminiModelsJson(vararg models: String): String =
    "{\"models\":[" + models.joinToString(",") + "]}"

/** One Gemini model entry, named the way the API names it. */
private fun geminiModel(id: String, methods: List<String> = listOf("generateContent")): String =
    "{\"name\":\"models/$id\",\"displayName\":\"$id\",\"supportedGenerationMethods\":[" +
        methods.joinToString(",") { "\"$it\"" } + "]}"
