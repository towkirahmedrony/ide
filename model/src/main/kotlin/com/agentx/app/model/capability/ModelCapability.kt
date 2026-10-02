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
