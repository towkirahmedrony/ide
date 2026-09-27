package dev.forge.ide.model

/**
 * A structured, provider-agnostic request. Generation settings come from the
 * [config], with an optional per-request override via [generation].
 */
data class ModelRequest(
    val config: ModelConfig,
    val messages: List<ModelMessage>,
    /** Tool specs the model may call. Empty until tool calling is enabled. */
    val tools: List<ModelToolSpec> = emptyList(),
    val toolChoice: ModelToolChoice? = null,
    /** When set, overrides [ModelConfig.generation] for this request only. */
    val generation: ModelGenerationSettings? = null,
) {
    val model: String get() = config.model

    val stream: Boolean get() = config.stream

    val effectiveGeneration: ModelGenerationSettings get() = generation ?: config.generation

    companion object {
        /** Convenience factory mirroring the common "one config, many prompts" case. */
        fun of(config: ModelConfig, messages: List<ModelMessage>): ModelRequest =
            ModelRequest(config = config, messages = messages)
    }
}
