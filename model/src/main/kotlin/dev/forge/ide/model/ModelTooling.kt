package dev.forge.ide.model

/**
 * Provider-independent description of a tool the model may call. The Tool
 * System will map its own definitions onto these types when tool calling is
 * implemented; the model layer never depends on the Tool System.
 */
enum class ModelToolParameterType {
    STRING,
    NUMBER,
    BOOLEAN,
    OBJECT,
    ARRAY,
    ANY,
}

data class ModelToolParameter(
    val name: String,
    val type: ModelToolParameterType = ModelToolParameterType.ANY,
    val description: String = "",
    val required: Boolean = false,
)

data class ModelToolSpec(
    val name: String,
    val description: String = "",
    val parameters: List<ModelToolParameter> = emptyList(),
)

/** How the model may choose a tool. */
sealed interface ModelToolChoice {
    data object Auto : ModelToolChoice

    data object None : ModelToolChoice

    data object Required : ModelToolChoice

    data class Specific(val name: String) : ModelToolChoice
}
