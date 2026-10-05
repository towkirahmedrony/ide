package com.agentx.app.model.ratelimit

/**
 * The quota scope one connection's requests are admitted under.
 *
 * A provider *family* (`openai-compatible`, `groq`, …) is not a quota scope: two
 * custom OpenAI-compatible endpoints are two servers with two separate allowances,
 * and two keys for one provider are two accounts. What shares an allowance is the
 * connection, which the saved preset's identity identifies and which stays stable
 * across restarts.
 *
 * The connection is only used as a discriminator when it actually discriminates.
 * A draft or ad-hoc configuration has no saved preset, so its connection identity
 * falls back to the provider family; there is then nothing to separate, and the
 * request keeps the family scope so existing behaviour is unchanged. A provider with
 * a single connection likewise keeps exactly one bucket.
 *
 * Returns null when no discriminator is needed.
 */
internal fun quotaScopeOf(connectionId: String, providerId: String): String? {
    val connection = connectionId.trim().takeIf { it.isNotBlank() } ?: return null
    return connection.takeIf { it != providerId.trim() }
}

/** Configuration metadata key holding an explicit account discriminator. */
const val ACCOUNT_METADATA_KEY: String = "accountId"

/**
 * The quota scope this configuration's traffic is admitted under.
 *
 * This is the one function both sides of admission must agree on: the gateway
 * *reserves* against it, and any pre-request eligibility check must *evaluate*
 * against the same scope. When they disagreed, a connection-scoped quota registered
 * by the rate-limit manager was invisible to the eligibility check, which then
 * reported a candidate as having headroom that the gateway would have refused —
 * exactly the "it looked allowed and then failed" behaviour a proactive limiter
 * exists to prevent.
 *
 * An explicit `accountId` in the metadata wins, so a host that really does run
 * several accounts through one connection can say so.
 */
fun com.agentx.app.model.ModelConfig.quotaScope(): String? =
    metadata[ACCOUNT_METADATA_KEY]?.takeIf { it.isNotBlank() }
        ?: quotaScopeOf(connectionId, providerId)

/**
 * Headroom policy, kept apart from provider facts.
 *
 * The catalog states what a provider allows; this states how close AgentX is
 * willing to run to it. Keeping them separate is what lets the same provider fact
 * be used at different safety levels without editing the provider's number, and it
 * is why the margin is applied at registration rather than stored in the catalog.
 */
data class RateLimitPolicy(
    /**
     * Fraction of every published ceiling held back.
     *
     * A published limit is the point at which the provider starts refusing, and
     * several agent roles can be in flight at once, each having reserved capacity
     * from the same remaining headroom. Scheduling exactly at the ceiling leaves no
     * room for the requests that are already on their way and turns what should be a
     * wait into a 429 that fails a running task. Ten percent is enough to absorb that
     * in-flight overlap at the small ceilings free tiers use (a 30 rpm plan keeps
     * three requests of slack) without meaningfully under-using the allowance.
     */
    val safetyMargin: Double = DEFAULT_SAFETY_MARGIN,
) {
    init {
        require(safetyMargin in 0.0..RateLimitProfile.MAX_SAFETY_MARGIN) {
            "safetyMargin must be between 0 and ${RateLimitProfile.MAX_SAFETY_MARGIN}"
        }
    }

    companion object {
        const val DEFAULT_SAFETY_MARGIN: Double = 0.1

        val DEFAULT: RateLimitPolicy = RateLimitPolicy()
    }
}

/**
 * Publishes the quota profiles a connection runs under.
 *
 * This is the step that was missing from the runtime: a connection coming online
 * must be able to state the quotas its requests will be admitted against, so
 * protection happens *before* the request instead of only after a 429. It exists as
 * an interface so the connection lifecycle does not depend on where the profiles
 * come from — a catalog today, a user-configured store or a provider's own quota
 * response later.
 */
interface RateLimitProfileRegistrar {
    /**
     * Registers the quota profiles that apply to the connection [connectionId] of
     * provider family [providerId]. Must be safe to call repeatedly for the same
     * connection: reconnecting re-states the same limits, it does not stack them.
     *
     * [local] marks a connection that is not a remote provider, which is never given
     * a remote quota.
     */
    suspend fun registerFor(connectionId: String, providerId: String, local: Boolean)

    companion object {
        /** A registrar that publishes nothing; the default when none is supplied. */
        val NONE: RateLimitProfileRegistrar = object : RateLimitProfileRegistrar {
            override suspend fun registerFor(connectionId: String, providerId: String, local: Boolean) = Unit
        }
    }
}

/**
 * Registers the documented quotas from [ProviderQuotaCatalog] for each connection
 * that comes online.
 *
 * Profiles are scoped to the connection, so two connections of the same provider
 * family never share one bucket, while a single connection's provider-scoped entry
 * still covers every model it serves.
 *
 * A local connection registers nothing at all. A local runtime (an on-device or
 * LAN server) consumes no remote quota, and throttling it with a provider's numbers
 * would slow down the one model that costs nothing to call. A remote custom endpoint
 * registers nothing either, because no provider published a limit for it — but it is
 * not treated as unlimited internally: it simply has no configured ceiling, which is
 * reported as unknown and may be stated explicitly through a configured profile.
 */
class CatalogRateLimitProfileRegistrar(
    private val manager: RateLimitManager,
    private val catalog: (String) -> List<RateLimitProfile> = ProviderQuotaCatalog::profiles,
    private val policy: RateLimitPolicy = RateLimitPolicy.DEFAULT,
) : RateLimitProfileRegistrar {

    override suspend fun registerFor(connectionId: String, providerId: String, local: Boolean) {
        if (local) return
        val scope = quotaScopeOf(connectionId, providerId)
        catalog(providerId).forEach { profile ->
            manager.updateProfile(
                profile
                    .scopedTo(scope)
                    .withSafetyMargin(policy.safetyMargin),
            )
        }
    }
}
