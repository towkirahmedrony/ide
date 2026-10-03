package com.agentx.app.model.catalog

import com.agentx.app.core.ForgeError
import com.agentx.app.core.ForgeErrorCode
import com.agentx.app.core.ForgeResult
import com.agentx.app.core.failure
import com.agentx.app.core.success
import com.agentx.app.core.logging.ForgeLogger
import com.agentx.app.core.logging.ForgeLoggers
import com.agentx.app.core.logging.LogLevel
import com.agentx.app.model.ModelCapabilities
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.capability.InMemoryModelCapabilityRegistry
import com.agentx.app.model.capability.ModelCapabilityProfile
import com.agentx.app.model.capability.ModelCapabilityRegistry
import com.agentx.app.model.http.HttpRequestSpec
import com.agentx.app.model.http.HttpTransport
import com.agentx.app.model.http.UrlConnectionHttpTransport
import com.agentx.app.model.json.JsonCodec
import com.agentx.app.model.json.JsonObject
import com.agentx.app.model.json.arrayOrNull
import com.agentx.app.model.json.booleanOrNull
import com.agentx.app.model.json.numberOrNull
import com.agentx.app.model.json.objectOrNull
import com.agentx.app.model.json.stringOrNull
import com.agentx.app.model.connect.KnownModelProviders
import com.agentx.app.model.connect.KnownProviderSpec
import com.agentx.app.model.connect.ModelListAuth
import com.agentx.app.model.connect.ModelSetupKind
import com.agentx.app.model.connect.diagnosticPath
import com.agentx.app.model.connect.normalizeModelId
import com.agentx.app.model.connect.originOf
import com.agentx.app.model.preset.ModelProviderIds
import kotlin.coroutines.cancellation.CancellationException

/** Path, relative to a Groq API base URL, of the OpenAI-compatible model list. */
object GroqModelCatalog {
    const val PROVIDER_ID: String = ModelProviderIds.GROQ

    /**
     * Groq's model list, as documented on the host: `<host>/openai/v1/models`.
     * The provider base URL already ends in `/openai/v1`, so the endpoint the
     * catalog calls is `<baseUrl>/models`.
     */
    const val MODELS_PATH_FROM_HOST: String = "/openai/v1/models"

    fun modelsUrl(baseUrl: String): String = baseUrl.trimEnd('/') + "/models"
}

/**
 * Gemini's model list.
 *
 * The chat endpoint is the OpenAI-compatible surface
 * (`https://generativelanguage.googleapis.com/v1beta/openai`), but that surface
 * has no `/models` route: asking it for one answers 404, which is why the
 * provider appeared to have no models to discover. The list is served by the
 * Gemini API itself, at `/v1beta/models`, authenticated with the documented key
 * header. The URL is built from the connection's own host and the path declared
 * by the provider catalogue, so a preset pointed at a proxy lists through that
 * proxy and no host is hardcoded here.
 */
object GeminiModelCatalog {
    const val PROVIDER_ID: String = ModelProviderIds.GEMINI

    /** Gemini's own model list, relative to the provider's host. */
    const val MODELS_PATH_FROM_HOST: String = "/v1beta/models"

    fun modelsUrl(baseUrl: String): String = originOf(baseUrl) + MODELS_PATH_FROM_HOST
}

/**
 * A catalog fetched from a provider's OpenAI-compatible model-list endpoint.
 *
 * Caching: a snapshot is reused until [ttlMillis] has elapsed, so the catalog is
 * never fetched before every inference request. A refresh merges the fetched
 * models with the previous snapshot: a model that has disappeared is kept and
 * marked unavailable instead of being silently dropped, which lets Settings show
 * a saved role assignment as unavailable rather than quietly replacing it.
 */
class RemoteModelCatalog(
    override val providerId: String,
    /** Produces the model-list URL, or null when the provider is not connected. */
    private val modelsUrl: () -> String?,
    /** Produces the credential, or null for an unauthenticated endpoint. */
    private val credential: () -> String? = { null },
    /**
     * Where the credential goes. A hosted compatible API takes a bearer token;
     * a provider's own API may document a dedicated key header (Gemini). The key
     * never travels in the URL, so it cannot leak through a logged address.
     */
    private val authHeaderName: String = "Authorization",
    private val authScheme: String = "Bearer ",
    private val transport: HttpTransport = UrlConnectionHttpTransport(),
    private val store: ModelCatalogStore = InMemoryModelCatalogStore(),
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val connectTimeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    private val logger: ForgeLogger = ForgeLoggers.create(
        level = LogLevel.INFO,
        baseFields = mapOf("component" to "model-catalog"),
    ),
    private val capabilityRegistry: ModelCapabilityRegistry = InMemoryModelCapabilityRegistry(),
) : ModelCatalog {

    private val lock = Any()

    @Volatile
    private var snapshot: ModelCatalogSnapshot? = null

    override fun cached(): ModelCatalogSnapshot? = snapshot

    override fun isStale(nowMillis: Long): Boolean {
        val current = snapshot ?: return true
        return nowMillis - current.fetchedAtMillis > ttlMillis
    }

    suspend fun restore() {
        if (snapshot != null) return
        val stored = runCatching { store.load(providerId) }.getOrNull() ?: return
        synchronized(lock) {
            if (snapshot == null) snapshot = stored
        }
        registerCatalogModels(stored.models)
    }

    /**
     * Seeds a rebuilt catalog with the snapshot of the catalog it replaces.
     *
     * A catalog is rebuilt when the provider's connection changed (a corrected API
     * key, a new endpoint). Carrying the previous list over means a refresh that
     * then fails still leaves the last list the provider actually reported, instead
     * of dropping the picker back to a built-in list. Never overwrites a snapshot
     * this catalog already has.
     */
    internal fun adoptSnapshot(previous: ModelCatalogSnapshot?) {
        if (previous == null) return
        synchronized(lock) {
            if (snapshot == null) snapshot = previous
        }
        registerCatalogModels(previous.models)
    }

    override suspend fun refresh(force: Boolean): ForgeResult<ModelCatalogSnapshot, ForgeError> {
        restore()
        val now = clock()
        val existing = snapshot
        if (!force && existing != null && now - existing.fetchedAtMillis <= ttlMillis) {
            return success(existing.copy(source = CatalogSource.CACHE))
        }

        val url = modelsUrl()
        if (url.isNullOrBlank()) return failure(unavailable("The provider is not connected."))

        val headers = LinkedHashMap<String, String>()
        headers["Accept"] = "application/json"
        credential()?.takeIf { it.isNotBlank() }?.let { headers[authHeaderName] = "$authScheme$it" }

        val response = try {
            transport.execute(
                HttpRequestSpec(
                    method = "GET",
                    url = url,
                    headers = headers,
                    connectTimeoutMillis = connectTimeoutMillis,
                    readTimeoutMillis = readTimeoutMillis,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return failure(unavailable("Could not reach the model list: ${error.message ?: "network error"}"))
        }

        if (!response.isSuccess) {
            logger.warn(
                "Model list request failed",
                mapOf(
                    "providerId" to providerId,
                    "path" to diagnosticPath(url),
                    "httpStatus" to response.statusCode,
                    "count" to response.body.length,
                ),
            )
            return failure(
                unavailable("The model list endpoint returned HTTP ${response.statusCode}.", response.statusCode),
            )
        }

        val parsed = parseModels(response.body)
        if (parsed == null) {
            logger.warn(
                "Model list response could not be parsed",
                mapOf(
                    "providerId" to providerId,
                    "path" to diagnosticPath(url),
                    "httpStatus" to response.statusCode,
                    "reason" to "the body is not a model list",
                ),
            )
            return failure(unavailable("The model list response could not be parsed."))
        }
        logger.info(
            "Model list received",
            mapOf(
                "providerId" to providerId,
                "path" to diagnosticPath(url),
                "httpStatus" to response.statusCode,
                "received" to parsed.received,
                "accepted" to parsed.models.size,
                "rejected" to parsed.rejected.size,
                "rejectionReasons" to parsed.rejected.take(REJECTION_SAMPLE_SIZE),
            ),
        )
        // The final handoff to the Settings/model picker: what the provider
        // reported, what the text runtime rejected, and what the picker will
        // actually offer. The ids are model names only — never a credential.
        val exposed = parsed.models.filter { it.available }
        logger.info(
            "[${providerLabel()}][CATALOG] discovered=${parsed.received} " +
                "filtered=${parsed.rejected.size} exposedToUi=${exposed.size}",
        )
        logger.info("[${providerLabel()}][CATALOG] uiModels=${exposed.joinToString(",") { it.id }}")
        // An answer that parsed but yielded nothing usable is reported as such: it
        // must never look like "this provider has no models", and the caller must
        // not present its built-in fallback as if discovery had returned these.
        if (parsed.received == 0) {
            return failure(unavailable("The model list response contained no models."))
        }
        if (parsed.models.isEmpty()) {
            val reasons = parsed.rejected.take(REJECTION_SAMPLE_SIZE).joinToString("; ")
            return failure(
                unavailable(
                    "The provider listed ${parsed.received} models, but none of them can run text " +
                        "generation. Rejected: $reasons",
                ),
            )
        }

        val merged = merge(existing, parsed.models)
        val fresh = ModelCatalogSnapshot(
            providerId = providerId,
            models = merged,
            fetchedAtMillis = now,
            source = CatalogSource.REMOTE,
        )
        synchronized(lock) { snapshot = fresh }
        registerCatalogModels(merged)
        runCatching { store.save(fresh) }
        return success(fresh)
    }

    // --- parsing -----------------------------------------------------------

    /** What a model list contained: what survived, and why the rest did not. */
    private data class ParsedCatalog(
        val models: List<CatalogModel>,
        val received: Int,
        val rejected: List<String>,
    )

    private data class ParsedModel(
        val model: CatalogModel?,
        val id: String,
        val rejection: String? = null,
    )

    /**
     * Parses a provider's model list, keeping only models this runtime can drive.
     *
     * Two shapes are accepted, because providers answer with either: the
     * OpenAI-compatible `data` array, and the provider's own `models` array
     * (Gemini's model list reports `models/<id>`, a display name and the token
     * limits). A missing field stays null — nothing about a model is invented.
     */
    private fun parseModels(body: String): ParsedCatalog? {
        val root = runCatching { JsonCodec.parse(body).objectOrNull() }.getOrNull() ?: return null
        val entries = root.arrayOrNull("data") ?: root.arrayOrNull("models") ?: return null
        val parsed = entries.mapNotNull { item -> item.objectOrNull()?.let(::parseModel) }
        return ParsedCatalog(
            models = parsed.mapNotNull { it.model },
            received = parsed.size,
            rejected = parsed.mapNotNull { entry -> entry.rejection?.let { "${entry.id}: $it" } },
        )
    }

    private fun parseModel(model: JsonObject): ParsedModel {
        val rawId = model.stringOrNull("id") ?: model.stringOrNull("name")
        // Gemini reports `models/<id>`; the id sent to chat is the bare one.
        val id = rawId?.let(::normalizeModelId).orEmpty()
        if (id.isEmpty()) return ParsedModel(null, UNNAMED_MODEL, "the entry has no id or name")
        val methods = stringList(model, "supportedGenerationMethods", "supported_generation_methods")
        if (!isRunnableTextModel(id, methods)) {
            return ParsedModel(
                model = null,
                id = id,
                rejection = if (methods.isEmpty()) {
                    "not a text-generation model"
                } else {
                    "does not report $GENERATE_CONTENT_METHOD (${methods.joinToString("/")})"
                },
            )
        }
        val deprecated = deprecationOf(model)
        val catalogModel = CatalogModel(
            id = id,
            displayName = (model.stringOrNull("displayName") ?: model.stringOrNull("display_name"))
                ?.takeIf { it.isNotBlank() },
            contextWindowTokens = firstInt(
                model,
                "context_window",
                "context_window_tokens",
                "max_context",
                "inputTokenLimit",
                "input_token_limit",
            ),
            maxOutputTokens = firstInt(
                model,
                "max_output_tokens",
                "max_completion_tokens",
                "outputTokenLimit",
                "output_token_limit",
            ),
            capabilities = capabilitiesFor(id),
            deprecated = deprecated,
            available = (model.booleanOrNull("active") ?: true) && deprecated != true,
            providerOwnedBy = model.stringOrNull("owned_by")?.takeIf { it.isNotBlank() },
            createdAtMillis = model.numberOrNull("created")?.toLong(),
        )
        return ParsedModel(model = catalogModel, id = id)
    }

    /**
     * Keeps models the text agent runtime can drive.
     *
     * Provider-reported generation methods win when present: the runtime needs
     * text generation. Without them the identifier is filtered against the known
     * non-text families, which is the documented behaviour for every provider.
     */
    private fun isRunnableTextModel(id: String, methods: List<String>): Boolean {
        // The provider's own generation methods are proof, so they decide alone: a
        // model that reports text generation is never dropped on its name, which
        // keeps a newly released family (whose name nothing recognises yet) usable.
        if (methods.isNotEmpty()) {
            return methods.any { it.equals(GENERATE_CONTENT_METHOD, ignoreCase = true) }
        }
        if (!isUsableTextModel(id)) return false
        if (providerId == ModelProviderIds.GEMINI) {
            val lower = id.lowercase()
            return GEMINI_NON_TEXT_MARKERS.none { lower.contains(it) }
        }
        return true
    }

    /** A provider-reported deprecation, or null when the provider did not say. */
    private fun deprecationOf(model: JsonObject): Boolean? {
        model.booleanOrNull("deprecated")?.let { return it }
        model.booleanOrNull("retired")?.let { return it }
        return when (val status = model.stringOrNull("status")?.lowercase()) {
            null -> null
            else -> when {
                status.contains("deprecat") || status.contains("retired") || status.contains("shutdown") -> true
                status.contains("active") || status.contains("available") -> false
                else -> null
            }
        }
    }

    private fun stringList(model: JsonObject, vararg keys: String): List<String> {
        keys.forEach { key ->
            val array = model.arrayOrNull(key) ?: return@forEach
            return array.mapNotNull { it.stringOrNull() }
        }
        return emptyList()
    }

    /**
     * A previously known model that is absent from [fetched] is retained with
     * `available = false`. Nothing is removed, so a role assignment never
     * silently points at a different model.
     */
    private fun merge(previous: ModelCatalogSnapshot?, fetched: List<CatalogModel>): List<CatalogModel> {
        if (previous == null) return fetched
        val fetchedIds = fetched.map { it.id }.toHashSet()
        val removed = previous.models
            .filter { it.id !in fetchedIds }
            .map { it.copy(available = false) }
        return fetched + removed
    }

    /** The uppercase provider identity used as a log tag. Never a credential. */
    private fun providerLabel(): String = providerId.uppercase()

    private fun capabilitiesFor(id: String): ModelCapabilities {
        val known = capabilityRegistry.get(providerId, id)
        if (known != null && known.known && known.enabled) return known.toModelCapabilities()
        // Discovered models stay listed, but unknown ones are never treated as
        // tool-capable until a definition is registered.
        return ModelCapabilities(streaming = true, toolCalling = false, systemMessages = true)
    }

    /**
     * Identity-only registration. Catalog listing is not capability proof:
     * discovered models enter the registry with unknown support so absence
     * from the hardcoded overlay is no longer an eligibility allowlist.
     */
    private fun registerCatalogModels(models: List<CatalogModel>) {
        models.forEach { model ->
            capabilityRegistry.registerOrUpdate(
                ModelCapabilityProfile.discovered(
                    providerId = providerId,
                    modelId = model.id,
                    displayName = model.displayName,
                    maxContextTokens = model.contextWindowTokens,
                    maxOutputTokens = model.maxOutputTokens,
                ),
            )
        }
    }

    private fun firstInt(model: JsonObject, vararg keys: String): Int? {
        keys.forEach { key -> model.numberOrNull(key)?.let { return it.toInt() } }
        return null
    }

    private fun unavailable(message: String, httpStatus: Int? = null): ForgeError = ForgeError(
        code = ForgeErrorCode.MODEL_CATALOG_UNAVAILABLE,
        message = message,
        details = mutableMapOf<String, Any?>("providerId" to providerId).apply {
            httpStatus?.let { put("httpStatus", it) }
        },
    )

    companion object {
        /** Fifteen minutes: long enough that inference never refetches the list. */
        const val DEFAULT_TTL_MILLIS: Long = 15L * 60L * 1_000L
        const val DEFAULT_TIMEOUT_MILLIS: Int = 15_000

        /** The generation method that means "this model does text chat". */
        const val GENERATE_CONTENT_METHOD: String = "generateContent"

        /** How many rejection reasons are reported, so a log stays readable. */
        const val REJECTION_SAMPLE_SIZE: Int = 3

        private const val UNNAMED_MODEL: String = "(unnamed)"

        /**
         * Gemini families that are not text-agent models: embeddings, image and
         * video generation, speech/audio synthesis, transcription, music, and
         * question answering. Applied only when the provider reports no generation
         * methods, and only to Gemini, so the Groq list is filtered exactly as
         * before. A model the API reports as text-generating is never dropped on
         * its name, so a newly released text family stays usable.
         */
        val GEMINI_NON_TEXT_MARKERS: List<String> = listOf(
            "embedding",
            "imagen",
            "image",
            "veo",
            "tts",
            "transcribe",
            "speech",
            "audio",
            "lyria",
            "rerank",
            "guard",
            "moderation",
            "aqa",
        )

        /**
         * Groq's list also returns speech, guard and embedding models that the
         * AgentX text runtime cannot drive. Those are not exposed as choices.
         */
        fun isUsableTextModel(id: String): Boolean {
            val lower = id.lowercase()
            val excluded = listOf(
                "whisper",
                "tts",
                "playai-tts",
                "embed",
                "guard",
                "moderation",
                "rerank",
                "speech",
                "dall-e",
            )
            return excluded.none { lower.contains(it) }
        }
    }
}

/**
 * Builds a catalog per connected provider identity. Catalogs are created lazily
 * from the live connections, so a provider that is connected later (or
 * reconnected) simply gets a fresh catalog; nothing is hardcoded to a model list.
 */
interface ModelCatalogRegistry {
    /** Provider identities that currently have a catalog. */
    fun providers(): List<String>

    fun catalog(providerId: String): ModelCatalog?

    /** Cached snapshot; never triggers a network request. */
    fun snapshot(providerId: String): ModelCatalogSnapshot?

    /** Available (non-deprecated) models for [providerId]; empty when unknown. */
    fun availableModels(providerId: String): List<CatalogModel>

    suspend fun refresh(providerId: String, force: Boolean = false): ForgeResult<ModelCatalogSnapshot, ForgeError>

    suspend fun refreshAll(force: Boolean = false): Map<String, ModelCatalogSnapshot>

    /** Loads persisted snapshots into memory; safe to call more than once. */
    suspend fun restore()
}

/** Factory so tests can supply a canned catalog. */
fun interface ModelCatalogFactory {
    fun create(providerId: String, connection: ModelConfig): ModelCatalog?
}

/**
 * Default factory: a connected provider gets a remote catalog pointed at its own
 * model list. Groq's is `<host>/openai/v1/models` (see [GroqModelCatalog]);
 * Gemini's is Gemini's own `/v1beta/models` (see [GeminiModelCatalog]), because
 * the OpenAI-compatible surface it chats on does not serve `/models`.
 *
 * Each provider keeps its own catalog, credential and cache: adding Gemini does
 * not change what Groq or a local endpoint resolves to.
 */
class RemoteModelCatalogFactory(
    private val transport: HttpTransport = UrlConnectionHttpTransport(),
    private val store: ModelCatalogStore = InMemoryModelCatalogStore(),
    private val ttlMillis: Long = RemoteModelCatalog.DEFAULT_TTL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Diagnostics for what a model list returned; never carries a credential. */
    private val catalogLogger: ForgeLogger = ForgeLoggers.create(
        level = LogLevel.INFO,
        baseFields = mapOf("component" to "model-catalog"),
    ),
    private val capabilityRegistry: ModelCapabilityRegistry = InMemoryModelCapabilityRegistry(),
) : ModelCatalogFactory {

    override fun create(providerId: String, connection: ModelConfig): ModelCatalog? {
        if (!supports(providerId)) return null
        val auth = KnownModelProviders.spec(ModelSetupKind.fromId(providerId))?.modelListAuth
            ?: ModelListAuth.BEARER
        return RemoteModelCatalog(
            providerId = providerId,
            modelsUrl = { modelsUrlFor(providerId, connection.baseUrl) },
            credential = { connection.apiKey },
            // Gemini's list is served by the Gemini API and takes the key in its own
            // header; the credential used for chat is unchanged.
            authHeaderName = auth.headerName,
            authScheme = auth.scheme,
            transport = transport,
            store = store,
            ttlMillis = ttlMillis,
            clock = clock,
            logger = catalogLogger,
            capabilityRegistry = capabilityRegistry,
        )
    }

    /**
     * Providers whose model list this layer understands.
     *
     * A local OpenAI-compatible endpoint is deliberately not included: it may not
     * expose a model list at all, and its models are configured by hand.
     */
    fun supports(providerId: String): Boolean =
        providerId == ModelProviderIds.GROQ || providerId == ModelProviderIds.GEMINI

    /**
     * The model-list endpoint for a provider.
     *
     * A provider that declares its own model-list path (Gemini) is asked there, on
     * the host of the connection itself, so `/models` is never appended to a
     * surface that does not serve it. Everything else keeps `<baseUrl>/models`.
     */
    fun modelsUrlFor(providerId: String, baseUrl: String): String {
        val spec = KnownModelProviders.spec(ModelSetupKind.fromId(providerId))
        if (spec?.modelListPath != null) return spec.modelListUrlFor(baseUrl)
        return when (providerId) {
            ModelProviderIds.GROQ -> GroqModelCatalog.modelsUrl(baseUrl)
            ModelProviderIds.GEMINI -> GeminiModelCatalog.modelsUrl(baseUrl)
            else -> baseUrl.trimEnd('/') + KnownProviderSpec.MODEL_LIST_PATH
        }
    }
}

class DefaultModelCatalogRegistry(
    private val connections: () -> Map<String, ModelConfig>,
    private val factory: ModelCatalogFactory = RemoteModelCatalogFactory(),
) : ModelCatalogRegistry {

    private val lock = Any()
    private val catalogs = LinkedHashMap<String, ModelCatalog>()

    /** The connection each catalog was built from, so a changed one is rebuilt. */
    private val origins = LinkedHashMap<String, ModelConfig>()

    override fun providers(): List<String> = synchronized(lock) { catalogs.keys.toList() }

    /**
     * The catalog for [providerId], created lazily from that provider's current
     * connection.
     *
     * A connection that changed (a corrected key, a different endpoint) rebuilds the
     * catalog, carrying the previous snapshot over, so a later refresh uses the new
     * credential instead of the one captured when the catalog was first created. A
     * provider that is no longer configured loses its catalog.
     */
    override fun catalog(providerId: String): ModelCatalog? = synchronized(lock) {
        val connection = connections()[providerId] ?: run {
            catalogs.remove(providerId)
            origins.remove(providerId)
            return null
        }
        val existing = catalogs[providerId]
        if (existing != null && origins[providerId] == connection) return existing
        val created = factory.create(providerId, connection) ?: return null
        (created as? RemoteModelCatalog)?.adoptSnapshot(existing?.cached())
        catalogs[providerId] = created
        origins[providerId] = connection
        created
    }

    override fun snapshot(providerId: String): ModelCatalogSnapshot? = catalog(providerId)?.cached()

    override fun availableModels(providerId: String): List<CatalogModel> =
        snapshot(providerId)?.availableModels().orEmpty()

    override suspend fun refresh(providerId: String, force: Boolean): ForgeResult<ModelCatalogSnapshot, ForgeError> {
        val target = catalog(providerId)
            ?: return failure(
                ForgeError(
                    code = ForgeErrorCode.MODEL_CATALOG_UNAVAILABLE,
                    message = "No model catalog is available for '$providerId'.",
                    details = mapOf("providerId" to providerId),
                ),
            )
        return target.refresh(force)
    }

    override suspend fun refreshAll(force: Boolean): Map<String, ModelCatalogSnapshot> {
        val result = LinkedHashMap<String, ModelCatalogSnapshot>()
        connections().keys.forEach { providerId ->
            val catalog = catalog(providerId) ?: return@forEach
            when (val refreshed = catalog.refresh(force)) {
                is ForgeResult.Success -> result[providerId] = refreshed.value
                is ForgeResult.Failure -> catalog.cached()?.let { result[providerId] = it }
            }
        }
        return result
    }

    override suspend fun restore() {
        synchronized(lock) { catalogs.values.toList() }.forEach { catalog ->
            (catalog as? RemoteModelCatalog)?.restore()
        }
    }
}
