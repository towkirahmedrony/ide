package com.agentx.app.model

/**
 * Feature flags reported by a provider for a specific model. The application
 * asks the gateway for these before depending on a feature, so it never assumes
 * that any given provider or model supports streaming, tool calling, vision, or
 * structured output.
 */
data class ModelCapabilities(
    val streaming: Boolean = false,
    val toolCalling: Boolean = false,
    val vision: Boolean = false,
    val structuredOutput: Boolean = false,
    val systemMessages: Boolean = true,
    val contextWindowTokens: Int? = null,
    val maxOutputTokens: Int? = null,
    /** True when the model is known to expose a reasoning/thinking channel. */
    val reasoning: Boolean = false,
    /** True when the model runs on-device or on a local OpenAI-compatible runtime. */
    val local: Boolean = false,
    /** False when the definition is retained but must not be selected. */
    val enabled: Boolean = true,

    /** Escape hatch for provider- or model-specific flags. */
    val extensions: Map<String, Boolean> = emptyMap(),
) {
    /** Returns a named extended capability, defaulting to false. */
    fun supports(feature: String): Boolean = extensions[feature] ?: false
}
