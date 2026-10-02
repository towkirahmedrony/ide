package com.agentx.app.model.ratelimit

/**
 * Supplies configured quotas for a provider/model. Unknown dimensions stay
 * [Quota.Unknown]; implementations must not invent free-tier numbers.
 */
fun interface RateLimitLimitSource {
    fun limitsFor(providerId: String, modelId: String, accountId: String?): RateLimitLimits
}

/** Reads [RateLimitProfile]s. A missing profile is all-unknown, not a guessed default. */
class ProfileRateLimitLimitSource(
    private val profiles: () -> Map<RateLimitKey, RateLimitProfile>,
) : RateLimitLimitSource {
    override fun limitsFor(providerId: String, modelId: String, accountId: String?): RateLimitLimits {
        val map = profiles()
        val model = map[RateLimitKey(providerId, modelId.takeIf { it.isNotBlank() }, accountId)]
        val provider = map[RateLimitKey(providerId, null, accountId)]
        val chosen = when {
            model != null && model.enabled -> model
            provider != null && provider.enabled -> provider
            else -> null
        }
        return chosen?.effectiveLimits() ?: RateLimitLimits.NONE
    }
}
