package com.agentx.app.model.capability

/**
 * An explicit, per-configuration capability declaration.
 *
 * AgentX never infers a capability from a provider id or a model name. A model
 * that is only discovered, connected, or served by a user's own endpoint stays
 * [CapabilitySupport.UNKNOWN], and unknown is not support: a tool-enabled role
 * must not assume it. A user who runs their own server, however, knows things
 * AgentX cannot discover — for example that the GGUF they serve is an instruct
 * model spoken to with a tool-calling chat template — so this type is where that
 * knowledge is stated.
 *
 * A declaration belongs to one model/configuration. It is persisted on the
 * preset it was made for and materialized into the capability registry for
 * exactly that `providerId` + `modelId`. It is never a provider-wide default,
 * and every capability it does not state stays [CapabilitySupport.UNKNOWN], so
 * declaring tool calling for one custom model never makes another model of the
 * same provider look tool-capable.
 */
data class ModelCapabilityDeclaration(
    val toolCalling: CapabilitySupport = CapabilitySupport.UNKNOWN,
    val streaming: CapabilitySupport = CapabilitySupport.UNKNOWN,
    val vision: CapabilitySupport = CapabilitySupport.UNKNOWN,
    val structuredOutput: CapabilitySupport = CapabilitySupport.UNKNOWN,
    val reasoning: CapabilitySupport = CapabilitySupport.UNKNOWN,
) {

    /** The capabilities this declaration actually states, in enum order. */
    val declared: Map<ModelCapability, CapabilitySupport>
        get() {
            val stated = LinkedHashMap<ModelCapability, CapabilitySupport>()
            ModelCapability.entries.forEach { capability ->
                val resolution = support(capability)
                if (resolution != CapabilitySupport.UNKNOWN) stated[capability] = resolution
            }
            return stated
        }

    /** True when nothing is stated, so applying this declaration changes nothing. */
    val isEmpty: Boolean get() = declared.isEmpty()

    fun support(capability: ModelCapability): CapabilitySupport = when (capability) {
        ModelCapability.TOOL_CALLING -> toolCalling
        ModelCapability.STREAMING -> streaming
        ModelCapability.VISION -> vision
        ModelCapability.STRUCTURED_OUTPUT -> structuredOutput
        ModelCapability.REASONING -> reasoning
    }

    /** A copy with [capability] stated as [support]. */
    fun with(capability: ModelCapability, support: CapabilitySupport): ModelCapabilityDeclaration =
        when (capability) {
            ModelCapability.TOOL_CALLING -> copy(toolCalling = support)
            ModelCapability.STREAMING -> copy(streaming = support)
            ModelCapability.VISION -> copy(vision = support)
            ModelCapability.STRUCTURED_OUTPUT -> copy(structuredOutput = support)
            ModelCapability.REASONING -> copy(reasoning = support)
        }

    /**
     * The authoritative profile this declaration establishes for one model,
     * layered onto [existing].
     *
     * Only the capabilities this declaration actually states are overridden, so
     * declaring one thing never silently downgrades another. The result is marked
     * [ModelCapabilityProfile.known] with [CapabilityProvenance.HARDCODED]
     * because an explicit declaration is knowledge, not inference: it has the
     * same standing as a built-in definition, which is also what keeps a later
     * discovery or reconnect from erasing it.
     */
    fun applyTo(existing: ModelCapabilityProfile): ModelCapabilityProfile = existing.copy(
        toolCalling = statedOr(toolCalling, existing.toolCalling),
        streaming = statedOr(streaming, existing.streaming),
        vision = statedOr(vision, existing.vision),
        structuredOutput = statedOr(structuredOutput, existing.structuredOutput),
        reasoning = statedOr(reasoning, existing.reasoning),
        known = true,
        provenance = CapabilityProvenance.HARDCODED,
    )

    private fun statedOr(stated: CapabilitySupport, existing: CapabilitySupport): CapabilitySupport =
        if (stated == CapabilitySupport.UNKNOWN) existing else stated

    companion object {
        /** A declaration that states nothing: the identity of this type. */
        val EMPTY: ModelCapabilityDeclaration = ModelCapabilityDeclaration()

        /** A declaration that states exactly one capability. */
        fun of(capability: ModelCapability, support: CapabilitySupport): ModelCapabilityDeclaration =
            EMPTY.with(capability, support)

        /**
         * The declaration for an endpoint the user states serves a tool-enabled
         * agentic role.
         *
         * Such a role needs both tool calling and streamed completions, and the
         * OpenAI-compatible provider already advertises streaming, so a user who
         * states that their model calls tools states both. Nothing else is
         * claimed: vision, structured output and reasoning stay
         * [CapabilitySupport.UNKNOWN] rather than being guessed.
         */
        fun toolEnabledEndpoint(): ModelCapabilityDeclaration = ModelCapabilityDeclaration(
            toolCalling = CapabilitySupport.SUPPORTED,
            streaming = CapabilitySupport.SUPPORTED,
        )

        /** Rebuilds a declaration from the capabilities it [stated]. */
        fun from(stated: Map<ModelCapability, CapabilitySupport>): ModelCapabilityDeclaration =
            stated.entries.fold(EMPTY) { declaration, (capability, support) ->
                declaration.with(capability, support)
            }
    }
}
