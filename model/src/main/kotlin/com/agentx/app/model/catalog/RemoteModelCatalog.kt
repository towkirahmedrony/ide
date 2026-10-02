package com.agentx.app.model.catalog

import com.agentx.app.core.ForgeError
import com.agentx.app.core.ForgeErrorCode
import com.agentx.app.core.ForgeResult
import com.agentx.app.core.failure
import com.agentx.app.core.success
import com.agentx.app.model.ModelCapabilities
import com.agentx.app.model.ModelConfig
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
 * Gemini's model list, from the OpenAI-compatible surface the Gemini provider
 * already talks to.
 *
 * The provider's base URL is `https://generativelanguage.googleapis.com/v1beta/openai`
 * (root plus the preset's empty API base path), so the model list is
 * `<baseUrl>/models` — the same endpoint the provider's health check and the
 * connect-time discovery already call. No second HTTP client and no scraping:
 * discovery is the API the account is already authenticated against.
 */
object GeminiModelCatalog {
    const val PROVIDER_ID: String = ModelProviderIds.GEMINI

    /** Model list of the Gemini OpenAI-compatible surface. */
    const val MODELS_PATH_FROM_HOST: String = "/v1beta/openai/models"

    fun modelsUrl(baseUrl: String): String = baseUrl.trimEnd('/') + "/models"
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
    /** Produces the bearer credential, or null for an unauthenticated endpoint. */
    private val credential: () -> String? = { null },
    private val transport: HttpTransport = UrlConnectionHttpTransport(),
    private val store: ModelCatalogStore = InMemoryModelCatalogStore(),
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val connectTimeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
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
        credential()?.takeIf { it.isNotBlank() }?.let { headers["Authorization"] = "Bearer $it" }

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
            return failure(
                unavailable("The model list endpoint returned HTTP ${response.statusCode}.", response.statusCode),
            )
        }

        val fetched = parseModels(response.body)
        if (fetched == null) {
            return failure(unavailable("The model list response could not be parsed."))
        }

        val merged = merge(existing, fetched)
        val fresh = ModelCatalogSnapshot(
            providerId = providerId,
            models = merged,
            fetchedAtMillis = now,
            source = CatalogSource.REMOTE,
        )
        synchronized(lock) { snapshot = fresh }
        runCatching { store.save(fresh) }
        return success(fresh)
    }

    // --- parsing -----------------------------------------------------------

    /**
     * Parses a provider's model list, keeping only models this runtime can drive.
     *
     * Two shapes are accepted, because the same endpoint can answer with either:
     * the OpenAI-compatible `data` array, and the provider's own `models` array
     * (Gemini's surface reports a display name and the token limits there). A
     * missing field stays null — nothing is inferred from a model's name beyond
     * the documented text-model filter.
     */
    private fun parseModels(body: String): List<CatalogModel>? {
        val root = runCatching { JsonCodec.parse(body).objectOrNull() }.getOrNull() ?: return null
        val entries = root.arrayOrNull("data") ?: root.arrayOrNull("models") ?: return null
        return entries.mapNotNull { item -> item.objectOrNull()?.let(::parseModel) }
    }

    private fun parseModel(model: JsonObject): CatalogModel? {
        val rawId = model.stringOrNull("id") ?: model.stringOrNull("name") ?: return null
        // Gemini reports `models/<id>`; the id sent to chat is the bare one.
        val id = rawId.trim().removePrefix("models/").takeIf { it.isNotBlank() } ?: return null
        val methods = stringList(model, "supportedGenerationMethods", "supported_generation_methods")
        if (!isRunnableTextModel(id, methods)) return null
        val deprecated = deprecationOf(model)
        return CatalogModel(
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
    }

    /**
     * Keeps models the text agent runtime can drive.
     *
     * Provider-reported generation methods win when present: the runtime needs
     * text generation. Without them the identifier is filtered against the known
     * non-text families, which is the documented behaviour for every provider.
     */
    private fun isRunnableTextModel(id: String, methods: List<String>): Boolean {
        if (!isUsableTextModel(id)) return false
        if (methods.isNotEmpty()) {
            return methods.any { it.equals(GENERATE_CONTENT_METHOD, ignoreCase = true) }
        }
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

    private fun capabilitiesFor(id: String): ModelCapabilities {
        val lower = id.lowercase()
        // The AgentX text/model runtime needs streaming chat. Tool calling is not
        // assumed per model; it is only advertised when the identifier makes the
        // capability unambiguous rather than sold as a guess.
        val toolCalling = lower.contains("llama-3") || lower.contains("-tools") || lower.contains("function")
        return ModelCapabilities(streaming = true, toolCalling = toolCalling, systemMessages = true)
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

        /**
         * Gemini families that are not text-chat models: embeddings, image and
         * video generation, speech, and question answering. Applied only when the
         * provider reports no generation methods, and only to Gemini, so the
         * Groq list is filtered exactly as before.
         */
        val GEMINI_NON_TEXT_MARKERS: List<String> = listOf(
            "embedding",
            "imagen",
            "image",
            "veo",
            "tts",
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
 * `<baseUrl>/models` endpoint, which is the OpenAI-compatible model list. Groq's
 * endpoint is `<host>/openai/v1/models` (see [GroqModelCatalog]) and Gemini's is
 * `<host>/v1beta/openai/models` (see [GeminiModelCatalog]).
 *
 * Each provider keeps its own catalog, credential and cache: adding Gemini does
 * not change what Groq or a local endpoint resolves to.
 */
class RemoteModelCatalogFactory(
    private val transport: HttpTransport = UrlConnectionHttpTransport(),
    private val store: ModelCatalogStore = InMemoryModelCatalogStore(),
    private val ttlMillis: Long = RemoteModelCatalog.DEFAULT_TTL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) : ModelCatalogFactory {

    override fun create(providerId: String, connection: ModelConfig): ModelCatalog? {
        if (!supports(providerId)) return null
        return RemoteModelCatalog(
            providerId = providerId,
            modelsUrl = { modelsUrlFor(providerId, connection.baseUrl) },
            credential = { connection.apiKey },
            transport = transport,
            store = store,
            ttlMillis = ttlMillis,
            clock = clock,
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

    /** The model-list endpoint of a supported provider's base URL. */
    fun modelsUrlFor(providerId: String, baseUrl: String): String = when (providerId) {
        ModelProviderIds.GROQ -> GroqModelCatalog.modelsUrl(baseUrl)
        ModelProviderIds.GEMINI -> GeminiModelCatalog.modelsUrl(baseUrl)
        else -> baseUrl.trimEnd('/') + "/models"
    }
}

class DefaultModelCatalogRegistry(
    private val connections: () -> Map<String, ModelConfig>,
    private val factory: ModelCatalogFactory = RemoteModelCatalogFactory(),
) : ModelCatalogRegistry {

    private val lock = Any()
    private val catalogs = LinkedHashMap<String, ModelCatalog>()

    override fun providers(): List<String> = synchronized(lock) { catalogs.keys.toList() }

    override fun catalog(providerId: String): ModelCatalog? = synchronized(lock) {
        val connection = connections()[providerId] ?: return null
        catalogs.getOrPut(providerId) { factory.create(providerId, connection) ?: return null }
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
