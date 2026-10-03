package com.agentx.app.model.health

import com.agentx.app.model.ModelProviderError
import com.agentx.app.model.ModelProviderErrorCode

/**
 * The runtime health of one fallback candidate.
 *
 * This is deliberately not the same thing as
 * [com.agentx.app.model.runtime.ModelHealthStatus]: that one answers "does this
 * endpoint serve a model API at all", which is a setup question asked once. This
 * one answers "what has actually happened when we used this provider/model", and
 * it is what stops a request from being sent to a candidate that just failed.
 */
enum class CandidateHealthState {
    /** Nothing is known yet, or the last known problem has expired. */
    HEALTHY,

    /** Reaching the candidate is impaired, but it is still worth attempting. */
    DEGRADED,

    /** Repeated temporary failures: the candidate is in its cooldown. */
    TEMPORARILY_UNAVAILABLE,

    /** The provider refused for quota reasons; the cooldown follows its reset. */
    RATE_LIMITED,

    /** Credentials were rejected: this is a provider/account problem, not a model one. */
    AUTH_FAILED,

    /** The configuration itself is wrong; retrying it changes nothing. */
    CONFIGURATION_ERROR,

    /** No observation yet. Never treated as healthy. */
    UNKNOWN,
}

/**
 * How far one observation reaches.
 *
 * A model-specific failure must not disable every model of that provider, and a
 * provider-wide problem must not be re-learned per model, so the scope is part of
 * the record rather than assumed.
 */
enum class CandidateHealthScope {
    /** Only the affected model. */
    MODEL,

    /** Every model of the provider (for example rejected credentials). */
    PROVIDER,

    /** The provider account, which may cover several providers' entries. */
    ACCOUNT,
}

/** The kind of failure that produced a health observation. */
enum class CandidateFailure {
    RATE_LIMITED,
    TIMEOUT,
    CONNECTION,
    PROVIDER_OUTAGE,
    OVERLOADED,
    AUTHENTICATION,
    CONFIGURATION,

    /** A temporary failure that could not be classified further. */
    TEMPORARY,

    /** Not temporary: recorded so the state is not mistaken for healthy. */
    PERMANENT,
    ;

    companion object {
        /**
         * Maps an existing provider error onto a health kind.
         *
         * The provider error codes are read here rather than re-classified, so the
         * error taxonomy stays in one place and this layer cannot drift from it.
         */
        fun from(error: ModelProviderError): CandidateFailure = when (error.code) {
            ModelProviderErrorCode.RATE_LIMITED -> RATE_LIMITED
            ModelProviderErrorCode.TIMEOUT -> TIMEOUT
            ModelProviderErrorCode.NETWORK_ERROR,
            ModelProviderErrorCode.CONNECTION_FAILED,
            -> CONNECTION
            ModelProviderErrorCode.AUTHENTICATION_FAILED -> AUTHENTICATION
            ModelProviderErrorCode.INVALID_CONFIG -> CONFIGURATION
            ModelProviderErrorCode.PROVIDER_ERROR -> {
                val status = error.httpStatus
                if (status == 429) RATE_LIMITED
                else if (status == null || status >= 500) PROVIDER_OUTAGE
                else PERMANENT
            }
            ModelProviderErrorCode.UNSUPPORTED -> PERMANENT
            else -> TEMPORARY
        }
    }
}

/**
 * One health observation.
 *
 * [cooldownUntilMillis] is null for a state that does not block (HEALTHY,
 * DEGRADED, UNKNOWN), so "is this candidate blocked?" is answered by the record
 * itself rather than by a caller comparing states by hand.
 */
data class CandidateHealthRecord(
    val providerId: String,
    val modelId: String? = null,
    val accountId: String? = null,
    val state: CandidateHealthState = CandidateHealthState.UNKNOWN,
    val scope: CandidateHealthScope = CandidateHealthScope.MODEL,
    val reason: String? = null,
    val consecutiveFailures: Int = 0,
    val observedAtMillis: Long = 0L,
    val cooldownUntilMillis: Long? = null,
) {
    /** True while this record forbids a request. */
    fun coolingDown(nowMillis: Long): Boolean = cooldownUntilMillis?.let { it > nowMillis } ?: false

    /** How long is left of the cooldown, or null when there is none. */
    fun remainingCooldownMillis(nowMillis: Long): Long? =
        cooldownUntilMillis?.let { (it - nowMillis).takeIf { remaining -> remaining > 0L } }

    val usable: Boolean get() = state != CandidateHealthState.AUTH_FAILED &&
        state != CandidateHealthState.CONFIGURATION_ERROR
}

/**
 * Lightweight, in-memory provider/model health and cooldown tracking.
 *
 * What it is for: a candidate that just failed must not be hammered again, and a
 * failure must not be generalised further than it actually reaches. So a record
 * carries a scope — a 500 from one model leaves the provider's other models
 * alone, while rejected credentials mark the provider — and a cooldown that
 * honours the provider's own reset information when it gave one, falling back to
 * bounded exponential backoff with jitter.
 *
 * What it deliberately is not: a permanent blacklist. Every record expires once
 * its cooldown passes and is then pruned, so one transient error cannot disable a
 * provider for the life of the process, and nothing here is persisted (temporary
 * runtime state does not belong in the user's settings).
 */
class CandidateHealthTracker(
    private val clock: () -> Long = System::currentTimeMillis,
    /** First cooldown after a repeated temporary failure. */
    private val baseCooldownMillis: Long = DEFAULT_BASE_COOLDOWN_MILLIS,
    /** Ceiling for exponential growth, so a bad candidate is retried eventually. */
    private val maxCooldownMillis: Long = DEFAULT_MAX_COOLDOWN_MILLIS,
    /** Failures that turn a degraded candidate into a cooling-down one. */
    private val unavailableAfterFailures: Int = DEFAULT_UNAVAILABLE_AFTER_FAILURES,
    /** How long a provider-wide problem (credentials, configuration) is respected. */
    private val providerScopeCooldownMillis: Long = DEFAULT_PROVIDER_SCOPE_COOLDOWN_MILLIS,
) {

    private val lock = Any()
    private val records = LinkedHashMap<String, CandidateHealthRecord>()

    /** The most specific record for a candidate: model, else provider, else account. */
    fun state(providerId: String, modelId: String? = null, accountId: String? = null): CandidateHealthRecord {
        val now = clock()
        pruneLocked(now)
        return synchronized(lock) {
            modelId?.let { records[modelKey(providerId, it, accountId)] }
                ?: records[providerKey(providerId, accountId)]
                ?: accountId?.let { records[accountKey(it)] }
                ?: CandidateHealthRecord(
                    providerId = providerId,
                    modelId = modelId,
                    accountId = accountId,
                    state = CandidateHealthState.UNKNOWN,
                    observedAtMillis = now,
                )
        }
    }

    /**
     * True when the candidate may be attempted now.
     *
     * A model-scoped cooldown blocks only that model; a provider-scoped one blocks
     * the provider's models, which is the widest reach any single failure earns.
     */
    fun isUsable(providerId: String, modelId: String? = null, accountId: String? = null): Boolean {
        val effective = state(providerId, modelId, accountId)
        if (!effective.usable) return false
        return !effective.coolingDown(clock())
    }

    /** How long until the candidate may be attempted, or null when it may be now. */
    fun cooldownRemainingMillis(providerId: String, modelId: String? = null, accountId: String? = null): Long? =
        state(providerId, modelId, accountId).remainingCooldownMillis(clock())

    /**
     * Records a failure against a candidate.
     *
     * The provider's own reset/retry information wins when it was supplied;
     * otherwise the cooldown grows exponentially with each consecutive failure and
     * carries a jitter derived from the candidate, so a shared outage does not
     * bring every candidate back at the same instant.
     */
    fun recordFailure(
        providerId: String,
        modelId: String? = null,
        accountId: String? = null,
        failure: CandidateFailure,
        retryAfterMillis: Long? = null,
    ): CandidateHealthRecord {
        val now = clock()
        val scope = scopeOf(failure)
        val key = keyFor(scope, providerId, modelId, accountId)
        return synchronized(lock) {
            val previous = records[key]
            val consecutive = (previous?.consecutiveFailures ?: 0) + 1
            val state = stateOf(failure, consecutive)
            // A permanent failure does not cool down: retrying an invalid key or a
            // malformed request would be pointless, and it stays visible instead.
            val cooldown = when {
                !isTemporary(failure) -> null
                retryAfterMillis != null && retryAfterMillis > 0L -> retryAfterMillis
                state == CandidateHealthState.TEMPORARILY_UNAVAILABLE || state == CandidateHealthState.RATE_LIMITED ->
                    backoffMillis(consecutive, providerId, modelId)
                scope != CandidateHealthScope.MODEL -> providerScopeCooldownMillis
                else -> null
            }
            val record = CandidateHealthRecord(
                providerId = providerId,
                modelId = if (scope == CandidateHealthScope.MODEL) modelId else null,
                accountId = accountId,
                state = state,
                scope = scope,
                reason = failure.name,
                consecutiveFailures = consecutive,
                observedAtMillis = now,
                cooldownUntilMillis = cooldown?.let { now + it },
            )
            records[key] = record
            record
        }
    }

    /** Records that a candidate answered: its problems are over, so the record clears. */
    fun recordSuccess(providerId: String, modelId: String? = null, accountId: String? = null): CandidateHealthRecord {
        val now = clock()
        return synchronized(lock) {
            records.remove(modelKey(providerId, modelId, accountId))
            // A success clears the problem attributed to the scope that succeeded, and
            // nothing wider. One model answering says nothing about a provider-wide
            // credentials or configuration problem, so that record is kept; a
            // provider-wide success does clear it, which is also what clears the
            // per-model records an outage left behind.
            val clearedScope = if (modelId == null) {
                records.remove(providerKey(providerId, accountId))
                CandidateHealthScope.PROVIDER
            } else {
                CandidateHealthScope.MODEL
            }
            val record = CandidateHealthRecord(
                providerId = providerId,
                modelId = modelId,
                accountId = accountId,
                state = CandidateHealthState.HEALTHY,
                scope = if (modelId == null) CandidateHealthScope.PROVIDER else CandidateHealthScope.MODEL,
                observedAtMillis = now,
            )
            records[keyFor(record.scope, providerId, modelId, accountId)] = record
            record
        }
    }

    /** The current observations, newest first, for observability. Never a credential. */
    fun snapshot(): List<CandidateHealthRecord> = synchronized(lock) {
        pruneLocked(clock())
        records.values.sortedByDescending { it.observedAtMillis }
    }

    /** Drops records whose cooldown has expired, so transient state is not permanent. */
    fun prune(nowMillis: Long = clock()): Int {
        var removed = 0
        synchronized(lock) {
            val iterator = records.entries.iterator()
            while (iterator.hasNext()) {
                val record = iterator.next().value
                if (record.cooldownUntilMillis == null && record.state != CandidateHealthState.HEALTHY) continue
                if (record.coolingDown(nowMillis)) continue
                iterator.remove()
                removed++
            }
        }
        return removed
    }

    private fun pruneLocked(nowMillis: Long) {
        val iterator = records.entries.iterator()
        while (iterator.hasNext()) {
            val record = iterator.next().value
            if (record.coolingDown(nowMillis)) continue
            // A cooled-down record has served its purpose: the candidate becomes
            // eligible again rather than staying marked forever.
            if (record.state == CandidateHealthState.TEMPORARILY_UNAVAILABLE ||
                record.state == CandidateHealthState.RATE_LIMITED
            ) {
                iterator.remove()
            }
        }
    }

    private fun stateOf(failure: CandidateFailure, consecutive: Int): CandidateHealthState = when (failure) {
        CandidateFailure.AUTHENTICATION -> CandidateHealthState.AUTH_FAILED
        CandidateFailure.CONFIGURATION -> CandidateHealthState.CONFIGURATION_ERROR
        CandidateFailure.RATE_LIMITED -> CandidateHealthState.RATE_LIMITED
        CandidateFailure.PERMANENT -> CandidateHealthState.UNKNOWN
        CandidateFailure.TIMEOUT,
        CandidateFailure.CONNECTION,
        CandidateFailure.PROVIDER_OUTAGE,
        CandidateFailure.OVERLOADED,
        CandidateFailure.TEMPORARY,
        -> if (consecutive >= unavailableAfterFailures) {
            CandidateHealthState.TEMPORARILY_UNAVAILABLE
        } else {
            CandidateHealthState.DEGRADED
        }
    }

    private fun isTemporary(failure: CandidateFailure): Boolean = when (failure) {
        CandidateFailure.AUTHENTICATION,
        CandidateFailure.CONFIGURATION,
        CandidateFailure.PERMANENT,
        -> false
        else -> true
    }

    /**
     * A credentials or configuration problem belongs to the provider, an account
     * problem to the account, and everything else to the one model that hit it.
     */
    private fun scopeOf(failure: CandidateFailure): CandidateHealthScope = when (failure) {
        CandidateFailure.AUTHENTICATION,
        CandidateFailure.CONFIGURATION,
        -> CandidateHealthScope.PROVIDER
        else -> CandidateHealthScope.MODEL
    }

    private fun keyFor(
        scope: CandidateHealthScope,
        providerId: String,
        modelId: String?,
        accountId: String?,
    ): String = when (scope) {
        CandidateHealthScope.MODEL -> modelKey(providerId, modelId, accountId)
        CandidateHealthScope.PROVIDER -> providerKey(providerId, accountId)
        CandidateHealthScope.ACCOUNT -> accountKey(accountId ?: providerId)
    }

    private fun modelKey(providerId: String, modelId: String?, accountId: String?): String =
        "model:$providerId:${modelId.orEmpty()}:${accountId.orEmpty()}"

    private fun providerKey(providerId: String, accountId: String?): String =
        "provider:$providerId:${accountId.orEmpty()}"

    private fun accountKey(accountId: String): String = "account:$accountId"

    /**
     * Exponential backoff with a deterministic per-candidate jitter.
     *
     * The jitter is derived from the candidate rather than drawn randomly so the
     * same failure always produces the same wait, while candidates that failed
     * together still return at different times instead of in lockstep.
     */
    private fun backoffMillis(consecutive: Int, providerId: String, modelId: String?): Long {
        val exponent = (consecutive - 1).coerceIn(0, MAX_BACKOFF_EXPONENT)
        val escalations = 1L shl exponent
        val grown = (baseCooldownMillis * escalations).coerceAtMost(maxCooldownMillis)
        val seed = "$providerId:${modelId.orEmpty()}".hashCode().toLong().let { if (it < 0L) -it else it }
        val jitter = if (JITTER_BOUND_MILLIS <= 0L) 0L else seed % JITTER_BOUND_MILLIS
        return (grown + jitter).coerceAtMost(maxCooldownMillis)
    }

    companion object {
        /** First cooldown for a temporarily unavailable candidate. */
        const val DEFAULT_BASE_COOLDOWN_MILLIS: Long = 5_000L

        /** Upper bound, so a candidate is retried rather than abandoned. */
        const val DEFAULT_MAX_COOLDOWN_MILLIS: Long = 120_000L

        /** Consecutive temporary failures before a candidate is cooled down. */
        const val DEFAULT_UNAVAILABLE_AFTER_FAILURES: Int = 2

        /** How long rejected credentials / a bad configuration are respected. */
        const val DEFAULT_PROVIDER_SCOPE_COOLDOWN_MILLIS: Long = 300_000L

        /** Spread so a shared outage does not return every candidate at once. */
        const val JITTER_BOUND_MILLIS: Long = 1_000L

        private const val MAX_BACKOFF_EXPONENT = 5
    }
}
