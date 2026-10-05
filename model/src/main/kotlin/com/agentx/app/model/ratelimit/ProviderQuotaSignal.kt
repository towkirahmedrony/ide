package com.agentx.app.model.ratelimit

/**
 * How well a quota value is actually known.
 *
 * Part 4 requires that quota knowledge be represented explicitly rather than
 * assumed. A provider that publishes nothing must not be treated as unlimited,
 * and a locally counted value must never be reported as if the provider had
 * confirmed it — unknown quota increases caution, it never reduces it.
 */
enum class QuotaKnowledge {
    /** The provider published this exact value (a limit or a remaining count). */
    KNOWN,

    /** The provider reported it for a recent response; it drifts and may be stale. */
    OBSERVED,

    /** Locally counted/estimated, with no provider confirmation. */
    ESTIMATED,

    /** Nothing is known. Never treated as unlimited, never guessed. */
    UNKNOWN,
}

/**
 * A quota dimension, named the way providers name them rather than the way any
 * one provider happens to expose them.
 *
 * The existing [RateLimitKind] answers "requests or tokens"; a window is the
 * second half of the identity, because the same provider enforces per-minute and
 * per-day buckets at once and they must not be conflated.
 */
enum class QuotaDimension(val kind: RateLimitKind, val window: QuotaWindow) {
    REQUESTS_PER_MINUTE(RateLimitKind.REQUEST, QuotaWindow.MINUTE),
    REQUESTS_PER_HOUR(RateLimitKind.REQUEST, QuotaWindow.HOUR),
    REQUESTS_PER_DAY(RateLimitKind.REQUEST, QuotaWindow.DAY),
    TOKENS_PER_MINUTE(RateLimitKind.TOKENS, QuotaWindow.MINUTE),
    TOKENS_PER_HOUR(RateLimitKind.TOKENS, QuotaWindow.HOUR),
    TOKENS_PER_DAY(RateLimitKind.TOKENS, QuotaWindow.DAY),
    /**
     * The prompt side of a token ceiling, for a provider that publishes input and
     * output separately. Distinct from [TOKENS_PER_MINUTE], which is one ceiling
     * for a whole request: reporting one as the other would misstate what the
     * provider actually enforces.
     */
    INPUT_TOKENS_PER_MINUTE(RateLimitKind.TOKENS, QuotaWindow.MINUTE),
    /** The completion side of a split token ceiling. See [INPUT_TOKENS_PER_MINUTE]. */
    OUTPUT_TOKENS_PER_MINUTE(RateLimitKind.TOKENS, QuotaWindow.MINUTE),
    CONCURRENT_REQUESTS(RateLimitKind.REQUEST, QuotaWindow.INSTANT),

    /** A dimension the provider named that this build does not model yet. */
    UNRECOGNISED(RateLimitKind.UNKNOWN, QuotaWindow.UNKNOWN),
}

/** The window a quota dimension is measured over. */
enum class QuotaWindow {
    INSTANT,
    MINUTE,
    HOUR,
    DAY,
    UNKNOWN,
    ;

    /** How long one window lasts, or null when the window is not time-boxed. */
    val durationMillis: Long?
        get() = when (this) {
            MINUTE -> 60_000L
            HOUR -> 3_600_000L
            DAY -> 86_400_000L
            INSTANT, UNKNOWN -> null
        }
}

/**
 * One quota fact a provider reported, normalised.
 *
 * Every numeric field is nullable on purpose: a provider that reports a
 * remaining count but no limit, or a limit with no reset, is represented exactly
 * that way. Nothing is filled in from a guess.
 */
data class ObservedQuotaFact(
    val dimension: QuotaDimension,
    /** The provider's ceiling for this dimension, when it reported one. */
    val limit: Long? = null,
    /** What the provider said is left. */
    val remaining: Long? = null,
    /** How long until this dimension refills, when the provider said so. */
    val resetAfterMillis: Long? = null,
    val knowledge: QuotaKnowledge = QuotaKnowledge.OBSERVED,
    /** The provider header this came from, for diagnostics. Never a credential. */
    val source: String? = null,
) {
    val hasNumbers: Boolean get() = limit != null || remaining != null
}

/**
 * Everything a provider told us about quota in one response, provider-neutrally.
 *
 * The parser recognises the *shape* providers use (`x-ratelimit-limit-tokens`,
 * `x-ratelimit-remaining-requests-day`, `retry-after`, and the
 * `ratelimit-*`/`x-ratelimit-*` families generally) instead of a fixed list of
 * provider-specific header names, so a provider that adopts the convention is
 * understood without a code change and a provider that uses different names
 * simply reports nothing — which stays UNKNOWN rather than being invented.
 */
data class ProviderQuotaSignal(
    val facts: List<ObservedQuotaFact> = emptyList(),
    /** From `Retry-After`, when present. */
    val retryAfterMillis: Long? = null,
    val observedAtMillis: Long = 0L,
) {
    val hasSignal: Boolean get() = facts.any { it.hasNumbers } || retryAfterMillis != null

    fun factFor(dimension: QuotaDimension): ObservedQuotaFact? = facts.lastOrNull { it.dimension == dimension }

    companion object {
        val EMPTY: ProviderQuotaSignal = ProviderQuotaSignal()
    }
}

/**
 * Provider-neutral reader for rate-limit response headers.
 *
 * Providers that expose quota do not agree on header names, but they do follow
 * one shape: a prefix, a role (`limit` / `remaining` / `reset`), and optional
 * tokens naming the dimension (`requests` / `tokens` / `concurrency`) and the
 * window (`minute` / `hour` / `day`, or the `rpm`/`rph`/`tpd` shorthands).
 * Reading that shape means:
 *
 *  - a provider that reports headers is understood without being special-cased;
 *  - a provider that reports none yields an empty signal, and its quota stays
 *    UNKNOWN rather than becoming a guessed number;
 *  - no provider-specific header list or free-tier quota value is hardcoded here.
 *
 * Only what a header literally states is produced. An unparseable window or reset
 * yields UNKNOWN/null instead of an invented value.
 */
object ProviderQuotaHeaders {

    /** `Retry-After` is a plain HTTP header, so it is read by the existing parser. */
    fun parse(
        headers: Map<String, List<String>>,
        nowMillis: Long = System.currentTimeMillis(),
    ): ProviderQuotaSignal {
        if (headers.isEmpty()) return ProviderQuotaSignal(observedAtMillis = nowMillis)

        val facts = mutableListOf<ObservedQuotaFact>()
        headers.forEach { (name, values) ->
            val header = name.lowercase()
            val role = roleOf(header) ?: return@forEach
            val raw = values.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
            val dimension = dimensionOf(header)
            when (role) {
                Role.LIMIT -> raw.toLongOrNull()?.let { value ->
                    facts += ObservedQuotaFact(
                        dimension = dimension,
                        limit = value.takeIf { it >= 0L },
                        knowledge = QuotaKnowledge.KNOWN,
                        source = header,
                    )
                }

                Role.REMAINING -> raw.toLongOrNull()?.let { value ->
                    facts += ObservedQuotaFact(
                        dimension = dimension,
                        remaining = value.takeIf { it >= 0L },
                        knowledge = QuotaKnowledge.OBSERVED,
                        source = header,
                    )
                }

                Role.RESET -> resetMillis(raw, nowMillis)?.let { reset ->
                    facts += ObservedQuotaFact(
                        dimension = dimension,
                        resetAfterMillis = reset,
                        knowledge = QuotaKnowledge.OBSERVED,
                        source = header,
                    )
                }
            }
        }

        // The same dimension may arrive as several headers (limit, remaining, reset);
        // they describe one bucket, so they are merged into one fact per dimension.
        val merged = LinkedHashMap<QuotaDimension, ObservedQuotaFact>()
        facts.forEach { fact ->
            val existing = merged[fact.dimension]
            merged[fact.dimension] = if (existing == null) {
                fact
            } else {
                ObservedQuotaFact(
                    dimension = fact.dimension,
                    limit = fact.limit ?: existing.limit,
                    remaining = fact.remaining ?: existing.remaining,
                    resetAfterMillis = fact.resetAfterMillis ?: existing.resetAfterMillis,
                    knowledge = if (
                        existing.knowledge == QuotaKnowledge.KNOWN || fact.knowledge == QuotaKnowledge.KNOWN
                    ) {
                        QuotaKnowledge.KNOWN
                    } else {
                        QuotaKnowledge.OBSERVED
                    },
                    source = existing.source ?: fact.source,
                )
            }
        }

        return ProviderQuotaSignal(
            facts = merged.values.toList(),
            retryAfterMillis = RetryAfter.parseMillis(headers, nowMillis),
            observedAtMillis = nowMillis,
        )
    }

    private enum class Role { LIMIT, REMAINING, RESET }

    /** The prefix families a rate-limit header may use. */
    private val PREFIXES = listOf("x-ratelimit-", "ratelimit-", "x-rate-limit-", "rate-limit-")

    private fun roleOf(header: String): Role? {
        if (!PREFIXES.any { header.startsWith(it) }) return null
        return when {
            header.contains("remaining") -> Role.REMAINING
            header.contains("reset") -> Role.RESET
            header.contains("limit") -> Role.LIMIT
            else -> null
        }
    }

    private fun dimensionOf(header: String): QuotaDimension {
        val concurrency = header.contains("concurren")
        val tokens = header.contains("token") || header.contains("tpm") || header.contains("tpd") ||
            header.contains("input") || header.contains("output")
        val requests = header.contains("request") || header.contains("query") ||
            header.contains("rpm") || header.contains("rph") || header.contains("rpd")
        val window = when {
            header.contains("minute") || header.contains("-min") || header.contains("rpm") ||
                header.contains("tpm") -> QuotaWindow.MINUTE
            header.contains("hour") || header.contains("-hr") || header.contains("rph") ||
                header.contains("tph") -> QuotaWindow.HOUR
            header.contains("day") || header.contains("rpd") || header.contains("tpd") -> QuotaWindow.DAY
            // A window this build does not model is not guessed at: the fact is kept
            // and reported as an unrecognised dimension instead of being folded into
            // a window it may not belong to.
            header.contains("week") || header.contains("wk") || header.contains("month") -> return QuotaDimension.UNRECOGNISED
            // The header family's documented base window. This is a property of the
            // header *shape* — an unsuffixed `x-ratelimit-remaining-tokens` is the
            // shortest standard bucket — not a provider-specific quota value.
            else -> QuotaWindow.MINUTE
        }
        return when {
            concurrency -> QuotaDimension.CONCURRENT_REQUESTS
            // A header that names the prompt or completion side reports that side,
            // not the whole-request ceiling. Only the minute window is modelled for
            // the split, so a longer window keeps the total dimension it already
            // mapped to rather than being reported as a bucket it may not belong to.
            tokens && window == QuotaWindow.MINUTE && header.contains("input") ->
                QuotaDimension.INPUT_TOKENS_PER_MINUTE
            tokens && window == QuotaWindow.MINUTE && header.contains("output") ->
                QuotaDimension.OUTPUT_TOKENS_PER_MINUTE
            tokens && window == QuotaWindow.MINUTE -> QuotaDimension.TOKENS_PER_MINUTE
            tokens && window == QuotaWindow.HOUR -> QuotaDimension.TOKENS_PER_HOUR
            tokens && window == QuotaWindow.DAY -> QuotaDimension.TOKENS_PER_DAY
            requests && window == QuotaWindow.MINUTE -> QuotaDimension.REQUESTS_PER_MINUTE
            requests && window == QuotaWindow.HOUR -> QuotaDimension.REQUESTS_PER_HOUR
            requests && window == QuotaWindow.DAY -> QuotaDimension.REQUESTS_PER_DAY
            else -> QuotaDimension.UNRECOGNISED
        }
    }

    /**
     * A reset header, in milliseconds from now.
     *
     * Providers express it as a duration (`2.4s`, `1m30s`, `150ms`) or as a number
     * of seconds, and sometimes as an absolute epoch second. Anything else is left
     * unknown rather than guessed at.
     */
    private fun resetMillis(raw: String, nowMillis: Long): Long? {
        parseDuration(raw)?.let { return it.takeIf { it > 0L } }
        val number = raw.toDoubleOrNull() ?: return null
        if (number <= 0.0) return null
        // A large value is an absolute epoch second, a small one a relative second.
        return if (number > 1_000_000_000.0) {
            (number * 1000.0).toLong().minus(nowMillis).takeIf { it > 0L }
        } else {
            (number * 1000.0).toLong()
        }
    }

    /** Parses `1h2m3.5s`/`250ms`/`90s` into milliseconds, or null when not a duration. */
    private fun parseDuration(raw: String): Long? {
        val text = raw.trim().lowercase()
        if (text.isEmpty()) return null
        var total = 0.0
        var index = 0
        var matched = false
        while (index < text.length) {
            val start = index
            while (index < text.length && (text[index].isDigit() || text[index] == '.')) index++
            if (index == start) return null
            val value = text.substring(start, index).toDoubleOrNull() ?: return null
            val unitStart = index
            while (index < text.length && !text[index].isDigit() && text[index] != '.') index++
            val unit = text.substring(unitStart, index)
            val multiplier = when (unit) {
                "ms" -> 1.0
                "s", "" -> 1000.0
                "m" -> 60_000.0
                "h" -> 3_600_000.0
                "d" -> 86_400_000.0
                else -> return null
            }
            total += value * multiplier
            matched = true
        }
        return if (matched) total.toLong() else null
    }
}
