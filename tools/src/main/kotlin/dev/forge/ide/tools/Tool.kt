package dev.forge.ide.tools

/** Structured, schema-validated arguments for a single tool invocation. */
data class ToolInput(
    val arguments: JsonObject = emptyMap(),
) {
    fun has(name: String): Boolean = arguments.containsKey(name)

    fun string(name: String): String? = arguments.stringOrNull(name)

    fun number(name: String): Double? = arguments.numberOrNull(name)

    fun boolean(name: String): Boolean? = arguments.booleanOrNull(name)

    fun objectValue(name: String): JsonObject? = arguments[name]?.objectOrNull()

    fun arrayValue(name: String): List<JsonValue>? = arguments[name]?.arrayOrNull()
}

/** Structured data returned by a tool on success. */
data class ToolOutput(
    val content: JsonObject = emptyMap(),

    /** Optional pre-rendered text for display; never used for control flow. */
    val displayText: String? = null,
)

/**
 * A capability the agent can invoke.
 *
 * Implementations must stay independent of the UI, the model provider, and the
 * router: a tool declares a [definition] and implements a single [execute]
 * method. The router is solely responsible for resolving, validating, and
 * authorizing calls, so a new tool never requires changes to the agent core.
 *
 * A tool may `throw ToolExecutionError` to signal a structured failure; the
 * executor normalizes that (or any other throwable) into a `ToolResult.Failure`.
 */
interface Tool {
    val definition: ToolDefinition

    suspend fun execute(input: ToolInput, context: ToolExecutionContext): ToolOutput
}
