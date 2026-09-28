package com.agentx.app.tools

/** JSON-schema compatible type of a single tool argument. */
enum class ToolParameterType {
    STRING,
    NUMBER,
    BOOLEAN,
    OBJECT,
    ARRAY,

    /** No type constraint; used for opaque or pass-through values. */
    ANY,
}

/** Declares one argument accepted by a tool. */
data class ToolParameter(
    val name: String,
    val type: ToolParameterType = ToolParameterType.ANY,
    val description: String = "",
    val required: Boolean = false,
    val enumValues: List<String> = emptyList(),
    val defaultValue: JsonValue? = null,
)

/**
 * Structured input schema for a tool. It is intentionally a small subset of
 * JSON Schema: enough to validate requests now and to be serialized for model
 * tool/function calling later.
 */
data class ToolInputSchema(
    val parameters: List<ToolParameter> = emptyList(),
) {
    fun parameter(name: String): ToolParameter? = parameters.firstOrNull { it.name == name }

    /** Returns every validation problem found; an empty list means valid. */
    fun validate(arguments: JsonObject): List<String> {
        val errors = mutableListOf<String>()
        for (parameter in parameters) {
            val value = arguments[parameter.name]
            val missing = value == null || value is JsonValue.Null
            if (missing) {
                if (parameter.required && parameter.defaultValue == null) {
                    errors += "Missing required argument '${parameter.name}'"
                }
                continue
            }
            if (!parameter.accepts(value)) {
                errors += "Argument '${parameter.name}' must be a ${parameter.type.name.lowercase()}"
                continue
            }
            if (parameter.enumValues.isNotEmpty()) {
                val text = value.stringOrNull()
                if (text != null && text !in parameter.enumValues) {
                    errors += "Argument '${parameter.name}' must be one of ${parameter.enumValues}"
                }
            }
        }
        return errors
    }

    private fun ToolParameter.accepts(value: JsonValue): Boolean = when (type) {
        ToolParameterType.ANY -> true
        ToolParameterType.STRING -> value is JsonValue.Str
        ToolParameterType.NUMBER -> value is JsonValue.Num
        ToolParameterType.BOOLEAN -> value is JsonValue.Bool
        ToolParameterType.OBJECT -> value is JsonValue.Obj
        ToolParameterType.ARRAY -> value is JsonValue.Arr
    }
}

/** Describes the value a tool returns. */
data class ToolOutputSpec(
    val description: String = "",
    val schema: JsonObject = emptyMap(),
)

/**
 * Optional capability tags. They carry no behavior on their own; they exist so
 * a permission policy can later treat dangerous capabilities (shell, network,
 * credentials, ...) specially without changing the [Tool] interface.
 */
enum class ToolCapability {
    READ_ONLY,
    MUTATING,
    FILESYSTEM,
    SHELL,
    NETWORK,
    GIT,
    CREDENTIALS,
    USER_INTERACTION,
}

/**
 * Declarative description of a tool: the single source of truth shared by the
 * registry, the router, the permission layer, and the future model layer. A
 * tool implementation only references this definition and never leaks it into
 * the agent core.
 */
data class ToolDefinition(
    val name: String,
    val title: String = name,
    val description: String,
    val inputSchema: ToolInputSchema = ToolInputSchema(),
    val output: ToolOutputSpec = ToolOutputSpec(),
    val permission: ToolPermissionDecision = ToolPermissionDecision.ALLOW,
    val capabilities: Set<ToolCapability> = emptySet(),
    val metadata: Map<String, String> = emptyMap(),
    val category: ToolCategory = ToolCategory.OTHER,
    val requiredPermissions: Set<ToolPermissionLevel> = emptySet(),
    /**
     * External connection a future tool may require. The Connection Manager
     * verifies type, enabled state, capability and credential presence; the
     * tool never receives the secret.
     */
    val connectionRequirement: ToolConnectionRequirement? = null,
) {
    init {
        require(name.isNotBlank()) { "Tool name must not be blank" }
        require(name.matches(NAME_PATTERN)) {
            "Tool name '$name' must contain only letters, digits, '.', '_', or '-'"
        }
        require(description.isNotBlank()) { "Tool '$name' must have a description" }
    }

    val id: ToolId get() = ToolId(name)

    companion object {
        val NAME_PATTERN = Regex("[A-Za-z0-9_.-]+")
    }
}

/** Converts a [ToolDefinition] into a JSON object for the future model layer. */
fun ToolDefinition.toJsonSchema(): JsonObject {
    val properties: JsonObject = inputSchema.parameters.associate { parameter ->
        parameter.name to JsonValue.Obj(parameter.toJsonSchema())
    }
    val required = inputSchema.parameters.filter { it.required }.map { Json.of(it.name) }
    return mapOf(
        "name" to Json.of(name),
        "description" to Json.of(description),
        "type" to Json.of("object"),
        "properties" to Json.obj(properties),
        "required" to Json.array(required),
    )
}

private fun ToolParameter.toJsonSchema(): JsonObject {
    val result = LinkedHashMap<String, JsonValue>()
    if (type != ToolParameterType.ANY) {
        result["type"] = Json.of(type.jsonName())
    }
    if (description.isNotBlank()) {
        result["description"] = Json.of(description)
    }
    if (enumValues.isNotEmpty()) {
        result["enum"] = Json.array(enumValues.map { Json.of(it) })
    }
    defaultValue?.let { result["default"] = it }
    return result
}

private fun ToolParameterType.jsonName(): String = when (this) {
    ToolParameterType.STRING -> "string"
    ToolParameterType.NUMBER -> "number"
    ToolParameterType.BOOLEAN -> "boolean"
    ToolParameterType.OBJECT -> "object"
    ToolParameterType.ARRAY -> "array"
    ToolParameterType.ANY -> "object"
}
