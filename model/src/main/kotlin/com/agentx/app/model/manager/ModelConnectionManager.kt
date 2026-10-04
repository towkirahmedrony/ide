package com.agentx.app.model.manager

import com.agentx.app.model.preset.ModelPreset
import com.agentx.app.model.preset.validateEndpointUrl
import com.agentx.app.model.runtime.EndpointSource
import com.agentx.app.model.runtime.ModelEndpoint
import com.agentx.app.model.runtime.ModelLifecycleState

/**
 * The connection *lifecycle rules* for one [ModelConnectionKind].
 *
 * The model layer already has one thing called a connection: the provider
 * registration held by [ModelConnectionRegistry] and routed to by the Model Gateway.
 * That stays single and shared — one registry, one credential store, one
 * [com.agentx.app.model.ModelConfig] shape, one gateway. What was shared by accident
 * is the *lifecycle around it*: when a connection may exist, and what losing
 * reachability means. That is the whole of this interface.
 *
 * Why it has to be split:
 *
 * - An API provider's connection *is* its saved configuration (provider identity,
 *   endpoint, credential reference, model). Nothing about it needs a runtime to be
 *   up, so a failed request is a health observation and never a lost connection.
 * - A local/custom connection *is* its live endpoint. It may legitimately be absent
 *   (server stopped, tunnel down), so losing it releases the connection and a bounded
 *   recovery is worth attempting.
 *
 * Reusing the local rules for an API provider is what made a working Gemini/Groq
 * preset disappear when a probe failed, and what made local reconnect loops able to
 * disturb API provider state. The rules below are the only place the two differ, so
 * they cannot drift back together.
 *
 * This is deliberately not a second registry, a second credential system or a second
 * descriptor: it decides *policy* and leaves those to the shared components.
 */
interface ModelConnectionManager {

    /** The kind of connection this manager owns the rules for. */
    val kind: ModelConnectionKind

    /** Whether this manager owns [preset]'s connection lifecycle. */
    fun handles(preset: ModelPreset): Boolean

    /**
     * Whether the saved configuration alone is enough for the connection to exist,
     * or whether a live runtime endpoint must be detected and must answer first.
     */
    val connectsFromConfiguration: Boolean

    /**
     * Whether an endpoint that stopped answering is re-established by a bounded
     * reconnect sequence, and whether the connection is re-checked periodically.
     */
    val recoversLapsedRuntime: Boolean

    /**
     * The endpoint this connection is addressed by, or null when there is none yet.
     *
     * [runtimeEndpoint] is what a runtime published (detected from tunnel output, a
     * device port, or the configured URL). A configuration-backed connection ignores
     * it: its address comes from the preset, not from a runtime.
     */
    fun endpointFor(preset: ModelPreset, runtimeEndpoint: ModelEndpoint?): ModelEndpoint?

    /**
     * The state reported while the model API cannot be reached.
     *
     * A configuration-backed connection is still configured, so losing reachability
     * is reported as degraded health. A runtime-backed connection has nothing behind
     * it, so it is reported as disconnected.
     */
    fun unreachableState(): ModelLifecycleState
}

/**
 * The API provider lifecycle: the connection is the saved configuration and is
 * persistent.
 *
 * Established when the provider is connected (credential saved, model selected) and
 * kept until the user edits, disconnects or removes it. Reachability is health, not
 * configuration: a provider that is temporarily unavailable, rate limited, or behind
 * a bad network never loses its endpoint, credential, model selection or gateway
 * registration.
 */
class ApiModelConnectionManager : ModelConnectionManager {

    override val kind: ModelConnectionKind = ModelConnectionKind.API

    override fun handles(preset: ModelPreset): Boolean = preset.connectionKind == kind

    override val connectsFromConfiguration: Boolean = true

    /** There is no runtime to recover and nothing to poll: the saved configuration is the connection. */
    override val recoversLapsedRuntime: Boolean = false

    /**
     * The provider's own configured endpoint, which is what the connect flow persisted
     * and what every request is addressed to. A runtime-detected endpoint is never
     * preferred over it: an API provider is not discovered, it is configured.
     */
    override fun endpointFor(preset: ModelPreset, runtimeEndpoint: ModelEndpoint?): ModelEndpoint? =
        configuredEndpoint(preset) ?: runtimeEndpoint

    override fun unreachableState(): ModelLifecycleState = ModelLifecycleState.DEGRADED

    /** The configured endpoint, validated exactly as discovery validates a preset. */
    private fun configuredEndpoint(preset: ModelPreset): ModelEndpoint? {
        val raw = preset.endpoint.explicitUrl?.takeIf { it.isNotBlank() } ?: return null
        val url = validateEndpointUrl(raw, preset.providerType.requiresSecureEndpoint) ?: return null
        return ModelEndpoint(url, EndpointSource.CONFIGURED)
    }
}

/**
 * The Local / Custom lifecycle: the connection is the live endpoint the user runs.
 *
 * Unchanged from the behaviour this project already had — endpoint discovery, a
 * reachability gate before the connection exists, a bounded reconnect when it lapses,
 * and a disconnect when the endpoint is gone. Keeping it in one object is what lets
 * the API lifecycle stop borrowing it.
 */
class LocalModelConnectionManager : ModelConnectionManager {

    override val kind: ModelConnectionKind = ModelConnectionKind.LOCAL_CUSTOM

    override fun handles(preset: ModelPreset): Boolean = preset.connectionKind == kind

    override val connectsFromConfiguration: Boolean = false

    override val recoversLapsedRuntime: Boolean = true

    /**
     * Only what the runtime actually published. A local/custom endpoint has no
     * configured address it could be trusted to reconnect to without a runtime
     * confirming it, which is exactly why its lifecycle is runtime-gated.
     */
    override fun endpointFor(preset: ModelPreset, runtimeEndpoint: ModelEndpoint?): ModelEndpoint? = runtimeEndpoint

    override fun unreachableState(): ModelLifecycleState = ModelLifecycleState.DISCONNECTED
}
