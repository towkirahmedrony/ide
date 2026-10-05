package com.agentx.app.agent.model

import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.model.json.Json
import com.agentx.app.model.json.JsonCodec
import com.agentx.app.model.json.JsonObject
import com.agentx.app.model.json.JsonValue
import com.agentx.app.model.json.arrayOrNull
import com.agentx.app.model.json.booleanOrNull
import com.agentx.app.model.json.numberOrNull
import com.agentx.app.model.json.objectOrNull
import com.agentx.app.model.json.stringOrNull

/**
 * The persisted, user-owned fallback configuration.
 *
 * This is the piece the runtime was missing. [ModelFallbackPolicy] already described
 * *what* a configured fallback is, and [ModelFallback] already executed one, but no
 * configuration source existed: the composition root built the agent layer with
 * `ModelFallbackPolicy.DISABLED` and nothing could ever change it, so the whole
 * fallback path was unreachable in the shipped app no matter how well it behaved in
 * tests. A policy that no one can configure is not a feature.
 *
 * It stores what the user owns and nothing else — whether fallback is enabled, how
 * many candidates one request may try, and the ordered chain per role. It never
 * stores a credential: a chain entry names a provider/model/connection, and the
 * credential stays in the keystore behind the connection it belongs to.
 *
 * Opt-in is preserved: [DEFAULT] is disabled with no chains, matching
 * [ModelFallbackPolicy.DISABLED], so an existing user's behaviour does not change
 * until they configure a chain on purpose.
 */
data class AgentFallbackConfig(
    /** Whether a temporary primary failure may try a configured candidate. */
    val automaticFallback: Boolean = ModelFallbackPolicy.DEFAULT_AUTOMATIC_FALLBACK,
    /** Maximum candidates one model request may try; the primary is not counted. */
    val maxFallbackAttempts: Int = ModelFallbackPolicy.DEFAULT_MAX_FALLBACK_ATTEMPTS,
    /** Ordered candidate chain per role. An absent or empty chain means "no fallback". */
    val chains: Map<AgentRole, List<RoleModelPreference>> = emptyMap(),
) {

    init {
        require(maxFallbackAttempts >= 0) { "maxFallbackAttempts must not be negative" }
        require(maxFallbackAttempts <= MAX_FALLBACK_ATTEMPTS_LIMIT) {
            "maxFallbackAttempts must not exceed $MAX_FALLBACK_ATTEMPTS_LIMIT"
        }
    }

    /** The chain declared for [role], or empty when none is. */
    fun chain(role: AgentRole): List<RoleModelPreference> = chains[role].orEmpty()

    /** True only when fallback is enabled and [role] has a chain to use. */
    fun enabledFor(role: AgentRole): Boolean = automaticFallback && chain(role).isNotEmpty()

    /** Returns the config with [role]'s chain replaced by [chain]. */
    fun withChain(role: AgentRole, chain: List<RoleModelPreference>): AgentFallbackConfig {
        if (chain.isEmpty()) return copy(chains = chains - role)
        return copy(chains = chains + (role to chain))
    }

    /** The policy the runtime executes, derived from what was persisted. */
    fun toPolicy(): ModelFallbackPolicy = ModelFallbackPolicy(
        automaticFallback = automaticFallback,
        maxFallbackAttempts = maxFallbackAttempts,
        fallbacksByRole = chains.filterValues { it.isNotEmpty() },
    )

    companion object {
        /** Disabled, no chains: the shipped default and the opt-in baseline. */
        val DEFAULT: AgentFallbackConfig = AgentFallbackConfig()

        /**
         * A hard ceiling on the configured chain depth.
         *
         * The policy already bounds attempts per request, but a stored value is user
         * data that could arrive malformed or be edited; clamping it to a small
         * number keeps a single request from being fanned out across an unbounded
         * number of providers however the file got that way.
         */
        const val MAX_FALLBACK_ATTEMPTS_LIMIT: Int = 5
    }
}

/**
 * Persistence port for the fallback configuration.
 *
 * Mirrors the role-model store: a suspending load/save plus a synchronous
 * [snapshot], because the runtime needs the configuration at construction time and
 * cannot wait for a suspending load before the first model request.
 */
interface AgentFallbackStore {
    suspend fun load(): AgentFallbackConfig

    suspend fun save(config: AgentFallbackConfig)

    /** The persisted configuration right now, when the store can answer synchronously. */
    fun snapshot(): AgentFallbackConfig = AgentFallbackConfig.DEFAULT
}

/** Store used by previews, tests and the platform default. */
class InMemoryAgentFallbackStore(
    initial: AgentFallbackConfig = AgentFallbackConfig.DEFAULT,
) : AgentFallbackStore {
    private var value = initial

    override suspend fun load(): AgentFallbackConfig = value

    override fun snapshot(): AgentFallbackConfig = value

    override suspend fun save(config: AgentFallbackConfig) {
        value = config
    }
}

/**
 * The single authoritative fallback configuration, over a persisted store.
 *
 * Reads are synchronous and cheap so [policy] can be handed to the runtime as a live
 * view: a saved change applies to the next request instead of requiring a restart.
 * Writes go through the store, so a chain configured once is still there next run.
 *
 * It holds no credential and never resolves a connection by itself — it only records
 * which provider/model/connection a role may fall back to. Whether that connection
 * exists and whether the model is eligible stay the runtime's decision, so this type
 * cannot become a second, weaker source of truth for model selection.
 */
class AgentFallbackConfigRepository(
    private val store: AgentFallbackStore = InMemoryAgentFallbackStore(),
) {
    @Volatile
    private var current: AgentFallbackConfig = store.snapshot()

    /** The configuration in force. */
    fun config(): AgentFallbackConfig = current

    /** The executable policy in force. */
    fun policy(): ModelFallbackPolicy = current.toPolicy()

    /** True when fallback is enabled and [role] has a chain. */
    fun enabledFor(role: AgentRole): Boolean = current.enabledFor(role)

    /** A live view for the runtime, so a saved change needs no restart. */
    fun livePolicy(): () -> ModelFallbackPolicy = { policy() }

    /**
     * Loads the persisted configuration. Synchronous stores are already in force at
     * construction; this is the recovery path for a store that can only load
     * asynchronously, and it deliberately does not overwrite a value already set this
     * run.
     */
    suspend fun restore(): AgentFallbackConfig {
        if (current != AgentFallbackConfig.DEFAULT) return current
        val loaded = store.load()
        current = loaded
        return current
    }

    /** Enables or disables fallback. Chains are kept, so toggling is not destructive. */
    suspend fun setEnabled(enabled: Boolean): AgentFallbackConfig =
        persist(current.copy(automaticFallback = enabled))

    /** Replaces [role]'s ordered chain. An empty chain clears it. */
    suspend fun setChain(role: AgentRole, chain: List<RoleModelPreference>): AgentFallbackConfig =
        persist(current.withChain(role, chain))

    /** Clears [role]'s chain, leaving the enabled flag alone. */
    suspend fun resetChain(role: AgentRole): AgentFallbackConfig = setChain(role, emptyList())

    /** Returns to the opt-in baseline: disabled, no chains. */
    suspend fun resetAll(): AgentFallbackConfig = persist(AgentFallbackConfig.DEFAULT)

    private suspend fun persist(next: AgentFallbackConfig): AgentFallbackConfig {
        current = next
        store.save(next)
        return next
    }
}

/**
 * Serializes the fallback configuration with the JSON codec the model layer already
 * ships, so persistence needs no new dependency or file format.
 *
 * Decoding is tolerant by design: an entry that cannot be understood is dropped, and
 * a chain is kept only if it has at least one entry. It never promotes a malformed
 * value into an enabled fallback — the safe direction for a setting whose whole
 * purpose is to be explicit.
 */
object AgentFallbackConfigCodec {

    fun encode(config: AgentFallbackConfig): String = JsonCodec.encode(toJson(config))

    fun decode(text: String): AgentFallbackConfig = runCatching {
        val root = JsonCodec.parse(text).objectOrNull() ?: return AgentFallbackConfig.DEFAULT
        AgentFallbackConfig(
            automaticFallback = root.booleanOrNull("automaticFallback")
                ?: ModelFallbackPolicy.DEFAULT_AUTOMATIC_FALLBACK,
            maxFallbackAttempts = root.numberOrNull("maxFallbackAttempts")
                ?.toInt()
                ?.coerceIn(0, AgentFallbackConfig.MAX_FALLBACK_ATTEMPTS_LIMIT)
                ?: ModelFallbackPolicy.DEFAULT_MAX_FALLBACK_ATTEMPTS,
            chains = decodeChains(root),
        )
    }.getOrElse { AgentFallbackConfig.DEFAULT }

    private fun toJson(config: AgentFallbackConfig): JsonValue.Obj = JsonValue.Obj(
        linkedMapOf(
            "automaticFallback" to Json.of(config.automaticFallback),
            "maxFallbackAttempts" to Json.of(config.maxFallbackAttempts),
            // Keys are the role names, so the file stays readable and a role rename
            // is visible in the diff rather than silently orphaning a chain.
            "chains" to JsonValue.Obj(
                LinkedHashMap(
                    config.chains
                        .filterValues { it.isNotEmpty() }
                        .map { (role, chain) -> role.name to JsonValue.Arr(chain.map(::preferenceToJson)) }
                        .toMap(),
                ),
            ),
        ),
    )

    private fun preferenceToJson(preference: RoleModelPreference): JsonValue.Obj {
        val fields = LinkedHashMap<String, JsonValue>()
        fields["providerId"] = Json.of(preference.providerId)
        preference.model?.let { fields["model"] = Json.of(it) }
        preference.connectionId?.let { fields["connectionId"] = Json.of(it) }
        return JsonValue.Obj(fields)
    }

    private fun decodeChains(root: JsonObject): Map<AgentRole, List<RoleModelPreference>> {
        val chains = root.objectOrNull("chains") ?: return emptyMap()
        val decoded = LinkedHashMap<AgentRole, List<RoleModelPreference>>()
        chains.forEach { (key, value) ->
            val role = AgentRole.entries.firstOrNull { it.name == key } ?: return@forEach
            val chain = value.arrayOrNull()
                ?.mapNotNull { item ->
                    val fields = item.objectOrNull() ?: return@mapNotNull null
                    // One corrupt entry must cost only itself. Constructing a
                    // preference with a blank identity throws by design, and letting
                    // that escape would discard the whole stored configuration — so
                    // an entry that cannot state which model it means is dropped here,
                    // and the entries around it survive.
                    if (fields.stringOrNull("providerId").isNullOrBlank()) {
                        null
                    } else {
                        runCatching { jsonToPreference(fields) }.getOrNull()
                    }
                }
                .orEmpty()
            if (chain.isNotEmpty()) decoded[role] = chain
        }
        return decoded
    }

    private fun jsonToPreference(fields: JsonObject): RoleModelPreference = RoleModelPreference(
        providerId = fields.stringOrNull("providerId").orEmpty(),
        model = fields.stringOrNull("model")?.takeIf { it.isNotBlank() },
        connectionId = fields.stringOrNull("connectionId")?.takeIf { it.isNotBlank() },
    )
}
