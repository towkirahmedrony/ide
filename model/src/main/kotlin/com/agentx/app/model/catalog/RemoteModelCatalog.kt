package com.agentx.app.model.catalog

import com.agentx.app.core.ForgeError
import com.agentx.app.core.ForgeErrorCode
import com.agentx.app.core.ForgeResult
import com.agentx.app.core.failure
import com.agentx.app.core.logging.ForgeLogger
import com.agentx.app.core.logging.ForgeLoggers
import com.agentx.app.core.logging.LogLevel
import com.agentx.app.core.success
import com.agentx.app.model.ModelCapabilities
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.capability.InMemoryModelCapabilityRegistry
import com.agentx.app.model.capability.ModelCapabilityRegistry
import com.agentx.app.model.connect.DiscoveryFailureKind
import com.agentx.app.model.connect.KnownModelProviders
import com.agentx.app.model.connect.KnownProviderSpec
import com.agentx.app.model.connect.ModelListAuth
import com.agentx.app.model.connect.ModelSetupKind
import com.agentx.app.model.connect.diagnosticPath
import com.agentx.app.model.connect.originOf
import com.agentx.app.model.discovery.ModelDiscovery
import com.agentx.app.model.discovery.ModelDiscoveryOutcome
import com.agentx.app.model.discovery.ModelListParsing
import com.agentx.app.model.http.HttpRequestSpec
import com.agentx.app.model.http.HttpTransport
import com.agentx.app.model.http.UrlConnectionHttpTransport
import com.agentx.app.model.preset.ModelProviderIds
import com.agentx.app.model.provider.gemini.GeminiModelProvider
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
 * The chat endpoint is Gemini's own API (`<host>/v1beta/models/<model>:generateContent`),
 * and the list is served by the same API at `/v1beta/models`. The URL is built
 * from the connection's own host, so a preset pointed at a proxy lists through
 * that proxy and no host is hardcoded here. The path itself is owned by
 * [GeminiModelProvider], which is the provider that performs the discovery, so the
 * endpoint exists once.
 */
object GeminiModelCatalog {
    const val PROVIDER_ID: String = ModelProviderIds.GEMINI

    /** Gemini's own model list, relative to the provider's host. */
    const val MODELS_PATH_FROM_HOST: String = GeminiModelProvider.MODELS_PATH_FROM_HOST

    fun modelsUrl(baseUrl: String): String = originOf(baseUrl) + MODELS_PATH_FROM_HOST
}

/**
 * A catalog for one provider, fed by that provider's own model discovery and
 * persisted through [ModelCatalogStore].
 *
 * Where the model list comes from: a [discovery] source supplied by the caller is
 * the provider's own lookup ([com.agentx.app.model.discovery.ModelDiscovery]), and
 * it is asked first — that is the normal path, because the provider is the thing
 * that knows its protocol, its endpoint and its credential header. When no
 * provider discovery is supplied this catalog performs the list request itself,
 * which is how a host that has not wired a provider keeps working unchanged.
 *
 * What this layer owns: validating and normalizing what arrived, merging it with
 * the previous snapshot, registering identities into the Part 1 capability
 * registry, persisting the snapshot, and recording what the last attempt did.
 * Nothing here assigns a role or chooses a model.
 *
 * Caching: a snapshot is reused until [ttlMillis] has elapsed, so the catalog is
 * never fetched before every inference request. A refresh merges the discovered
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
     * Where the credential goes for this catalog's own list request. A hosted
     * compatible API takes a bearer token; a provider's own API may document a
     * dedicated key header (Gemini). The key never travels in the URL, so it cannot
     * leak through a logged address.
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
    /**
     * The provider's own discovery. When absent this catalog performs the list
     * request itself, so an existing host keeps working with no provider wired.
     */
    private val discovery: ModelDiscovery? = null,
) : ModelCatalog {

    private val lock = Any()

    @Volatile
    private var snapshot: ModelCatalogSnapshot? = null

    @Volatile
    private var lastState: ModelCatalogState = ModelCatalogState.Idle

    override fun cached(): ModelCatalogSnapshot? = snapshot

    /** What the last discovery attempt did: the UI distinguishes these states. */
    fun state(): ModelCatalogState = lastState

    override fun isStale(nowMillis: Long): Boolean {
        val current = snapshot ?: return true
        return nowMillis - current.fetchedAtMillis > ttlMillis
    }

    /**
     * Loads the persisted snapshot and re-registers its models.
     *
     * Catalog state is a cache of what the provider reported, so it must survive a
     * restart: without this, every restart made the picker fall back to a built-in
     * list and the capability registry forgot the identities a previous run had
     * discovered. Nothing here is a credential — a snapshot never holds one.
     */
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

    /**
     * Refresh: discover → validate/normalize → update the catalog → register →
     * persist. A failure of any kind leaves the previous snapshot exactly as it
     * was, and no other provider's models are substituted for this one's.
     */
    override suspend fun refresh(force: Boolean): ForgeResult<ModelCatalogSnapshot, ForgeError> {
        restore()
        val now = clock()
        val existing = snapshot
        if (!force && existing != null && now - existing.fetchedAtMillis <= ttlMillis) {
            return success(existing.copy(source = CatalogSource.CACHE))
        }

        val outcome = try {
            discovery?.discover() ?: fetchFromEndpoint()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            ModelDiscoveryOutcome.Failed(
                kind = DiscoveryFailureKind.UNKNOWN,
                message = "Could not reach the model list: ${error.message ?: "network error"}",
            )
        }

        return when (outcome) {
            // The provider publishes no list. Reported as its own state so a caller
            // never reads it as "this provider has no models", and never as a reason
            // to drop the models the user configured by hand.
            is ModelDiscoveryOutcome.Unavailable -> {
                logger.warn(
                    "Model discovery is unavailable",
                    mapOf("providerId" to providerId, "reason" to outcome.reason),
                )
                lastState = ModelCatalogState.Unavailable(outcome.message)
                failure(unavailable(outcome.message))
            }

            // A failed refresh is not a destructive one: the snapshot is untouched.
            is ModelDiscoveryOutcome.Failed -> {
                logger.warn(
                    "Model list request failed",
                    mapOf(
                        "providerId" to providerId,
                        "kind" to outcome.kind.name,
                        "httpStatus" to (outcome.httpStatus ?: "-"),
                        "path" to (modelsUrl()?.let(::diagnosticPath) ?: "-"),
                    ),
                )
                lastState = ModelCatalogState.Failed(
                    message = outcome.message,
                    kind = outcome.kind,
                    httpStatus = outcome.httpStatus,
                )
                failure(unavailable(outcome.message, outcome.httpStatus))
            }

            is ModelDiscoveryOutcome.Discovered -> accept(outcome, existing, now)
        }
    }

    /** Validates, merges, registers and persists one successful discovery. */
    private fun accept(
        discovered: ModelDiscoveryOutcome.Discovered,
        previous: ModelCatalogSnapshot?,
        now: Long,
    ): ForgeResult<ModelCatalogSnapshot, ForgeError> {
        val path = modelsUrl()?.let(::diagnosticPath) ?: "-"
        logger.info(
            "Model list received",
            mapOf(
                "providerId" to providerId,
                "path" to path,
                "httpStatus" to (discovered.httpStatus ?: HTTP_OK),
                "received" to discovered.reportedCount,
                "accepted" to discovered.models.size,
                "rejected" to discovered.rejected.size,
                "rejectionReasons" to discovered.rejected.take(ModelListParsing.REJECTION_SAMPLE_SIZE),
            ),
        )
        // An answer that parsed but yielded nothing usable is reported as such: it
        // must never look like "this provider has no models", and the caller must
        // not present its built-in fallback as if discovery had returned these.
        if (discovered.reportedCount == 0) {
            lastState = ModelCatalogState.Failed(
                message = "The model list response contained no models.",
                kind = DiscoveryFailureKind.MALFORMED,
            )
            return failure(unavailable("The model list response contained no models."))
        }
        val models = discovered.models.map { model -> model.toCatalogModel(::capabilitiesFor) }
        if (models.isEmpty()) {
            val reasons = discovered.rejected.take(ModelListParsing.REJECTION_SAMPLE_SIZE).joinToString("; ")
            val message = "The provider listed ${discovered.reportedCount} models, but none of them can run " +
                "text generation. Rejected: $reasons"
            lastState = ModelCatalogState.Failed(message = message, kind = DiscoveryFailureKind.MALFORMED)
            return failure(unavailable(message))
        }

        // The final handoff to the Settings/model picker: what the provider
        // reported, what the text runtime rejected, and what the picker will
        // actually offer. The ids are model names only — never a credential.
        val exposed = models.filter { it.available }
        logger.info(
            "[${providerLabel()}][CATALOG] discovered=${discovered.reportedCount} " +
                "filtered=${discovered.rejected.size} exposedToUi=${exposed.size}",
        )
        logger.info("[${providerLabel()}][CATALOG] uiModels=${exposed.joinToString(",") { it.id }}")

        val merged = merge(previous, models)
        val fresh = ModelCatalogSnapshot(
            providerId = providerId,
            models = merged,
            fetchedAtMillis = now,
            source = CatalogSource.REMOTE,
        )
        synchronized(lock) { snapshot = fresh }
        registerCatalogModels(merged)
        lastState = ModelCatalogState.Discovered(modelCount = merged.size, fetchedAtMillis = now)
        runCatching { store.save(fresh) }
        return success(fresh)
    }

    // --- fallback discovery ------------------------------------------------

    /**
     * This catalog's own model-list request, used when the host did not wire a
     * provider discovery. It is the same request the providers make, reported
     * through the same outcome, so the rest of the refresh behaves identically.
     */
    private suspend fun fetchFromEndpoint(): ModelDiscoveryOutcome {
        val url = modelsUrl()
        if (url.isNullOrBlank()) {
            return ModelDiscoveryOutcome.Unavailable(
                reason = ModelDiscoveryOutcome.REASON_NOT_CONNECTED,
                message = "The provider is not connected.",
            )
        }

        val headers = LinkedHashMap<String, String>()
        headers["Accept"] = "application/json"
        credential()?.takeIf { it.isNotBlank() }?.let { key -> headers[authHeaderName] = "$authScheme$key" }

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
            return ModelDiscoveryOutcome.Failed(
                kind = DiscoveryFailureKind.UNREACHABLE,
                message = "Could not reach the model list: ${error.message ?: "network error"}",
            )
        }

        if (!response.isSuccess) {
            return ModelDiscoveryOutcome.Failed(
                kind = kindForStatus(response.statusCode),
                message = "The model list endpoint returned HTTP ${response.statusCode}.",
                httpStatus = response.statusCode,
            )
        }

        val parsed = ModelListParsing.parse(response.body, providerId)
            ?: return ModelDiscoveryOutcome.Failed(
                kind = DiscoveryFailureKind.MALFORMED,
                message = "The model list response could not be parsed.",
            )
        return ModelDiscoveryOutcome.Discovered(
            models = parsed.models,
            reportedCount = parsed.reportedCount,
            rejected = parsed.rejected,
            httpStatus = response.statusCode,
        )
    }

    private fun kindForStatus(status: Int): DiscoveryFailureKind = when (status) {
        401, 403 -> DiscoveryFailureKind.AUTHENTICATION_REQUIRED
        404 -> DiscoveryFailureKind.NOT_FOUND
        408 -> DiscoveryFailureKind.TIMEOUT
        429 -> DiscoveryFailureKind.RATE_LIMITED
        in 500..599 -> DiscoveryFailureKind.SERVER_ERROR
        else -> DiscoveryFailureKind.UNSUPPORTED
    }

    // --- merging and registration ------------------------------------------

    /**
     * A previously known model that is absent from [fetched] is retained with
     * `available = false`. Nothing is removed, so a role assignment never
     * silently points at a different model, and "no longer discovered" stays
     * distinguishable from "never existed".
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
     * discovered models enter the registry with unknown support so absence from
     * the hardcoded overlay is no longer an eligibility allowlist.
     */
    private fun registerCatalogModels(models: List<CatalogModel>) {
        registerDiscoveredModels(capabilityRegistry, providerId, models)
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

        /** Reported when a discovery source does not surface its own HTTP status. */
        const val HTTP_OK: Int = 200
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

    /**
     * What the last discovery attempt for [providerId] did.
     *
     * Defaults to [ModelCatalogState.Idle] so a registry that is not built on a
     * catalog (a preview or a test double) needs no extra implementation.
     */
    fun lastDiscovery(providerId: String): ModelCatalogState = ModelCatalogState.Idle

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
 * Default factory: a connected provider gets a catalog fed by that provider's own
 * model discovery, or — when the host wires none — by this layer's own request to
 * the provider's documented model list.
 *
 * Groq's is `<baseUrl>/models`, which is the documented `<host>/openai/v1/models`
 * on a Groq base URL; Gemini's is Gemini's own `/v1beta/models`, because the
 * OpenAI-compatible surface it does not chat on does not serve `/models`; a local
 * OpenAI-compatible runtime uses the same `/models` route, and answers 404 when it
 * has none, which is reported as "discovery unavailable" rather than as a catalog.
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
    /**
     * The provider-owned discovery to use for a connection, or null to let this
     * layer perform the list request itself. Supplied by the host from the same
     * provider factory the runtime chats through, so discovery and chat always
     * speak the same protocol to the same endpoint.
     */
    private val discoverySource: (String, ModelConfig) -> ModelDiscovery? = { _, _ -> null },
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
            discovery = discoverySource(providerId, connection),
        )
    }

    /**
     * Providers whose model list is understood here.
     *
     * A hosted provider and a local OpenAI-compatible runtime both serve the
     * compatible `/models` route, so both can be listed; a runtime that answers 404
     * is reported as having no list rather than being given an invented catalog.
     */
    fun supports(providerId: String): Boolean =
        providerId == ModelProviderIds.GROQ ||
            providerId == ModelProviderIds.GEMINI ||
            providerId == ModelProviderIds.OPENAI_COMPATIBLE

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
    /**
     * The snapshot store the factory writes to. Restoring from the same instance is
     * what makes a restart keep the models a previous run discovered.
     */
    private val store: ModelCatalogStore = InMemoryModelCatalogStore(),
    /** Where restored identities are registered, so they survive a restart too. */
    private val capabilityRegistry: ModelCapabilityRegistry = InMemoryModelCapabilityRegistry(),
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

    override fun lastDiscovery(providerId: String): ModelCatalogState {
        val live = catalog(providerId)
            ?: return ModelCatalogState.NotConnected("No model catalog is available for '$providerId'.")
        return (live as? RemoteModelCatalog)?.state() ?: ModelCatalogState.Idle
    }

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

    /**
     * Reloads persisted catalogs. Safe to call more than once, and safe to call
     * before anything is connected: a provider that is saved but not connected
     * right now still has its last snapshot restored, so the capability registry
     * and the picker do not forget what a previous run discovered.
     */
    override suspend fun restore() {
        // Every provider a previous run left something for is visited: the ones
        // connected now, the ones already materialized, and the ones only the store
        // still knows about. This is what makes a restart non-destructive — the
        // catalog comes back without asking the provider again.
        val wanted = LinkedHashSet<String>()
        wanted += synchronized(lock) { catalogs.keys.toList() }
        wanted += connections().keys
        wanted += runCatching { store.providers() }.getOrDefault(emptyList())

        wanted.forEach { providerId ->
            // Creating the catalog for a connected provider is what lets the restored
            // snapshot be visible to the picker; a provider that is not connected has
            // no catalog to hold it, so its identities are registered directly.
            val live = catalog(providerId)
            if (live is RemoteModelCatalog) {
                live.restore()
                return@forEach
            }
            val snapshot = runCatching { store.load(providerId) }.getOrNull() ?: return@forEach
            registerDiscoveredModels(capabilityRegistry, snapshot.providerId, snapshot.models)
        }
    }
}
