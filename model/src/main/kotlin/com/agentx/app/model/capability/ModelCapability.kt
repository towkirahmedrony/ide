package com.agentx.app.model.capability

import com.agentx.app.model.ModelCapabilities

/**
 * Named model features the runtime can require or advertise.
 *
 * Capability checks for agent roles and tool-enabled requests go through
 * [ModelCapabilityRegistry]; callers must not infer these from a provider id
 * or a model name.
 */
enum class ModelCapability(val id: String) {
    TOOL_CALLING("toolCalling"),
    STREAMING("streaming"),
    VISION("vision"),
    STRUCTURED_OUTPUT("structuredOutput"),
    REASONING("reasoning"),
    ;

    companion object {
        fun fromId(raw: String): ModelCapability? =
            entries.firstOrNull { it.id.equals(raw, ignoreCase = true) || it.name.equals(raw, ignoreCase = true) }
    }
}

/**
 * Whether a capability is known to be present.
 *
 * [UNKNOWN] is not the same as [UNSUPPORTED]: it means AgentX has no
 * authoritative metadata, so a tool-enabled role must not assume support.
 */
enum class CapabilitySupport {
    SUPPORTED,
    UNSUPPORTED,
    UNKNOWN,
    ;

    val isSupported: Boolean get() = this == SUPPORTED

    companion object {
        fun of(supported: Boolean): CapabilitySupport = if (supported) SUPPORTED else UNSUPPORTED

        fun knownOrUnknown(supported: Boolean?): CapabilitySupport = when (supported) {
            true -> SUPPORTED
            false -> UNSUPPORTED
            null -> UNKNOWN
        }
    }
}

/**
 * Where a [ModelCapabilityProfile] came from.
 *
 * Provenance never implies support: a [DISCOVERED] or [CONNECTED] model stays
 * [CapabilitySupport.UNKNOWN] until a hardcoded overlay or an explicit
 * config override says otherwise.
 */
enum class CapabilityProvenance {
    /** Hardcoded overlay from [KnownModelCapabilities]. */
    HARDCODED,
    /**
     * Attested by the provider's own API metadata — for example Gemini's
     * `supportedGenerationMethods`, which reports whether the model offers stream
     * generation. This is narrow, provider-reported evidence for one capability,
     * never an inference from a provider family or a model name: an
     * OpenAI-compatible protocol and a bare model listing still leave every
     * capability [CapabilitySupport.UNKNOWN].
     */
    PROVIDER_REPORTED,
    /** Listed by a provider catalog. Not capability proof. */
    DISCOVERED,
    /** Selected during connect. Identity only, not capability proof. */
    CONNECTED,
    ;

    companion object {
        fun merge(existing: CapabilityProvenance, incoming: CapabilityProvenance): CapabilityProvenance {
            val rank = listOf(HARDCODED, PROVIDER_REPORTED, DISCOVERED, CONNECTED)
            val existingRank = rank.indexOf(existing).takeIf { it >= 0 } ?: rank.lastIndex
            val incomingRank = rank.indexOf(incoming).takeIf { it >= 0 } ?: rank.lastIndex
            return if (incomingRank < existingRank) incoming else existing
        }
    }
}

/**
 * Authoritative capability record for one provider/model pair.
 *
 * [known] is true only for hardcoded definitions. Dynamically discovered
 * models are represented with [known] = false and conservative support
 * flags so they stay usable for plain chat without becoming tool-capable.
 */
data class ModelCapabilityProfile(
    val providerId: String,
    val modelId: String,
    val displayName: String,
    val toolCalling: CapabilitySupport = CapabilitySupport.UNKNOWN,
    val streaming: CapabilitySupport = CapabilitySupport.UNKNOWN,
    val vision: CapabilitySupport = CapabilitySupport.UNKNOWN,
    val structuredOutput: CapabilitySupport = CapabilitySupport.UNKNOWN,
    val reasoning: CapabilitySupport = CapabilitySupport.UNKNOWN,
    val maxContextTokens: Int? = null,
    val maxOutputTokens: Int? = null,
    val local: Boolean = false,
    val enabled: Boolean = true,
    val known: Boolean = true,
    val provenance: CapabilityProvenance = if (known) CapabilityProvenance.HARDCODED else CapabilityProvenance.DISCOVERED,
) {
    init {
        require(providerId.isNotBlank()) { "providerId must not be blank" }
        require(modelId.isNotBlank()) { "modelId must not be blank" }
    }

    fun support(capability: ModelCapability): CapabilitySupport = when (capability) {
        ModelCapability.TOOL_CALLING -> toolCalling
        ModelCapability.STREAMING -> streaming
        ModelCapability.VISION -> vision
        ModelCapability.STRUCTURED_OUTPUT -> structuredOutput
        ModelCapability.REASONING -> reasoning
    }

    fun supports(capability: ModelCapability): Boolean = support(capability).isSupported

    fun missing(required: Collection<ModelCapability>): List<ModelCapability> =
        required.filterNot { supports(it) }

    /**
     * Runtime flags consumed by the gateway. [UNKNOWN] and [UNSUPPORTED]
     * both become `false` so an unknown model is never treated as capable.
     */
    fun toModelCapabilities(): ModelCapabilities = ModelCapabilities(
        streaming = streaming.isSupported,
        toolCalling = toolCalling.isSupported,
        vision = vision.isSupported,
        structuredOutput = structuredOutput.isSupported,
        systemMessages = true,
        contextWindowTokens = maxContextTokens,
        maxOutputTokens = maxOutputTokens,
        reasoning = reasoning.isSupported,
        local = local,
        enabled = enabled,
        extensions = mapOf(
            EXTENSION_LOCAL to local,
            EXTENSION_ENABLED to enabled,
            EXTENSION_KNOWN to known,
        ),
    )

    companion object {
        const val EXTENSION_LOCAL: String = "local"
        const val EXTENSION_ENABLED: String = "enabled"
        const val EXTENSION_KNOWN: String = "known"

        fun unknown(providerId: String, modelId: String): ModelCapabilityProfile = ModelCapabilityProfile(
            providerId = providerId,
            modelId = modelId,
            displayName = modelId,
            toolCalling = CapabilitySupport.UNKNOWN,
            streaming = CapabilitySupport.UNKNOWN,
            vision = CapabilitySupport.UNKNOWN,
            structuredOutput = CapabilitySupport.UNKNOWN,
            reasoning = CapabilitySupport.UNKNOWN,
            local = false,
            enabled = true,
            known = false,
            provenance = CapabilityProvenance.DISCOVERED,
        )

        fun discovered(
            providerId: String,
            modelId: String,
            displayName: String? = null,
            maxContextTokens: Int? = null,
            maxOutputTokens: Int? = null,
            local: Boolean = false,
            /** Provider-attested support, or [CapabilitySupport.UNKNOWN]. */
            streaming: CapabilitySupport = CapabilitySupport.UNKNOWN,
            provenance: CapabilityProvenance = CapabilityProvenance.DISCOVERED,
        ): ModelCapabilityProfile = ModelCapabilityProfile(
            providerId = providerId,
            modelId = modelId,
            displayName = displayName?.takeIf { it.isNotBlank() } ?: modelId,
            toolCalling = CapabilitySupport.UNKNOWN,
            streaming = streaming,
            vision = CapabilitySupport.UNKNOWN,
            structuredOutput = CapabilitySupport.UNKNOWN,
            reasoning = CapabilitySupport.UNKNOWN,
            maxContextTokens = maxContextTokens,
            maxOutputTokens = maxOutputTokens,
            local = local,
            enabled = true,
            known = false,
            provenance = provenance,
        )

        fun connected(
            providerId: String,
            modelId: String,
            displayName: String? = null,
            local: Boolean = false,
        ): ModelCapabilityProfile = ModelCapabilityProfile(
            providerId = providerId,
            modelId = modelId,
            displayName = displayName?.takeIf { it.isNotBlank() } ?: modelId,
            toolCalling = CapabilitySupport.UNKNOWN,
            streaming = CapabilitySupport.UNKNOWN,
            vision = CapabilitySupport.UNKNOWN,
            structuredOutput = CapabilitySupport.UNKNOWN,
            reasoning = CapabilitySupport.UNKNOWN,
            local = local,
            enabled = true,
            known = false,
            provenance = CapabilityProvenance.CONNECTED,
        )
    }

    /**
     * Idempotent merge for [providerId] + [modelId].
     *
     * Hardcoded overlays keep their capability flags. Discovery and connect
     * never upgrade [CapabilitySupport.UNKNOWN] to [SUPPORTED], never flip
     * [enabled], and never invent tool/stream/vision support from a name.
     */
    fun mergeFrom(incoming: ModelCapabilityProfile): ModelCapabilityProfile {
        val overlay = incoming.known || incoming.provenance == CapabilityProvenance.HARDCODED
        return copy(
            displayName = mergedDisplayName(incoming, overlay),
            toolCalling = mergedSupport(toolCalling, incoming.toolCalling, overlay, incoming.provenance),
            streaming = mergedSupport(streaming, incoming.streaming, overlay, incoming.provenance),
            vision = mergedSupport(vision, incoming.vision, overlay, incoming.provenance),
            structuredOutput = mergedSupport(structuredOutput, incoming.structuredOutput, overlay, incoming.provenance),
            reasoning = mergedSupport(reasoning, incoming.reasoning, overlay, incoming.provenance),
            maxContextTokens = incoming.maxContextTokens ?: maxContextTokens,
            maxOutputTokens = incoming.maxOutputTokens ?: maxOutputTokens,
            local = local || incoming.local,
            enabled = if (overlay) incoming.enabled else enabled,
            known = known || incoming.known,
            provenance = CapabilityProvenance.merge(provenance, incoming.provenance),
        )
    }

    private fun mergedDisplayName(incoming: ModelCapabilityProfile, overlay: Boolean): String {
        if (overlay && incoming.displayName.isNotBlank()) return incoming.displayName
        if (!known && incoming.displayName.isNotBlank()) return incoming.displayName
        return displayName
    }

    private fun mergedSupport(
        existing: CapabilitySupport,
        incoming: CapabilitySupport,
        overlay: Boolean,
        incomingProvenance: CapabilityProvenance,
    ): CapabilitySupport {
        if (overlay) return incoming
        // An authoritative definition always wins over dynamic evidence.
        if (known) return existing
        // Narrow provider-attested evidence may resolve a capability that was
        // previously unknown, but it never overwrites a stated value and never
        // fabricates support for a capability the provider did not report.
        if (existing == CapabilitySupport.UNKNOWN &&
            incoming != CapabilitySupport.UNKNOWN &&
            incomingProvenance == CapabilityProvenance.PROVIDER_REPORTED
        ) {
            return incoming
        }
        return existing
    }

    /** Drops inferred capability flags so a dynamic model cannot look tool-capable. */
    fun withoutInferredCapabilities(): ModelCapabilityProfile {
        if (known || provenance == CapabilityProvenance.HARDCODED) return this
        // Provider-reported evidence is trusted for exactly the capabilities the
        // provider stated (a provider model list reports stream generation, and
        // nothing about tool calling). Everything unstated stays UNKNOWN.
        if (provenance == CapabilityProvenance.PROVIDER_REPORTED) return copy(known = false)
        // A dynamic profile that asserted support it cannot prove is not trusted for
        // its metadata either. Its name is cleared as well as its flags, so it cannot
        // overwrite a name an earlier, honest discovery already settled — otherwise
        // the unprovable claim would still win the entry it was rejected for.
        val claimedUnprovenSupport = toolCalling == CapabilitySupport.SUPPORTED ||
            streaming == CapabilitySupport.SUPPORTED ||
            vision == CapabilitySupport.SUPPORTED ||
            structuredOutput == CapabilitySupport.SUPPORTED ||
            reasoning == CapabilitySupport.SUPPORTED
        return copy(
            displayName = if (claimedUnprovenSupport) "" else displayName,
            toolCalling = CapabilitySupport.UNKNOWN,
            streaming = CapabilitySupport.UNKNOWN,
            vision = CapabilitySupport.UNKNOWN,
            structuredOutput = CapabilitySupport.UNKNOWN,
            reasoning = CapabilitySupport.UNKNOWN,
            known = false,
        )
    }
}

/** Converts an explicit [ModelCapabilities] override into a profile. */
fun ModelCapabilities.toCapabilityProfile(
    providerId: String,
    modelId: String,
    displayName: String = modelId,
): ModelCapabilityProfile = ModelCapabilityProfile(
    providerId = providerId,
    modelId = modelId,
    displayName = displayName,
    toolCalling = CapabilitySupport.of(toolCalling),
    streaming = CapabilitySupport.of(streaming),
    vision = CapabilitySupport.of(vision),
    structuredOutput = CapabilitySupport.of(structuredOutput),
    reasoning = CapabilitySupport.of(reasoning),
    maxContextTokens = contextWindowTokens,
    maxOutputTokens = this.maxOutputTokens,
    local = local,
    enabled = enabled,
    known = true,
)
