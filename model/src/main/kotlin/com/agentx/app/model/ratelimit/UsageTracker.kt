package com.agentx.app.model.ratelimit

import com.agentx.app.model.ModelUsage

/**
 * One completed (or attempted) model request, reduced to what is safe to keep.
 *
 * No API key, header or endpoint ever reaches a usage record: only provider and
 * model identifiers plus token counts.
 */
data class UsageEntry(
    val providerId: String,
    val modelId: String,
    /** Actual input tokens when the provider reported usage; otherwise null. */
    val inputTokens: Int? = null,
    /** Actual output tokens when the provider reported usage; otherwise null. */
    val outputTokens: Int? = null,
    /** Actual total tokens when the provider reported usage; otherwise null. */
    val totalTokens: Int? = null,
    /** True when the request counted against the quota but reported no usage. */
    val estimated: Boolean = false,
    val atMillis: Long = 0L,
) {
    companion object {
        /**
         * Maps a provider [ModelUsage] onto an entry. A null [usage] means the
         * provider reported nothing, so the entry is marked as estimated rather
         * than inventing zeros.
         */
        fun of(
            providerId: String,
            modelId: String,
            usage: ModelUsage?,
            atMillis: Long,
        ): UsageEntry = UsageEntry(
            providerId = providerId,
            modelId = modelId,
            inputTokens = usage?.promptTokens,
            outputTokens = usage?.completionTokens,
            totalTokens = usage?.totalTokens,
            estimated = usage?.totalTokens == null && usage?.promptTokens == null && usage?.completionTokens == null,
            atMillis = atMillis,
        )
    }
}

/** Aggregated usage for one provider/model pair. */
data class UsageTotals(
    val providerId: String,
    val modelId: String,
    val requestCount: Long = 0L,
    val inputTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val totalTokens: Long = 0L,
    /** Requests the provider refused with a rate-limit response. */
    val rateLimitEvents: Long = 0L,
    val firstUsedAtMillis: Long = 0L,
    val lastUsedAtMillis: Long = 0L,
)

/**
 * Records model usage per provider/model. Deliberately small: this phase exposes
 * current usage for the selected provider, not a full analytics system.
 *
 * [record] and [recordRateLimit] are suspending so an implementation can persist
 * through a store without blocking the caller's thread.
 */
interface UsageTracker {
    suspend fun record(entry: UsageEntry)

    suspend fun recordRateLimit(providerId: String, modelId: String, atMillis: Long)

    fun totals(providerId: String, modelId: String? = null): List<UsageTotals>

    fun all(): List<UsageTotals>
}

/** Persistence port for usage totals. Never stores anything secret. */
interface UsageStore {
    suspend fun load(): List<UsageTotals>

    suspend fun save(totals: List<UsageTotals>)
}

/** Store used by previews, tests and the platform default. */
class InMemoryUsageStore(initial: List<UsageTotals> = emptyList()) : UsageStore {
    private val values = LinkedHashMap<Pair<String, String>, UsageTotals>()

    init {
        initial.forEach { values[it.providerId to it.modelId] = it }
    }

    override suspend fun load(): List<UsageTotals> = values.values.toList()

    override suspend fun save(totals: List<UsageTotals>) {
        values.clear()
        totals.forEach { values[it.providerId to it.modelId] = it }
    }
}

/**
 * Thread-safe in-memory tracker with optional write-through persistence.
 *
 * Counters are updated under a single lock so two requests that finish at the
 * same instant cannot lose an increment.
 */
class DefaultUsageTracker(
    private val store: UsageStore? = null,
) : UsageTracker {

    private val lock = Any()
    private val totals = LinkedHashMap<Pair<String, String>, UsageTotals>()
    private var restored = false

    /** Loads persisted totals once; safe to call repeatedly. */
    suspend fun restore() {
        val loaded = store?.load() ?: return
        synchronized(lock) {
            if (restored) return
            loaded.forEach { totals[it.providerId to it.modelId] = it }
            restored = true
        }
    }

    override suspend fun record(entry: UsageEntry) {
        val key = entry.providerId to entry.modelId
        synchronized(lock) {
            val current = totals[key] ?: UsageTotals(
                providerId = entry.providerId,
                modelId = entry.modelId,
                firstUsedAtMillis = entry.atMillis,
            )
            totals[key] = current.copy(
                requestCount = current.requestCount + 1,
                inputTokens = current.inputTokens + (entry.inputTokens ?: 0).toLong(),
                outputTokens = current.outputTokens + (entry.outputTokens ?: 0).toLong(),
                totalTokens = current.totalTokens + (entry.totalTokens ?: 0).toLong(),
                firstUsedAtMillis = if (current.firstUsedAtMillis == 0L) entry.atMillis else current.firstUsedAtMillis,
                lastUsedAtMillis = maxOf(current.lastUsedAtMillis, entry.atMillis),
            )
        }
        persist()
    }

    override suspend fun recordRateLimit(providerId: String, modelId: String, atMillis: Long) {
        val key = providerId to modelId
        synchronized(lock) {
            val current = totals[key] ?: UsageTotals(
                providerId = providerId,
                modelId = modelId,
                firstUsedAtMillis = atMillis,
            )
            totals[key] = current.copy(
                rateLimitEvents = current.rateLimitEvents + 1,
                lastUsedAtMillis = maxOf(current.lastUsedAtMillis, atMillis),
            )
        }
        persist()
    }

    override fun totals(providerId: String, modelId: String?): List<UsageTotals> =
        synchronized(lock) {
            totals.values.filter { it.providerId == providerId && (modelId == null || it.modelId == modelId) }
        }

    override fun all(): List<UsageTotals> = synchronized(lock) { totals.values.toList() }

    private suspend fun persist() {
        val snapshot = all()
        store?.save(snapshot)
    }
}
