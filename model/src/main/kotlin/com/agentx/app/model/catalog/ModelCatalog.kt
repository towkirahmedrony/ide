package com.agentx.app.model.catalog

import com.agentx.app.core.ForgeError
import com.agentx.app.core.ForgeResult
import com.agentx.app.model.ModelCapabilities
import com.agentx.app.model.connect.normalizeModelId
import com.agentx.app.model.json.Json
import com.agentx.app.model.json.JsonCodec
import com.agentx.app.model.json.JsonObject
import com.agentx.app.model.json.JsonValue
import com.agentx.app.model.json.arrayOrNull
import com.agentx.app.model.json.booleanOrNull
import com.agentx.app.model.json.numberOrNull
import com.agentx.app.model.json.objectOrNull
import com.agentx.app.model.json.stringOrNull

/** Where a catalog snapshot came from. */
enum class CatalogSource {
    /** Freshly fetched from the provider's model-list endpoint. */
    REMOTE,

    /** Served from the local cache. */
    CACHE,
}

/**
 * One model as reported by a provider's model-list endpoint, plus the capability
 * metadata AgentX actually has. Nothing here is invented: a field is null when
 * the provider did not report it.
 */
data class CatalogModel(
    val id: String,
    /** Human name the provider reports, when it reports one. */
    val displayName: String? = null,
    val contextWindowTokens: Int? = null,
    val maxOutputTokens: Int? = null,
    val capabilities: ModelCapabilities = ModelCapabilities(streaming = true),
    /**
     * True when the provider explicitly reports the model as deprecated or
     * retired. Null means the provider did not say, which is not the same as
     * "current": nothing is inferred from an identifier.
     */
    val deprecated: Boolean? = null,
    /**
     * False when an earlier catalog listed this model but the latest refresh did
     * not. Such a model is kept (never silently dropped) so a saved role
     * assignment can be reported as unavailable instead of being replaced.
     */
    val available: Boolean = true,
    val providerOwnedBy: String? = null,
    val createdAtMillis: Long? = null,
)

/** A provider's model catalog at a point in time. */
data class ModelCatalogSnapshot(
    val providerId: String,
    val models: List<CatalogModel>,
    val fetchedAtMillis: Long,
    val source: CatalogSource,
) {
    fun availableModels(): List<CatalogModel> = models.filter { it.available }

    /**
     * Looks a model up by id.
     *
     * The id is normalized first, so a caller holding `models/<id>` (the form a
     * provider's own list may use) finds the same entry as a caller holding the
     * bare `<id>` that a preset stores.
     */
    fun find(modelId: String): CatalogModel? {
        val wanted = normalizeModelId(modelId)
        return models.firstOrNull { it.id == wanted }
    }

    fun isAvailable(modelId: String): Boolean = find(modelId)?.available == true
}

/**
 * A cached, refreshable catalog for one provider. [cached] never performs a
 * network request; [refresh] fetches only when needed (or when forced).
 */
interface ModelCatalog {
    val providerId: String

    /** The locally cached snapshot, or null when nothing has been fetched yet. */
    fun cached(): ModelCatalogSnapshot?

    /**
     * Refreshes the catalog. Within the cache TTL this returns the cached
     * snapshot without a network call; [force] bypasses the TTL for a manual
     * refresh.
     */
    suspend fun refresh(force: Boolean = false): ForgeResult<ModelCatalogSnapshot, ForgeError>

    /** True when there is no cached snapshot or it is older than the TTL. */
    fun isStale(nowMillis: Long): Boolean
}

/** Persistence port for catalog snapshots. Never stores a credential. */
interface ModelCatalogStore {
    suspend fun load(providerId: String): ModelCatalogSnapshot?

    suspend fun save(snapshot: ModelCatalogSnapshot)
}

/** Store used by previews, tests and the platform default. */
class InMemoryModelCatalogStore(initial: List<ModelCatalogSnapshot> = emptyList()) : ModelCatalogStore {
    private val values = LinkedHashMap<String, ModelCatalogSnapshot>()

    init {
        initial.forEach { values[it.providerId] = it }
    }

    override suspend fun load(providerId: String): ModelCatalogSnapshot? = values[providerId]

    override suspend fun save(snapshot: ModelCatalogSnapshot) {
        values[snapshot.providerId] = snapshot
    }
}

/**
 * Tolerant JSON codec mirroring the rest of the model layer: unknown fields are
 * ignored and unreadable entries are dropped rather than guessed at.
 */
object ModelCatalogCodec {

    fun encode(snapshot: ModelCatalogSnapshot): String = JsonCodec.encode(
        Json.obj(
            "providerId" to Json.of(snapshot.providerId),
            "fetchedAtMillis" to Json.of(snapshot.fetchedAtMillis),
            "source" to Json.of(snapshot.source.name),
            "models" to Json.array(snapshot.models.map(::encodeModel)),
        ),
    )

    fun decode(text: String): ModelCatalogSnapshot? = runCatching {
        val root = JsonCodec.parse(text).objectOrNull() ?: return null
        val providerId = root.stringOrNull("providerId")?.takeIf { it.isNotBlank() } ?: return null
        val models = root.arrayOrNull("models")?.mapNotNull { item -> item.objectOrNull()?.let(::decodeModel) }.orEmpty()
        ModelCatalogSnapshot(
            providerId = providerId,
            models = models,
            fetchedAtMillis = root.numberOrNull("fetchedAtMillis")?.toLong() ?: 0L,
            source = CatalogSource.entries.firstOrNull { it.name == root.stringOrNull("source") }
                ?: CatalogSource.CACHE,
        )
    }.getOrNull()

    private fun encodeModel(model: CatalogModel): JsonValue = Json.obj(
        "id" to Json.of(model.id),
        "displayName" to (model.displayName?.let { Json.of(it) } ?: JsonValue.Null),
        "contextWindowTokens" to (model.contextWindowTokens?.let { Json.of(it) } ?: JsonValue.Null),
        "maxOutputTokens" to (model.maxOutputTokens?.let { Json.of(it) } ?: JsonValue.Null),
        "deprecated" to (model.deprecated?.let { Json.of(it) } ?: JsonValue.Null),
        "available" to Json.of(model.available),
        "streaming" to Json.of(model.capabilities.streaming),
        "toolCalling" to Json.of(model.capabilities.toolCalling),
        "providerOwnedBy" to (model.providerOwnedBy?.let { Json.of(it) } ?: JsonValue.Null),
        "createdAtMillis" to (model.createdAtMillis?.let { Json.of(it) } ?: JsonValue.Null),
    )

    private fun decodeModel(json: JsonObject): CatalogModel? {
        val id = json.stringOrNull("id")?.takeIf { it.isNotBlank() } ?: return null
        return CatalogModel(
            id = id,
            displayName = json.stringOrNull("displayName")?.takeIf { it.isNotBlank() },
            contextWindowTokens = json.numberOrNull("contextWindowTokens")?.toInt(),
            maxOutputTokens = json.numberOrNull("maxOutputTokens")?.toInt(),
            deprecated = json.booleanOrNull("deprecated"),
            capabilities = ModelCapabilities(
                streaming = json.booleanOrNull("streaming") ?: true,
                toolCalling = json.booleanOrNull("toolCalling") ?: false,
            ),
            available = json.booleanOrNull("available") ?: true,
            providerOwnedBy = json.stringOrNull("providerOwnedBy")?.takeIf { it.isNotBlank() },
            createdAtMillis = json.numberOrNull("createdAtMillis")?.toLong(),
        )
    }
}
