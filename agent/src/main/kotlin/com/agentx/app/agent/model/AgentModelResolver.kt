package com.agentx.app.agent.model

import com.agentx.app.model.health.CandidateHealthTracker

import com.agentx.app.agent.domain.AgentDefinition
import com.agentx.app.agent.domain.AgentError
import com.agentx.app.agent.domain.AgentErrorCode
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.capability.InMemoryModelCapabilityRegistry
import com.agentx.app.model.capability.ModelCapability
import com.agentx.app.model.capability.ModelCapabilityErrors
import com.agentx.app.model.capability.ModelCapabilityDeclaration
import com.agentx.app.model.capability.ModelCapabilityProfile
import com.agentx.app.model.capability.ModelCapabilityRegistry
import com.agentx.app.model.capability.capabilityProfile
import com.agentx.app.model.capability.statedCapabilitiesFor
import com.agentx.app.model.manager.ModelConnectionKind
import com.agentx.app.model.preset.ModelProviderIds
import com.agentx.app.model.preset.stating
import com.agentx.app.model.ratelimit.RateLimitManager

/**
 * Logical provider identifiers the role → model mapping refers to.
 *
 * These are not registered providers: a role preference naming one of them
 * resolves only when a matching connection is supplied to [AgentModelResolver].
 * Nothing here creates a provider, holds a credential, or performs a network
 * request, so a role may name a provider that is not connected yet.
 */
object AgentModelProviders {
    const val GEMINI = ModelProviderIds.GEMINI
    const val GROQ = ModelProviderIds.GROQ
    const val OPENAI_COMPATIBLE = ModelProviderIds.OPENAI_COMPATIBLE
    const val CEREBRAS = ModelProviderIds.CEREBRAS
    const val MISTRAL = ModelProviderIds.MISTRAL
    const val OPENROUTER = ModelProviderIds.OPENROUTER
    const val CLOUDFLARE = ModelProviderIds.CLOUDFLARE
    const val NVIDIA_NIM = ModelProviderIds.NVIDIA_NIM
    const val FREELMAPI = ModelProviderIds.FREELMAPI

    /**
     * The local model runtime (for example Devstral 24B). It shares the
     * [OPENAI_COMPATIBLE] identity: one provider family, distinguished per
     * connection by its endpoint and model. Kept as an alias so the Phase 1
     * role mapping and its tests keep compiling unchanged.
     */
    const val OPENAI_COMPATIBLE_LOCAL = ModelProviderIds.OPENAI_COMPATIBLE
}

/**
 * Model identifiers the target role mapping asks for.
 *
 * `GEMINI` is the Flash model the provider catalog offers as its preferred
 * model, so the default mapping names a model that actually exists in the
 * configured catalog rather than an arbitrary one.
 */
object AgentModelIds {
    const val GEMINI = "gemini-3.5-flash"
    const val GROQ = "llama-3.3-70b-versatile"
    const val QWEN_CODER = "qwen2.5-coder-14b"

    /** The canonical local model MAIN/CODER/DEBUGGER target. */
    const val DEVSTRAL_24B = "devstral-24b"

    /**
     * Gemini Flash, reached through the FreeLLMAPI gateway.
     *
     * The explicit model the Reviewer runs on. It is a concrete model *id* — not a
     * provider family and not the gateway's own default — so the completion AgentX
     * sends names it and the gateway cannot substitute another Gemini model.
     */
    const val FREELLMAPI_GEMINI = "gemini-2.5-flash"

    /**
     * Groq's GPT-OSS 20B, reached through the FreeLLMAPI gateway.
     *
     * The explicit model the Explorer runs on. AgentX selects it; FreeLLMAPI's own
     * provider-side routing may still apply, but the request already states the
     * intended model rather than relying on the gateway's implicit default.
     */
    const val FREELLMAPI_GROQ = "openai/gpt-oss-20b"
}

/**
 * The provider (and, optionally, the model) one role should use when it is
 * available. Identifiers only: never an endpoint and never a credential.
 */
data class RoleModelPreference(
    val providerId: String,
    /** Optional model within [providerId]; a definition's own preference takes precedence. */
    val model: String? = null,
    /**
     * Optional saved connection (preset) identity this role was assigned from.
     *
     * When present it wins over [providerId], because several independent
     * connections can share one provider family (two custom OpenAI-compatible
     * endpoints, or a custom endpoint and an Ollama server). A preference that
     * names a connection is treated as an explicit assignment: if that exact
     * connection is not connected, resolution fails rather than degrading to the
     * provider-family rules, so a saved assignment is never silently answered by
     * a different connection of the same family.
     * Null means "resolve by provider family", which is what the built-in
     * defaults use and what a role configured before connections had identities
     * keeps doing.
     */
    val connectionId: String? = null,
    /**
     * The execution domain ([ModelConnectionKind.LOCAL_CUSTOM] or [ModelConnectionKind.API])
     * this preference is bound to.
     *
     * A non-null domain makes the preference domain-constrained: resolution only ever
     * selects a connection of that exact domain, and never falls back to a connection
     * in the other domain. This is what guarantees MAIN/CODER/DEBUGGER stay on the
     * local model even when a FreeLLMAPI/Gemini API connection exists, and what lets
     * API roles target the API domain.
     *
     * Being domain-constrained is deliberately *not* the same as being the user's own
     * assignment (see [explicit]): the built-in MAIN/CODER/DEBUGGER defaults name the
     * local domain because the role's policy says "this role runs locally", not because
     * the user bound a connection. A domain-constrained preference still must not cross
     * domains, and several unnamed candidates in it still fail rather than resolve by
     * listing order, but a default with no candidate reports that nothing is configured
     * rather than naming a connection the user never created.
     * Null preserves the legacy "match by provider family alone" behaviour.
     */
    val domain: ModelConnectionKind? = null,
    /**
     * Whether this preference is an explicit role assignment (the user's saved
     * Settings choice) rather than a policy default.
     *
     * An explicit assignment is authoritative: when the provider/connection it
     * names cannot be addressed, the resolver returns a structured failure and
     * never substitutes the active model. A built-in *policy default* is not the
     * user's assignment — it reports the unconfigured state instead of a missing
     * connection its domain has nothing to offer, and a family-only default keeps the
     * documented compatibility behaviour. A preference that names a [connectionId] is
     * authoritative regardless of this flag.
     */
    val explicit: Boolean = false,
    /**
     * The user's statement that the model this role is assigned to calls tools.
     *
     * A role can be assigned any model a connection serves — the picker offers the
     * gateway's whole catalog — while the connection itself can only state what is saved
     * on it, so a model chosen here could otherwise never acquire the capability a
     * tool-using role requires. The statement belongs to the one `(providerId, model)`
     * pair this preference names: it is applied to that model alone, never to the
      * connection, never to its other models, and never to another role. It is applied
      * whichever branch resolves the assignment — a named connection, the active
      * connection of the same family and domain, or a family match — and to the model
      * that is finally resolved, so it cannot be lost on the way to the eligibility
      * check (see [statingToolCalling]).
      *
      * False by default: nothing is claimed on the user's behalf, and a model nobody
     * stated anything about keeps resolving to `UNKNOWN`.
     */
    val declaresToolCalling: Boolean = false,
) {
    init {
        require(providerId.isNotBlank()) { "providerId must not be blank" }
    }
}

/**
 * The declarative role → model mapping.
 *
 * [DEFAULT] encodes the target architecture. It is a *preference*: a role whose
 * provider is not connected simply falls back to the active configuration, so
 * the mapping can describe Gemini/Groq/Qwen before any of them exist.
 */
data class AgentModelPreferences(
    val byRole: Map<AgentRole, RoleModelPreference> = emptyMap(),
) {
    operator fun get(role: AgentRole): RoleModelPreference? = byRole[role]

    fun with(role: AgentRole, preference: RoleModelPreference): AgentModelPreferences =
        copy(byRole = byRole + (role to preference))

    companion object {
        /** No role-specific configuration: every role uses the active model. */
        val EMPTY: AgentModelPreferences = AgentModelPreferences()

        /**
         * The desired mapping. Local roles are domain-constrained to
         * [ModelConnectionKind.LOCAL_CUSTOM] and API roles to
         * [ModelConnectionKind.API], so resolution is deterministic: adding an API
         * connection can never move a local role onto an API model, and vice versa.
         *
         * ```
         * MAIN       → LOCAL  → openai-compatible (local) → devstral-24b
         * CODER      → LOCAL  → openai-compatible (local) → devstral-24b
         * DEBUGGER   → LOCAL  → openai-compatible (local) → devstral-24b
         * REVIEWER   → API    → freellmapi → gemini-2.5-flash
         * EXPLORER   → API    → freellmapi → openai/gpt-oss-20b
         * RESEARCHER → API    → freellmapi → the configured API model
         * TESTER     → API    → groq → llama-3.3-70b-versatile
         *
         * Each API role names a concrete model id, and each role names the provider
         * *identity* it runs on, so nothing is resolved by provider family, protocol
         * or the order connections happen to be listed in.
         * ```
         */
        val DEFAULT: AgentModelPreferences = AgentModelPreferences(
            mapOf(
                AgentRole.MAIN to RoleModelPreference(
                    AgentModelProviders.OPENAI_COMPATIBLE_LOCAL,
                    AgentModelIds.DEVSTRAL_24B,
                    domain = ModelConnectionKind.LOCAL_CUSTOM,
                ),
                AgentRole.CODER to RoleModelPreference(
                    AgentModelProviders.OPENAI_COMPATIBLE_LOCAL,
                    AgentModelIds.DEVSTRAL_24B,
                    domain = ModelConnectionKind.LOCAL_CUSTOM,
                ),
                AgentRole.DEBUGGER to RoleModelPreference(
                    AgentModelProviders.OPENAI_COMPATIBLE_LOCAL,
                    AgentModelIds.DEVSTRAL_24B,
                    domain = ModelConnectionKind.LOCAL_CUSTOM,
                ),
                AgentRole.REVIEWER to RoleModelPreference(
                    AgentModelProviders.FREELMAPI,
                    AgentModelIds.FREELLMAPI_GEMINI,
                    domain = ModelConnectionKind.API,
                ),
                AgentRole.EXPLORER to RoleModelPreference(
                    AgentModelProviders.FREELMAPI,
                    AgentModelIds.FREELLMAPI_GROQ,
                    domain = ModelConnectionKind.API,
                ),
                AgentRole.RESEARCHER to RoleModelPreference(
                    AgentModelProviders.FREELMAPI,
                    AgentModelIds.FREELLMAPI_GEMINI,
                    domain = ModelConnectionKind.API,
                ),
                AgentRole.TESTER to RoleModelPreference(
                    AgentModelProviders.GROQ,
                    AgentModelIds.GROQ,
                    domain = ModelConnectionKind.API,
                ),
            ),
        )
    }
}

/**
 * Resolves the [ModelConfig] one [AgentRole] should run with.
 *
 * Pure and deterministic: it performs no network request, creates no provider,
 * executes no tool, and never touches [com.agentx.app.model.ModelGateway]. It
 * only selects among configurations the caller already has, which keeps the
 * resolution layer independent of how connections are established.
 *
 * Resolution order:
 * 1. No role preference → the [default] configuration.
 * 2. The preferred connection is named by [RoleModelPreference.connectionId] →
 *    that exact connection when connected. When it is not connected resolution
 *    fails with [AgentErrorCode.MODEL_NOT_CONNECTED]; a named assignment is never
 *    answered by another connection.
 * 3. The preferred provider is [default]'s provider → [default] with the role's
 *    model when one is known.
 * 4. A supplied [connections] entry of the preferred provider family → that
 *    configuration, with the role's model when one is known.
 * 5. The preferred provider/connection is not connected:
 *    - the user's own assignment ([RoleModelPreference.explicit], or a preference
 *      that names a connection) → a structured failure, never a substitute model;
 *    - a domain-constrained *policy default* → an unconfigured-default failure,
 *      reported with [AgentErrorCode.NOT_CONFIGURED]: the role has no model of its
 *      own domain configured yet. It is named as a default (`defaultConnection=`,
 *      `explicit=false`, `assignment=default`) with an actionable message, never as
 *      a connection the user did not create, and its domain is never crossed;
 *    - a legacy family-only policy default → the [default] configuration, so an
 *      unconnected built-in preference never breaks a run that works today.
 *
 * The distinction in step 5 is what makes the user's own role → model assignment
 * authoritative while keeping the built-in mapping's compatibility behaviour, and
 * what keeps a fresh install reporting "no model configured for this role" (the same
 * state Settings shows) instead of a missing connection.
 */
class AgentModelResolver(
    private val preferences: AgentModelPreferences = AgentModelPreferences.EMPTY,
    /**
     * The configurations the caller already has, keyed by connection identity
     * ([ModelConfig.connectionId]). A preference that names a connection is
     * resolved by that key; one that names only a provider family is resolved
     * against the configurations' [ModelConfig.providerId]. Empty means "only the
     * active model".
     */
    private val connections: () -> Map<String, ModelConfig> = { emptyMap() },
    /**
     * Optional live source consulted on every [resolve]. Settings writes through
     * [com.agentx.app.agent.model.AgentRoleModelRegistry] and the resolver picks
     * the new mapping up without being rebuilt. When null the fixed
     * [preferences] value is used.
     */
    private val livePreferences: (() -> AgentModelPreferences)? = null,
    /**
     * Authoritative capability lookup used by [validate] and [resolveChecked].
     * [resolve] itself stays a pure config selection and does not consult this.
     */
    private val capabilityRegistry: ModelCapabilityRegistry = InMemoryModelCapabilityRegistry.DEFAULT,
    /**
     * Admission control consulted by [resolveForRole], never by the pure
     * [resolve]. A null manager means rate limiting is not configured for this
     * host, so capabilities alone decide eligibility, exactly as before this
     * phase. Quota is only evaluated here, never reserved.
     */
    private val rateLimitManager: RateLimitManager? = null,
    /**
     * Observed provider/model health, consulted by [resolveForRole] alongside
     * capabilities and quota so a candidate inside its cooldown is never chosen.
     * A null tracker means health is not recorded for this host.
     */
    private val healthTracker: CandidateHealthTracker? = null,
    /**
     * Whether an intentional fallback chain is configured for [AgentRole].
     *
     * Purely informational for the structured resolution failure: it lets the
     * error report that a substitution is possible only through the explicitly
     * configured fallback policy, never through hidden resolution. The resolver
     * itself never falls back; it never consults or triggers the fallback layer.
     */
    private val intentionalFallback: (AgentRole) -> Boolean = { false },
) {

    /**
     * The single shared capability + rate-limit check. Every role resolves
     * through it, so no individual agent reimplements eligibility.
     */
    private val eligibilityChecker: ModelEligibilityChecker =
        ModelEligibilityChecker(capabilityRegistry, rateLimitManager, healthTracker)

    /** The mapping in effect for this resolution: the live source when present. */
    private fun currentPreferences(): AgentModelPreferences = livePreferences?.invoke() ?: preferences

    /** Resolves [role]'s configuration from the role mapping alone. */
    fun resolve(role: AgentRole, default: ModelConfig): ModelConfig =
        resolve(role, preferredModel = null, default = default)

    /** Resolves a definition's role, honoring [AgentDefinition.modelPreference]. */
    fun resolve(definition: AgentDefinition, default: ModelConfig): ModelConfig =
        resolve(definition.role, definition.modelPreference, default)

    /**
     * @param preferredModel the caller's model preference (for example an
     *   [AgentDefinition.modelPreference]); when blank, the role's configured
     *   model is used.
     *
     * @throws AgentModelResolutionException when an explicit role assignment's
     *   connection/provider is not connected. The configured model is never
     *   silently replaced.
     */
    fun resolve(role: AgentRole, preferredModel: String?, default: ModelConfig): ModelConfig {
        val selection = select(role, preferredModel, default)
        selection.error?.let { throw AgentModelResolutionException(it) }
        return checkNotNull(selection.config) { "selection without config and without error" }
    }

    /**
     * Internal selection shared by [resolve] and [resolveForRole]. It records
     * whether the role's own mapping produced the config, so an explicit role
     * assignment can be reported as such, and carries a structured error when the
     * role's target cannot be addressed — the user's missing assignment
     * ([connectionFailure]), several unnamed candidates for a domain-constrained
     * role ([ambiguousConnectionFailure]), or a built-in default with nothing
     * configured in its domain ([unconfiguredDefaultFailure]).
     */
    private fun select(role: AgentRole, preferredModel: String?, default: ModelConfig): Selection {
        val preference = currentPreferences()[role] ?: return Selection(default, fromRoleMapping = false)
        val providerId = preference.providerId
        val domain = preference.domain
        // The role's own mapping (the user's Settings choice, or the built-in
        // default) wins over the agent definition's static model; the definition
        // is only consulted when the role names a provider but no model. That is
        // what makes a model picked in Settings take effect at run time.
        val model = preference.model?.takeIf { it.isNotBlank() }
            ?: preferredModel?.takeIf { it.isNotBlank() }
        // Two independent questions, deliberately kept apart instead of folded into one
        // "authoritative" flag. They are not the same thing, and conflating them is what
        // made a fresh install unusable:
        //
        //  - `userAssignment` — is this the user's own choice: the assignment saved in
        //    Settings, or a preference that names one specific saved connection? Only a
        //    user's assignment is authoritative, and only it fails as an assignment.
        //  - `domainConstrained` — does the preference name an execution domain? MAIN's
        //    built-in preference does ("MAIN runs on the local runtime"), but that is a
        //    property of the role's policy, not evidence that the user bound a
        //    connection. It constrains which connections may answer the role, and it is
        //    what makes several same-domain candidates fail rather than resolve by the
        //    order they happen to be listed in — but it must never turn a built-in
        //    default into a user assignment.
        //
        // Reading the domain as authoritativeness is the original defect: MAIN's default
        // is domain-bound to the local runtime, so with no local connection yet the
        // resolver took the "explicit assignment" failure branch and named a connection,
        // and a model, the user had never configured.
        val namedId = preference.connectionId?.takeIf { it.isNotBlank() }
        val userAssignment = preference.explicit || namedId != null
        val domainConstrained = domain != null

        // A specific saved connection is addressed by its own identity, so a role
        // assigned to one custom endpoint never resolves to another endpoint of the
        // same provider family. A named connection that is not connected right now
        // (disconnected or deleted, or a map keyed differently) is a hard failure:
        // the saved identity is preserved and reported instead of being replaced.
        if (namedId != null) {
            val named = connections()[namedId]
                ?: return Selection(
                    config = null,
                    fromRoleMapping = true,
                    error = connectionFailure(role, preference, model, namedId),
                )
            return Selection(withAssignedStatement(withModel(named, model), preference), fromRoleMapping = true)
        }

        // The active model is used only when it satisfies the preference's provider
        // family *and* its execution domain, so a local role never picks up an API
        // active model (and vice versa) just because the provider family matches.
        //
        // The assignment's own statement is applied here too. This branch resolves the
        // role's configured model out of the active connection, and it is the branch a
        // role takes whenever the connection it was assigned from *is* the active one —
        // the ordinary case for a gateway whose model list the role was assigned from.
        // Returning the model without the statement is what made a declared assignment
        // arrive at the eligibility check undeclared (`declared=false`) and be rejected
        // for the capability the user had just stated for it.
        if (providerId == default.providerId && matchesDomain(default, domain)) {
            return Selection(withAssignedStatement(withModel(default, model), preference), fromRoleMapping = true)
        }

        val candidates = connectionsForProvider(providerId, domain)
        // Several connections of the required identity sit in the required domain and
        // the preference names none of them. Choosing one would be routing by the
        // order the connections happen to be listed in, so a domain-constrained role
        // (or an authoritative one) fails instead: the target stays intact and the user
        // binds one exact connection in Settings, which is then addressed by identity. A
        // legacy family-only preference (no domain, not explicit) keeps its documented
        // first-match behaviour.
        //
        // CRITICAL: When the user has an explicit assignment (userAssignment == true),
        // we must NOT silently substitute a candidate connection — the user's
        // configured model must remain the effective assignment. The ambiguity check
        // above only applies when there are multiple candidates; when there is exactly
        // one candidate and the user has an explicit assignment, we still report a
        // connection failure so the assignment is not overridden by the built-in default.
        if (candidates.size > 1 && !userAssignment && !domainConstrained) {
            return Selection(
                config = null,
                fromRoleMapping = true,
                error = ambiguousConnectionFailure(role, preference, model, candidates.map { it.connectionId }),
            )
        }
        // When there's exactly one candidate and the user does NOT have an explicit
        // assignment, use that candidate with the statement applied.
        if (candidates.size == 1 && !userAssignment) {
            return Selection(
                withAssignedStatement(withModel(candidates.firstOrNull()!!, model), preference),
                fromRoleMapping = true,
            )
        }
        // When there's exactly one candidate and the user DOES have an explicit
        // assignment, report a connection failure rather than substituting the
        // candidate — this prevents the built-in default from silently overriding
        // the user's configured model, which is the root cause of the HARDCODED
        // provenance mismatch reported on device.
        if (candidates.size == 1 && userAssignment) {
            return Selection(
                config = null,
                fromRoleMapping = true,
                error = connectionFailure(role, preference, model, providerId),
            )
        }
        // If there are no candidates and we don't have a user assignment or domain
        // constraint, fall through to the connection-failure or default branches below.
        if (candidates.isEmpty() && !userAssignment && !domainConstrained) {
            // Fall through to the connection-failure or default branches below.
        }

        // The provider family is not connected in the required domain. The user's own
        // assignment may not be answered by anything else, which is what keeps
        // MAIN/CODER/DEBUGGER on their saved local model when only an API connection
        // exists. When the user has an explicit assignment, we report a connection
        // failure rather than silently substituting the built-in default, which would
        // override the user's configured model — the exact defect that produces the
        // HARDCODED provenance mismatch reported on device.
        if (userAssignment) {
            return Selection(
                config = null,
                fromRoleMapping = true,
                error = connectionFailure(role, preference, model, providerId),
            )
        }
        // A built-in *policy default* whose target is not connected at all. Nothing the
        // user chose is missing, so this is not a connection failure: it is the
        // documented fresh-install state, "this role has no model configured yet". It is
        // still a failure — a default's domain is never crossed, so an API connection
        // never answers a local role, and the active model is never substituted across
        // domains — but it must be reported as an unconfigured default rather than as a
        // missing connection, which named a connection (and a model) the user had never
        // created and made a fresh install read as a broken pinned assignment.
        if (domainConstrained) {
            return Selection(
                config = null,
                fromRoleMapping = true,
                error = unconfiguredDefaultFailure(role, preference, model),
            )
        }
        return Selection(default, fromRoleMapping = false)
    }

    /**
     * Every connected configuration of one provider identity within [domain]. Scans
     * the values because the connection set is keyed by connection identity, not
     * provider family, so two connections of one identity are two entries. A null
     * domain matches any domain (legacy behaviour).
     */
    private fun connectionsForProvider(providerId: String, domain: ModelConnectionKind?): List<ModelConfig> =
        connections().values.filter { it.providerId == providerId && matchesDomain(it, domain) }

    /**
     * The connection a preference with no explicit identity resolves to: the single
     * connected configuration of that provider family within [domain], or the first
     * one for a legacy family-only preference. Callers that must not route by
     * connection order use [connectionsForProvider] and judge the count themselves.
     */
    private fun connectionForProvider(providerId: String, domain: ModelConnectionKind? = null): ModelConfig? =
        connectionsForProvider(providerId, domain).firstOrNull()

    /** Whether [config] belongs to [domain]; a null domain matches everything. */
    private fun matchesDomain(config: ModelConfig, domain: ModelConnectionKind?): Boolean =
        domain == null || config.connectionKind == domain

    /**
     * The outcome of [select]: a resolved configuration, or a structured failure
     * when an explicit assignment cannot be addressed ([config] is null then).
     */
    private data class Selection(
        val config: ModelConfig?,
        val fromRoleMapping: Boolean,
        val error: AgentError? = null,
    )

    /**
     * How many connected configurations of [providerId] exist *outside* [domain].
     *
     * Diagnostics only, and the one thing that separates "no such connection exists at
     * all" from "a connection of that provider family exists but cannot serve this
     * role" — two states the user repairs differently. It never influences selection.
     * Reads connection identifiers only: never an endpoint and never a credential.
     */
    private fun sameFamilyOtherDomain(providerId: String, domain: ModelConnectionKind?): Int =
        connections().values.count { it.providerId == providerId && !matchesDomain(it, domain) }

    /**
     * The structured failure for the *user's own* assignment whose provider or saved
     * connection is not currently connected.
     *
     * The role, the requested provider/connection, the requested model and whether an
     * intentional fallback is configured are carried on [AgentError.details], so the
     * UI/runtime can explain the failure instead of silently executing the role on a
     * different model. `assignment` and `explicit` say this was the user's choice, and
     * `sameFamilyOtherDomain` says whether an unusable connection of the family exists
     * in the other execution domain. It never contains a credential or an endpoint.
     */
    private fun connectionFailure(
        role: AgentRole,
        preference: RoleModelPreference,
        model: String?,
        missingConnectionId: String,
    ): AgentError {
        val named = preference.connectionId?.takeIf { it.isNotBlank() }
        // A named saved connection is reported by its own identity; an explicit
        // provider-family assignment (no saved connection id) by the provider identity it
        // names. Either way this is the user's assignment, so the target is a
        // `connection=` — the built-in default's target is named separately, by
        // [unconfiguredDefaultFailure], where nothing the user chose is missing.
        val connection = named ?: missingConnectionId
        val requestedModel = model ?: preference.model
        val fallbackConfigured = intentionalFallback(role)
        val otherDomain = sameFamilyOtherDomain(preference.providerId, preference.domain)
        return AgentError(
            code = AgentErrorCode.MODEL_NOT_CONNECTED,
            message = "MODEL_NOT_CONNECTED role=${role.name} provider=${preference.providerId} " +
                "connection=$connection model=${requestedModel ?: "(provider default)"} " +
                "reason=CONNECTION_NOT_CONNECTED fallbackAvailable=$fallbackConfigured " +
                "explicit=${preference.explicit} assignment=user",
            role = role,
            details = buildMap {
                put("role", role.name)
                put("provider", preference.providerId)
                put("connection", connection)
                requestedModel?.let { put("model", it) }
                put("reason", "CONNECTION_NOT_CONNECTED")
                put("cause", "MODEL_NOT_CONNECTED")
                put("explicit", preference.explicit.toString())
                put("assignment", "user")
                put("fallbackAvailable", fallbackConfigured.toString())
                preference.domain?.let { put("domain", it.name) }
                if (otherDomain > 0) put("sameFamilyOtherDomain", otherDomain.toString())
            },
        )
    }

    /**
     * The structured failure for a *built-in policy default* whose target is not
     * connected at all — the fresh-install state.
     *
     * A default is not an assignment: the user chose nothing, so nothing of theirs is
     * missing and no connection may be claimed to exist. Reporting this through
     * [connectionFailure] named the default's provider as a `connection=`, so a fresh
     * install read as though the user had pinned a connection that does not exist; the
     * reading, blocked a run, and sent the first investigation after a phantom
     * connection.
     *
     * It is therefore reported as what it is: the role has no model configured yet. The
     * error code is [AgentErrorCode.NOT_CONFIGURED] — the same state the runtime already
     * uses for "no model is configured" and the same state Settings reports for this
     * role — with an actionable instruction. The target is named as a default
     * (`defaultConnection=`), `explicit=false` and `assignment=default` say no one chose
     * it, and `otherDomain`-family connections are counted so an unusable connection in
     * the wrong domain is distinguishable from no connection at all. It never contains a
     * credential or an endpoint, and it never names a connection the user does not have.
     */
    private fun unconfiguredDefaultFailure(
        role: AgentRole,
        preference: RoleModelPreference,
        model: String?,
    ): AgentError {
        val requestedModel = model ?: preference.model
        val fallbackConfigured = intentionalFallback(role)
        val target = preference.providerId
        val label = RoleModelEvaluation.providerLabel(preference.providerId)
        val otherDomain = sameFamilyOtherDomain(preference.providerId, preference.domain)
        return AgentError(
            code = AgentErrorCode.NOT_CONFIGURED,
            message = "No model is connected for the ${role.name} agent. Open Settings → Models and " +
                "connect $label${requestedModel?.let { " ($it)" } ?: ""}, or assign this agent a model " +
                "that is connected. " +
                "MODEL_NOT_CONFIGURED role=${role.name} provider=${preference.providerId} " +
                "defaultConnection=$target model=${requestedModel ?: "(provider default)"} " +
                "reason=NO_CONNECTION_CONFIGURED fallbackAvailable=$fallbackConfigured " +
                "explicit=false assignment=default connectionsInDomain=0" +
                (if (otherDomain > 0) " sameFamilyOtherDomain=$otherDomain" else ""),
            role = role,
            details = buildMap {
                put("role", role.name)
                put("provider", preference.providerId)
                put("defaultConnection", target)
                requestedModel?.let { put("model", it) }
                put("reason", "NO_CONNECTION_CONFIGURED")
                put("cause", "MODEL_NOT_CONFIGURED")
                put("explicit", "false")
                put("assignment", "default")
                put("fallbackAvailable", fallbackConfigured.toString())
                put("connectionsInDomain", "0")
                preference.domain?.let { put("domain", it.name) }
                if (otherDomain > 0) put("sameFamilyOtherDomain", otherDomain.toString())
            },
        )
    }

    /**
     * The structured failure for an authoritative assignment that matches more than
     * one connection of its provider identity and names none of them.
     *
     * It is reported through the same [AgentErrorCode.MODEL_NOT_CONNECTED] a missing
     * connection uses, because from the role's point of view the same thing happened:
     * the exact connection it is bound to is not addressable. `reason` and
     * `candidates` distinguish the two, so the UI can say "pick which connection"
     * rather than "add the provider". The candidate list carries connection ids
     * only — never an endpoint, a model or a credential.
     *
     * `explicit` and `assignment` are reported from the preference rather than assumed:
     * this branch is also reachable for a domain-constrained built-in default (two
     * connections in the role's domain, none named), and a policy default must not be
     * described as the user's own pinned assignment.
     */
    private fun ambiguousConnectionFailure(
        role: AgentRole,
        preference: RoleModelPreference,
        model: String?,
        candidateConnections: List<String>,
    ): AgentError {
        val requestedModel = model ?: preference.model
        val fallbackConfigured = intentionalFallback(role)
        val candidates = candidateConnections.joinToString(",")
        val userAssignment = preference.explicit || !preference.connectionId.isNullOrBlank()
        val assignment = if (userAssignment) "user" else "default"
        return AgentError(
            code = AgentErrorCode.MODEL_NOT_CONNECTED,
            message = "MODEL_NOT_CONNECTED role=${role.name} provider=${preference.providerId} " +
                "connection=(ambiguous) model=${requestedModel ?: "(provider default)"} " +
                "reason=CONNECTION_AMBIGUOUS candidates=$candidates fallbackAvailable=$fallbackConfigured " +
                "explicit=${preference.explicit} assignment=$assignment",
            role = role,
            details = buildMap {
                put("role", role.name)
                put("provider", preference.providerId)
                put("connection", preference.connectionId.orEmpty())
                requestedModel?.let { put("model", it) }
                put("reason", "CONNECTION_AMBIGUOUS")
                put("cause", "MODEL_NOT_CONNECTED")
                put("candidates", candidates)
                put("explicit", preference.explicit.toString())
                put("assignment", assignment)
                put("fallbackAvailable", fallbackConfigured.toString())
                preference.domain?.let { put("domain", it.name) }
            },
        )
    }

    /** The configured preference for [role], when any. */
    fun preference(role: AgentRole): RoleModelPreference? = currentPreferences()[role]

    /**
     * Resolves an explicit [preference] into a [ModelConfig] using the same
     * rules [select] applies to a role's configured preference: the default
     * model when the provider matches, otherwise the supplied connection, with
     * the preference's model overriding when one is named.
     *
     * Returns null when the preference's provider is not connected, so a caller
     * (the fallback layer) can skip a candidate instead of inventing an
     * endpoint. A named connection that is not connected yields null rather than
     * degrading to a different connection of the same family, so a candidate's
     * identity is preserved. It performs no network request and touches no
     * credential beyond what the supplied connection already holds.
     *
     * A candidate that carries its own capability statement keeps it, exactly as the
     * role's own selection does: a candidate judged without its statement would be
     * rejected for the capability the user stated for it, and a usable model would be
     * skipped by the layer that asked.
     */
    fun configFor(preference: RoleModelPreference, default: ModelConfig): ModelConfig? {
        val model = preference.model?.takeIf { it.isNotBlank() }
        val domain = preference.domain
        // A named connection is addressed by its own identity: when it is not
        // connected the candidate is skipped, never swapped for a sibling
        // connection of the same provider family.
        val namedId = preference.connectionId?.takeIf { it.isNotBlank() }
        if (namedId != null) {
            val named = connections()[namedId] ?: return null
            return withAssignedStatement(withModel(named, model), preference)
        }
        if (preference.providerId == default.providerId && matchesDomain(default, domain)) {
            return withAssignedStatement(withModel(default, model), preference)
        }
        val connection = connectionForProvider(preference.providerId, domain) ?: return null
        return withAssignedStatement(withModel(connection, model), preference)
    }

    /**
     * Evaluates an explicit [config] for [role] with the shared capability and
     * rate-limit check. Exposed so the fallback layer judges candidates exactly
     * as [resolveForRole] judges the role's configured model, without
     * reimplementing capability or quota logic.
     */
    suspend fun eligibilityFor(
        role: AgentRole,
        config: ModelConfig,
        requirements: ModelRequestRequirements = ModelRequestRequirements.DEFAULT,
    ): ModelEligibility = eligibilityChecker.check(role, config, requirements)

    /**
     * Same selection as [resolve], then checks that the chosen model meets
     * [role]'s required capabilities. Never switches provider or model.
     */
    fun resolveChecked(role: AgentRole, default: ModelConfig): ModelConfig {
        val config = resolve(role, default)
        validate(role, config).getOrThrow()
        return config
    }

    fun resolveChecked(definition: AgentDefinition, default: ModelConfig): ModelConfig {
        val config = resolve(definition, default)
        validate(definition.role, config).getOrThrow()
        return config
    }

    /**
     * Resolves [role]'s configuration and evaluates whether it may run right now.
     *
     * Unlike [resolve], this consults the authoritative capability registry and,
     * when one is configured, the [RateLimitManager]. It never reserves quota
     * (admission only) and never substitutes another model: the returned
     * [ModelResolutionResult] carries the selected config, the eligibility state
     * and a structured reason when it cannot run (see [ModelEligibilityState]).
     */
    suspend fun resolveForRole(
        role: AgentRole,
        default: ModelConfig,
        requirements: ModelRequestRequirements = ModelRequestRequirements.DEFAULT,
    ): ModelResolutionResult =
        resolveForRole(role = role, preferredModel = null, default = default, requirements = requirements)

    /**
     * @throws AgentModelResolutionException when an explicit role assignment's
     *   connection/provider is not connected. The runtime receives the same
     *   structured failure [resolve] produces; it never receives a substitute
     *   model.
     */
    suspend fun resolveForRole(
        role: AgentRole,
        preferredModel: String?,
        default: ModelConfig,
        requirements: ModelRequestRequirements = ModelRequestRequirements.DEFAULT,
    ): ModelResolutionResult {
        val selection = select(role, preferredModel, default)
        selection.error?.let { throw AgentModelResolutionException(it) }
        val config = checkNotNull(selection.config) { "selection without config and without error" }
        val eligibility = eligibilityChecker.check(role, config, requirements)
        return ModelResolutionResult(
            role = role,
            config = config,
            eligibility = eligibility,
            explicit = selection.fromRoleMapping,
        )
    }

    suspend fun resolveForRole(
        definition: AgentDefinition,
        default: ModelConfig,
        requirements: ModelRequestRequirements = ModelRequestRequirements.DEFAULT,
    ): ModelResolutionResult =
        resolveForRole(definition.role, definition.modelPreference, default, requirements)

    /**
     * Whether [config] can satisfy [role]. A missing capability is a structured
     * [AgentError] with [AgentErrorCode.MODEL_CAPABILITY_UNSUPPORTED]; this
     * phase never falls back to another model.
     */
    fun validate(role: AgentRole, config: ModelConfig): Result<Unit> {
        val required = AgentRoleRequirements.required(role)
        val profile = profileFor(config)
        val missing = required.filterNot { capability -> profile.supports(capability) }
        if (missing.isEmpty()) return Result.success(Unit)
        val first = missing.first()
        return Result.failure(
            AgentModelResolutionException(
                error = capabilityError(role, config, first, profile),
            ),
        )
    }

    fun canSatisfy(role: AgentRole, config: ModelConfig): Boolean = validate(role, config).isSuccess

    /**
     * The capability profile in effect for [config], through the one shared
     * precedence rule — so a capability that travels on the configuration (a
     * saved per-model declaration) is honored here exactly as the eligibility
     * checker and the gateway honor it.
     */
    private fun profileFor(config: ModelConfig): ModelCapabilityProfile =
        capabilityProfileFor(config)

    /**
     * The authoritative capability record for [config], exposed so callers outside
     * this class can read descriptor metadata — most importantly `maxContextTokens`
     * — through the one shared precedence rule rather than reconstructing it from
     * `providerId` and `model` and risking a different answer.
     *
     * Reads no credential and performs no network request.
     */
    fun capabilityProfileFor(config: ModelConfig): ModelCapabilityProfile =
        config.capabilityProfile(capabilityRegistry)

    private fun capabilityError(
        role: AgentRole,
        config: ModelConfig,
        capability: ModelCapability,
        profile: ModelCapabilityProfile,
    ): AgentError = AgentError(
        code = AgentErrorCode.MODEL_CAPABILITY_UNSUPPORTED,
        message = "${ModelCapabilityErrors.CODE} provider=${config.providerId} " +
            "model=${config.model} capability=${capability.id}",
        role = role,
        details = mapOf(
            "provider" to config.providerId,
            "model" to config.model,
            "capability" to capability.id,
            "known" to profile.known.toString(),
            "support" to profile.support(capability).name,
            // Where the resolved value came from, so a rejection can be traced to a
            // built-in definition, a saved declaration or the absence of both.
            "provenance" to profile.provenance.name,
            "local" to profile.local.toString(),
        ),
    )

    /**
     * [config] pointed at [model].
     *
     * A change of model re-resolves the capability statement from the connection's
     * own per-model statements: the statement made for [model] travels with it, and
     * a statement made for a *different* model is never inherited by it.
     *
     * That is the same rule as before — one model's statement never lends itself to
     * another — and it is what makes a connection that serves several models usable:
     * a role pointed at one of a gateway's models receives the statement the user
     * made for that exact model, instead of losing it and resolving to unknown.
     * A model the connection says nothing about arrives with no statement at all.
     */
    /**
     * Applies the statement an *assignment* carries, for the model that assignment names.
     *
     * The rule itself is [statingToolCalling], shared with the Settings screen that judges
     * the same assignment, so the two cannot disagree. It resolves nothing here: the
     * statement is applied to whatever model the configuration ended up naming, which is
     * the model the role will actually run — never to a model the assignment did not name,
     * and never to the connection's other models.
     *
     * Every path that resolves a role's own assignment goes through this — a named
     * connection, the active connection, and a connection matched by family — so a
     * declared assignment arrives at the eligibility check declared whichever branch
     * resolved it.
     *
     * The statement is also published to the shared capability registry under its own
     * `(providerId, model)`. The gateway resolves that pair rather than the configuration
     * when it decides whether to send tools, so a statement that only travelled on the
     * configuration would be read by the eligibility check and lost by the request.
     */
    private fun withAssignedStatement(config: ModelConfig, preference: RoleModelPreference): ModelConfig {
        if (!preference.declaresToolCalling) return config
        val stated = config.statingToolCalling(config.model)
        // Unchanged means the connection already states something for this model, which was
        // published when the connection was built.
        if (stated == config) return config
        stated.declaredCapabilities?.let { statement ->
            capabilityRegistry.registerOrUpdate(
                statement.applyTo(capabilityRegistry.profile(config.providerId, config.model)),
            )
        }
        return stated
    }

    private fun withModel(config: ModelConfig, model: String?): ModelConfig =
        if (model == null || model == config.model) {
            config
        } else {
            val moved = config.copy(model = model, declaredCapabilitiesModel = model)
            // Read only the connection's per-model statements: the statement the
            // configuration carried for its previous model belongs to that model, and
            // must not be read as this one's. Whatever this model's own statement turns
            // out to be — or nothing, when the connection says nothing about it — the
            // scope now names the model it belongs to, so it can never be inherited by
            // a later rebuild either.
            moved.copy(declaredCapabilities = moved.statedCapabilitiesFor(model))
        }
}

/** Thrown by [AgentModelResolver.resolveChecked] when a role's model cannot satisfy the role. */
class AgentModelResolutionException(
    val error: AgentError,
) : RuntimeException(error.message)
