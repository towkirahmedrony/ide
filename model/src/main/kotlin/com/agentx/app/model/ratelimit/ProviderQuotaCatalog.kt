package com.agentx.app.model.ratelimit

import com.agentx.app.model.preset.ModelProviderIds

/**
 * Provider-published quotas, as *facts*.
 *
 * This is the configuration source admission control was missing: the manager has
 * always been able to enforce a [RateLimitProfile], but nothing produced one, so
 * every provider looked unlimited and protection only ever happened *after* a 429.
 * The catalog is keyed by the provider family a connection reports
 * ([ModelProviderIds]) and holds only what the provider actually documents.
 *
 * Two rules keep it honest:
 *
 * - **Nothing is inferred.** A provider with no published limit has no entry here,
 *   and a provider whose numbers are not published stays absent rather than being
 *   given a plausible guess. "No entry" means *unknown*, which the manager reports
 *   as [QuotaKnowledge.UNKNOWN] and deliberately does not enforce — never zero, and
 *   never a made-up ceiling.
 * - **Facts only, no policy.** Every value carries [RateLimitSource.PROVIDER_REPORTED]
 *   and the [RateLimitProfile.reference] it came from, so a limit can be audited
 *   back to its documentation. The safety margin and any other headroom policy are
 *   applied when a profile is registered
 *   ([CatalogRateLimitProfileRegistrar], [RateLimitPolicy]); they are never baked
 *   into the provider's number here.
 *
 * ## Scope of the entries
 *
 * Groq's free-plan limits are documented *per model* — its rate-limit table lists
 * a separate ceiling for each model — so its entries are model-scoped. They are
 * deliberately not collapsed into one provider-scoped number: a provider-scoped
 * ceiling would throttle a connection that is legitimately using two models at
 * once, which is not a limit the provider imposes.
 *
 * The reverse also holds: where a provider documents one account-wide ceiling
 * across all of its models, that is expressed as a provider-scoped entry
 * ([RateLimitProfile.modelId] left null) rather than being copied onto every model.
 */
object ProviderQuotaCatalog {

    /**
     * The documented profiles for [providerId], or an empty list when the provider
     * publishes nothing this build can rely on.
     *
     * An empty list is the honest answer, not a failure: the connection is still
     * usable and its requests are still admitted, because an unknown limit must not
     * become a block.
     */
    fun profiles(providerId: String): List<RateLimitProfile> =
        ENTRIES[normalize(providerId)].orEmpty()

    /** The documentation the entries for [providerId] came from, when any were cited. */
    fun reference(providerId: String): String? = REFERENCES[normalize(providerId)]

    /**
     * Why [providerId] has no configured limit, when that is a documented fact
     * rather than an omission in this build.
     *
     * Exposed so observability can distinguish "the provider publishes nothing" from
     * "AgentX forgot to configure it" — the two look identical from the outside, and
     * only one of them is a bug.
     */
    fun unpublishedReason(providerId: String): String? = UNPUBLISHED[normalize(providerId)]

    /** Providers this build has documented limits for. */
    fun configuredProviders(): Set<String> = ENTRIES.keys

    private fun normalize(providerId: String): String = providerId.trim().lowercase()

    /**
     * Groq, free plan, as published in its rate-limit documentation.
     *
     * Only models whose published free-tier figures this build has an authoritative
     * source for are listed. A Groq model that is not listed here stays unknown, and
     * that is intentional: inventing a ceiling for an unlisted model would be worse
     * than admitting it without one.
     */
    private val GROQ_REFERENCE: String = "https://console.groq.com/docs/rate-limits"

    private val ENTRIES: Map<String, List<RateLimitProfile>> = mapOf(
        ModelProviderIds.GROQ to listOf(
            groqModel("openai/gpt-oss-120b", requestsPerDay = 1_000),
            groqModel("openai/gpt-oss-20b", requestsPerDay = 1_000),
            groqModel("qwen/qwen3.6-27b", requestsPerDay = 1_000),
            // The compound models are documented with a smaller daily allowance than
            // the rest of the free plan, so they carry their own number.
            groqModel("groq/compound", requestsPerDay = 250),
            groqModel("groq/compound-mini", requestsPerDay = 250),
        ),
    )

    /** One Groq model entry. The free plan publishes 30 requests/minute per model. */
    private fun groqModel(modelId: String, requestsPerDay: Int): RateLimitProfile = RateLimitProfile(
        providerId = ModelProviderIds.GROQ,
        modelId = modelId,
        requestsPerMinute = 30,
        requestsPerDay = requestsPerDay,
        source = RateLimitSource.PROVIDER_REPORTED,
        reference = GROQ_REFERENCE,
    )

    private val REFERENCES: Map<String, String> = mapOf(
        ModelProviderIds.GROQ to GROQ_REFERENCE,
    )

    /**
     * Providers whose limits are documented as unpublished.
     *
     * Google no longer publishes per-model free-tier rate limits for the Gemini API
     * and points at the account's own quota view instead, so Gemini is listed with a
     * reason rather than with numbers taken from an older table.
     *
     * A Custom/Local endpoint has no provider to publish a limit: it is the user's
     * own server, and its ceiling — if any — is theirs to state through a configured
     * profile rather than something AgentX can look up.
     */
    private val UNPUBLISHED: Map<String, String> = mapOf(
        ModelProviderIds.GEMINI to
            "Gemini free-tier limits are per account and not published per model; " +
            "they are shown in the provider's own quota view.",
        ModelProviderIds.OPENAI_COMPATIBLE to
            "A custom or local endpoint publishes no quota of its own; " +
            "configure one explicitly if the server enforces a limit.",
    )
}

/**
 * [ProviderQuotaCatalog] exposed as a [RateLimitLimitSource].
 *
 * The manager consults this only for a scope that no registered profile covered —
 * a request with no connection discriminator, or one whose connection never
 * published a quota — so a registered profile always wins and the documented limit
 * can never be applied twice or contradict one.
 *
 * It exists because a provider's published ceiling belongs to the provider rather
 * than to the fact that a connection was registered: a request that reaches a
 * documented model should be admitted against the documented limit whichever path
 * it arrived by. The policy margin is applied here as it is at registration, so a
 * limit reached through this route has the same headroom as one reached through a
 * profile.
 */
class CatalogRateLimitLimitSource(
    private val catalog: (String) -> List<RateLimitProfile> = ProviderQuotaCatalog::profiles,
    private val policy: RateLimitPolicy = RateLimitPolicy.DEFAULT,
) : RateLimitLimitSource {

    override fun limitsFor(providerId: String, modelId: String, accountId: String?): RateLimitLimits {
        val entries = catalog(providerId)
        if (entries.isEmpty()) return RateLimitLimits.NONE
        // A model-scoped entry describes that model; a provider-scoped one describes
        // every model the provider serves, and is only used when the model itself is
        // not documented.
        val chosen = entries.lastOrNull { it.modelId == modelId }
            ?: entries.lastOrNull { it.modelId.isNullOrBlank() }
            ?: return RateLimitLimits.NONE
        return chosen.withSafetyMargin(policy.safetyMargin).effectiveLimits()
    }
}
