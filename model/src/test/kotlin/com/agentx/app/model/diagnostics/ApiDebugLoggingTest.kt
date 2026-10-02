package com.agentx.app.model.diagnostics

import com.agentx.app.model.DefaultModelGateway
import com.agentx.app.model.FakeHttpTransport
import com.agentx.app.model.ModelMessage
import com.agentx.app.model.ModelProviderError
import com.agentx.app.model.SUCCESS_RESPONSE
import com.agentx.app.model.connect.ChatCapabilityProbe
import com.agentx.app.model.connect.DiscoveryResult
import com.agentx.app.model.connect.KnownModelProviders
import com.agentx.app.model.connect.ModelApiDiscovery
import com.agentx.app.model.http.HttpRequestSpec
import com.agentx.app.model.http.HttpResponseSpec
import com.agentx.app.model.manager.GatewayModelConnectionRegistry
import com.agentx.app.model.openAiConfig
import com.agentx.app.model.preset.ModelPreset
import com.agentx.app.model.preset.ModelProviderType
import com.agentx.app.model.manager.DefaultModelProviderFactory
import com.agentx.app.model.request
import com.agentx.app.model.runtime.EndpointSource
import com.agentx.app.model.runtime.ModelEndpoint
import com.agentx.app.model.runtime.RecordingLogSink
import com.agentx.app.model.runtime.colabPreset
import com.agentx.app.model.runtime.recordingLogger
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The API diagnostics contract: a Gemini/Groq request is followable stage by stage
 * in the Developer Log, and a credential never appears in it.
 */
class ApiDebugLoggingTest {

    private companion object {
        /** Shaped like a real Google key so redaction is actually exercised. */
        const val GEMINI_KEY = "AIza-test-key-0123456789"

        /** Shaped like a real Groq key. */
        const val GROQ_KEY = "gsk_testkey0123456789"

        const val GEMINI_ROOT = "https://generativelanguage.googleapis.com/v1beta/openai"
    }

    private fun geminiModelsJson(vararg ids: String): String =
        "{\"models\":[" + ids.joinToString(",") { id ->
            "{\"name\":\"models/$id\",\"displayName\":\"$id\"," +
                "\"supportedGenerationMethods\":[\"generateContent\"]}"
        } + "]}"

    private fun openAiModelsJson(vararg ids: String): String =
        "{\"object\":\"list\",\"data\":[" + ids.joinToString(",") { "{\"id\":\"$it\"}" } + "]}"

    private fun geminiDiscovery(
        sink: RecordingLogSink,
        handler: (HttpRequestSpec) -> HttpResponseSpec,
    ): ModelApiDiscovery = ModelApiDiscovery(FakeHttpTransport(executeHandler = handler), logger = recordingLogger(sink))

    // --- Gemini -------------------------------------------------------------

    @Test
    fun `gemini discovery logs every stage under one correlation id`() = runBlocking {
        val sink = RecordingLogSink()
        val discovery = geminiDiscovery(sink) { request ->
            assertEquals("https://generativelanguage.googleapis.com/v1beta/models", request.url)
            HttpResponseSpec(200, geminiModelsJson("gemini-2.0-flash", "gemini-2.0-flash-lite"))
        }

        val result = discovery.discoverKnown(
            spec = KnownModelProviders.gemini,
            credential = GEMINI_KEY,
            preferredModelId = "gemini-2.0-flash",
        )

        assertIs<DiscoveryResult.Found>(result)
        val text = sink.text()
        assertTrue(text.contains("[GEMINI][DISCOVERY]"), text)
        assertTrue(text.contains("START"), text)
        assertTrue(text.contains("modelListPath=generativelanguage.googleapis.com/v1beta/models"), text)
        assertTrue(text.contains("authScheme=api-key-header"), text)
        assertTrue(text.contains("hasApiKey=YES"), text)
        assertTrue(text.contains("REQUEST method=GET"), text)
        assertTrue(text.contains("path=generativelanguage.googleapis.com/v1beta/models"), text)
        assertTrue(text.contains("RESPONSE status=200"), text)
        assertTrue(text.contains("elapsedMs="), text)
        assertTrue(text.contains("bodyBytes="), text)
        assertTrue(text.contains("PARSE fieldPath=models rawModels=2"), text)
        assertTrue(
            text.contains("MODEL raw=models/gemini-2.0-flash normalized=gemini-2.0-flash accepted=true"),
            text,
        )
        assertTrue(text.contains("SELECT"), text)
        assertTrue(text.contains("selected=gemini-2.0-flash"), text)
        assertTrue(text.contains("COMPLETE outcome=found"), text)

        // One operation, one id: every line of the attempt correlates.
        val ids = Regex("\\[GEMINI\\]\\[DISCOVERY\\]\\[([0-9a-f]{6})\\]")
            .findAll(text)
            .map { match -> match.groupValues[1] }
            .toSet()
        assertEquals(1, ids.size, text)
    }

    @Test
    fun `gemini authorization failure is logged with its status`() = runBlocking {
        val sink = RecordingLogSink()
        val discovery = geminiDiscovery(sink) {
            HttpResponseSpec(401, """{"error":{"message":"API key not valid"}}""")
        }

        val result = discovery.discoverKnown(KnownModelProviders.gemini, credential = GEMINI_KEY)

        assertEquals(
            "AUTHENTICATION_REQUIRED",
            assertIs<DiscoveryResult.Failed>(result).kind.name,
        )
        val text = sink.text()
        assertTrue(text.contains("RESPONSE status=401"), text)
        assertTrue(text.contains("success=false"), text)
        assertTrue(text.contains("kind=AUTHENTICATION_REQUIRED"), text)
        assertTrue(text.contains("ERROR"), text)
        assertFalse(text.contains(GEMINI_KEY), text)
    }

    @Test
    fun `gemini malformed body is logged as a parse failure`() = runBlocking {
        val sink = RecordingLogSink()
        val discovery = geminiDiscovery(sink) { HttpResponseSpec(200, "<html>not json</html>") }

        val result = discovery.discoverKnown(KnownModelProviders.gemini, credential = GEMINI_KEY)

        assertEquals("UNSUPPORTED", assertIs<DiscoveryResult.Failed>(result).kind.name)
        val text = sink.text()
        assertTrue(text.contains("PARSE_FAIL"), text)
        assertTrue(text.contains("fieldPath=none"), text)
        // A body that is not structured JSON carries no diagnostic value; only
        // structural information is written.
        assertTrue(text.contains("preview=omitted"), text)
        assertFalse(text.contains("<html>"), text)
    }

    @Test
    fun `gemini model name prefix is normalized and reported`() = runBlocking {
        val sink = RecordingLogSink()
        val discovery = geminiDiscovery(sink) {
            HttpResponseSpec(200, geminiModelsJson("gemini-3.1-flash", "gemini-2.0-flash"))
        }

        val result = discovery.discoverKnown(
            spec = KnownModelProviders.gemini,
            credential = GEMINI_KEY,
            preferredModelId = "gemini-3.1-flash",
        )

        val found = assertIs<DiscoveryResult.Found>(result)
        // The catalog the app stores and the picker show carries bare ids.
        assertEquals(listOf("gemini-3.1-flash", "gemini-2.0-flash"), found.api.modelIds)
        val text = sink.text()
        assertTrue(
            text.contains("MODEL raw=models/gemini-3.1-flash normalized=gemini-3.1-flash accepted=true"),
            text,
        )
        // The `models/` prefix the Gemini API reports is not a distinct model id.
        assertTrue(text.contains("normalizedFromPrefix=true"), text)
    }

    @Test
    fun `gemini utility models are reported as filtered out of auto-selection`() = runBlocking {
        val sink = RecordingLogSink()
        val discovery = geminiDiscovery(sink) {
            HttpResponseSpec(
                200,
                geminiModelsJson("gemini-2.0-flash", "gemini-2.0-flash-lite", "text-embedding-004"),
            )
        }

        val result = discovery.discoverKnown(
            spec = KnownModelProviders.gemini,
            credential = GEMINI_KEY,
            preferredModelId = "gemini-2.0-flash",
        )

        assertIs<DiscoveryResult.Found>(result)
        val text = sink.text()
        assertTrue(text.contains("FILTER id=text-embedding-004 reason=utility-model"), text)
        assertTrue(text.contains("catalog=3"), text)
        assertTrue(text.contains("reason=preferred"), text)
    }

    @Test
    fun `an entry without a usable id is reported as rejected, not silently dropped`() = runBlocking {
        val sink = RecordingLogSink()
        val discovery = geminiDiscovery(sink) {
            HttpResponseSpec(200, """{"models":[{"displayName":"no id here"},{"name":"models/gemini-2.0-flash"}]}""")
        }

        val result = discovery.discoverKnown(KnownModelProviders.gemini, credential = GEMINI_KEY)

        assertIs<DiscoveryResult.Found>(result)
        val text = sink.text()
        assertTrue(text.contains("MODEL raw=none normalized=- accepted=false reason=missing-id"), text)
        assertTrue(text.contains("PARSE fieldPath=models rawModels=2"), text)
    }

    @Test
    fun `gemini 404 falls back to the compatibility list and says so`() = runBlocking {
        val sink = RecordingLogSink()
        val discovery = geminiDiscovery(sink) { HttpResponseSpec(404, "") }

        val result = discovery.discoverKnown(KnownModelProviders.gemini, credential = GEMINI_KEY)

        val found = assertIs<DiscoveryResult.Found>(result)
        assertTrue(found.api.catalogFallback)
        val text = sink.text()
        assertTrue(text.contains("FALLBACK"), text)
        assertTrue(text.contains("reason=model-list-not-found"), text)
        assertTrue(text.contains("COMPLETE outcome=found"), text)
        assertTrue(text.contains("fallback=true"), text)
    }

    // --- Groq ---------------------------------------------------------------

    @Test
    fun `groq discovery logs the equivalent stages`() = runBlocking {
        val sink = RecordingLogSink()
        val transport = FakeHttpTransport(
            executeHandler = { request: HttpRequestSpec ->
                assertEquals("https://api.groq.com/openai/v1/models", request.url)
                assertEquals("Bearer $GROQ_KEY", request.headers["Authorization"])
                HttpResponseSpec(200, openAiModelsJson("llama-3.3-70b-versatile", "llama-3.1-8b-instant"))
            },
        )

        val result = ModelApiDiscovery(transport, logger = recordingLogger(sink)).discoverKnown(
            spec = KnownModelProviders.groq,
            credential = GROQ_KEY,
        )

        assertIs<DiscoveryResult.Found>(result)
        val text = sink.text()
        assertTrue(text.contains("[GROQ][DISCOVERY]"), text)
        assertTrue(text.contains("path=api.groq.com/openai/v1/models"), text)
        assertTrue(text.contains("authScheme=bearer"), text)
        assertTrue(text.contains("RESPONSE status=200"), text)
        assertTrue(text.contains("PARSE fieldPath=data rawModels=2"), text)
        assertTrue(
            text.contains("MODEL raw=llama-3.3-70b-versatile normalized=llama-3.3-70b-versatile accepted=true"),
            text,
        )
        assertTrue(text.contains("COMPLETE outcome=found"), text)
        assertFalse(text.contains(GROQ_KEY), text)
        assertFalse(text.contains("Bearer"), text)
    }

    @Test
    fun `groq rate limiting is logged with its status and retry hint`() = runBlocking {
        val sink = RecordingLogSink()
        val transport = FakeHttpTransport(
            executeHandler = {
                HttpResponseSpec(
                    statusCode = 429,
                    body = """{"error":{"message":"rate limit reached"}}""",
                    headers = mapOf(
                        "Retry-After" to listOf("2"),
                        "x-ratelimit-remaining-requests" to listOf("0"),
                    ),
                )
            },
        )

        val result = ModelApiDiscovery(transport, logger = recordingLogger(sink)).discoverKnown(
            spec = KnownModelProviders.groq,
            credential = GROQ_KEY,
        )

        assertEquals("RATE_LIMITED", assertIs<DiscoveryResult.Failed>(result).kind.name)
        val text = sink.text()
        assertTrue(text.contains("RESPONSE status=429"), text)
        assertTrue(text.contains("kind=RATE_LIMITED"), text)
        assertTrue(text.contains("retryAfter=2"), text)
        assertTrue(text.contains("rateLimitHeaders=retry-after,x-ratelimit-remaining-requests"), text)
    }

    @Test
    fun `groq models are exposed on the openai-compatible field path`() = runBlocking {
        val sink = RecordingLogSink()
        val transport = FakeHttpTransport(
            executeHandler = { HttpResponseSpec(200, """{"object":"list","data":[]}""") },
        )

        val result = ModelApiDiscovery(transport, logger = recordingLogger(sink)).discoverKnown(
            spec = KnownModelProviders.groq,
            credential = GROQ_KEY,
        )

        // An empty parsed catalog is reported, never silently turned into success.
        assertEquals("MALFORMED", assertIs<DiscoveryResult.Failed>(result).kind.name)
        assertTrue(sink.text().contains("reason=no-models"), sink.text())
    }

    // --- provider requests --------------------------------------------------

    @Test
    fun `a gemini completion logs the request shape and the response`() = runBlocking {
        val sink = RecordingLogSink()
        val transport = FakeHttpTransport(
            response = HttpResponseSpec(
                statusCode = 200,
                body = SUCCESS_RESPONSE,
                headers = mapOf(
                    "Content-Type" to listOf("application/json; charset=utf-8"),
                    "x-request-id" to listOf("req-42"),
                ),
            ),
        )
        val provider = OpenAiCompatibleProvider(id = "gemini", transport = transport, logger = recordingLogger(sink))

        provider.complete(
            request(
                openAiConfig(
                    providerId = "gemini",
                    baseUrl = GEMINI_ROOT,
                    model = "gemini-2.0-flash",
                    apiKey = GEMINI_KEY,
                ),
                ModelMessage.user("hello"),
            ),
        )

        val text = sink.text()
        assertTrue(text.contains("[GEMINI][COMPLETION]"), text)
        assertTrue(text.contains("REQUEST method=POST"), text)
        assertTrue(text.contains("path=generativelanguage.googleapis.com/v1beta/openai/chat/completions"), text)
        assertTrue(text.contains("purpose=normal-completion"), text)
        assertTrue(text.contains("messageCount=1"), text)
        assertTrue(text.contains("toolCount=0"), text)
        assertTrue(text.contains("toolCalling=NO"), text)
        assertTrue(text.contains("stream=false"), text)
        // Configuration is reported as a flag, never as the credential itself.
        assertTrue(text.contains("hasApiKey=YES"), text)
        assertTrue(text.contains("RESPONSE status=200"), text)
        assertTrue(text.contains("contentType=application/json"), text)
        assertTrue(text.contains("requestId=req-42"), text)
        assertTrue(text.contains("success=true"), text)
        assertTrue(text.contains("COMPLETE"), text)
        assertFalse(text.contains(GEMINI_KEY), text)
        assertFalse(text.contains(GEMINI_KEY.substring(0, 4)), text)
    }

    @Test
    fun `a groq API error is logged without echoing the credential`() = runBlocking {
        val sink = RecordingLogSink()
        val transport = FakeHttpTransport(
            response = HttpResponseSpec(
                statusCode = 401,
                body = """{"error":{"message":"Incorrect API key provided: $GROQ_KEY","type":"invalid_request_error"}}""",
            ),
        )
        val provider = OpenAiCompatibleProvider(id = "groq", transport = transport, logger = recordingLogger(sink))

        assertFailsWith<ModelProviderError> {
            provider.complete(
                request(
                    openAiConfig(providerId = "groq", baseUrl = "https://api.groq.com/openai/v1", model = "llama-3.3-70b-versatile", apiKey = GROQ_KEY),
                    ModelMessage.user("hello"),
                ),
            )
        }

        val text = sink.text()
        assertTrue(text.contains("[GROQ][COMPLETION]"), text)
        assertTrue(text.contains("RESPONSE status=401"), text)
        assertTrue(text.contains("success=false"), text)
        assertTrue(text.contains("ERROR"), text)
        assertTrue(text.contains("kind=AUTHENTICATION_FAILED"), text)
        assertTrue(text.contains("status=401"), text)
        assertTrue(text.contains("providerErrorType=invalid_request_error"), text)
        // The provider echoed the key back inside its error message; it must not
        // survive into the log.
        assertFalse(text.contains(GROQ_KEY), text)
        assertTrue(text.contains("[redacted]"), text)
    }

    // --- connection verification -------------------------------------------

    @Test
    fun `connection verification logs the preset identity and the outcome`() = runBlocking {
        val sink = RecordingLogSink()
        val transport = FakeHttpTransport(response = HttpResponseSpec(200, SUCCESS_RESPONSE))
        val logger = recordingLogger(sink)
        val probe = ChatCapabilityProbe(
            gateway = DefaultModelGateway(),
            providerFactory = { preset ->
                DefaultModelProviderFactory(transport, logger).create(preset)
            },
            logger = logger,
        )

        val result = probe.verify(
            preset = geminiPreset(),
            rootUrl = GEMINI_ROOT,
            apiBasePath = "",
            modelId = "gemini-2.0-flash",
            credential = GEMINI_KEY,
        )

        assertTrue(result.succeeded, result.message)
        val text = sink.text()
        assertTrue(text.contains("[GEMINI][CONNECTION]"), text)
        assertTrue(text.contains("operation=connection-verification"), text)
        assertTrue(text.contains("hasApiKey=YES"), text)
        assertTrue(text.contains("maxOutputTokens=1"), text)
        assertTrue(text.contains("COMPLETE outcome=ok"), text)
        assertFalse(text.contains(GEMINI_KEY), text)
    }

    // --- redaction ----------------------------------------------------------

    @Test
    fun `a credential echoed in a response body is redacted from a preview`() {
        val preview = assertNotNull(
            safeResponsePreview("""{"error":{"message":"bad key AIza-test-key-0123456789"}}"""),
        )

        assertFalse(preview.contains("AIza"), preview)
        assertTrue(preview.contains("[redacted]"), preview)
    }

    @Test
    fun `redaction covers bearer tokens and groq keys`() {
        val text = sanitizeForLog("Authorization: Bearer gsk_testkey0123456789")

        assertFalse(text.contains("gsk_"), text)
        assertFalse(text.contains("Bearer gsk"), text)
        assertTrue(text.contains("[redacted]"), text)
    }

    @Test
    fun `an unstructured or oversized body is never previewed`() {
        assertNull(safeResponsePreview("<html>nope</html>"))
        assertNull(safeResponsePreview("{" + "a".repeat(5_000) + "}"))
        assertNull(safeResponsePreview("   "))
    }

    // --- opt-in and existing behaviour -------------------------------------

    @Test
    fun `a discovery without a logger records nothing`() = runBlocking {
        val sink = RecordingLogSink()

        ModelApiDiscovery(FakeHttpTransport(executeHandler = { HttpResponseSpec(200, openAiModelsJson("only")) }))
            .discover("https://host.example/v1")

        assertTrue(sink.records.isEmpty(), sink.text())
    }

    @Test
    fun `existing connection logging still writes through the same logger`() {
        val sink = RecordingLogSink()
        val registry = GatewayModelConnectionRegistry(
            gateway = DefaultModelGateway(),
            logger = recordingLogger(sink),
        )

        registry.connect(
            preset = geminiPreset(),
            endpoint = ModelEndpoint("https://host.example", EndpointSource.CONFIGURED),
            credential = null,
        )

        assertTrue(sink.contains("Active model connection updated"), sink.text())
        assertTrue(sink.contains("provider=gemini"), sink.text())
        assertFalse(sink.contains(GEMINI_KEY), sink.text())
    }

    private fun geminiPreset(): ModelPreset = colabPreset(
        id = "gemini",
        name = "Google Gemini",
        model = "gemini-2.0-flash",
    ).copy(setupKind = "gemini", providerType = ModelProviderType.REMOTE_OPENAI_COMPATIBLE)
}
