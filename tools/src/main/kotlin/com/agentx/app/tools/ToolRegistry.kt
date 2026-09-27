package com.agentx.app.tools

/**
 * Stores the tools available to the platform. The registry is the only place
 * the agent core and the model layer read tool definitions from, so they never
 * depend on concrete tool implementations.
 */
interface ToolRegistry {
    /** Registers [tool]; throws [ToolExecutionError] if its name is taken. */
    fun register(tool: Tool)

    /** Removes the tool named [name]; returns whether one was removed. */
    fun unregister(name: String): Boolean

    /** Resolves a tool by its unique name, or null when unknown. */
    fun find(name: String): Tool?

    fun find(id: ToolId): Tool? = find(id.value)

    fun contains(name: String): Boolean

    fun tools(): List<Tool>

    fun names(): List<String>

    /** Snapshot of all definitions, ready to expose to the model layer. */
    fun definitions(): List<ToolDefinition>
}

/** Default in-memory registry. Registration order is preserved. */
class DefaultToolRegistry : ToolRegistry {

    private val tools = LinkedHashMap<String, Tool>()

    @Synchronized
    override fun register(tool: Tool) {
        validate(tool.definition)
        val name = tool.definition.name
        if (tools.containsKey(name)) {
            throw ToolExecutionError(
                code = ToolErrorCode.DUPLICATE_TOOL,
                message = "A tool named '$name' is already registered",
                toolName = name,
            )
        }
        tools[name] = tool
    }

    private fun validate(definition: ToolDefinition) {
        val problems = mutableListOf<String>()
        if (definition.name.isBlank()) problems += "name is blank"
        if (definition.description.isBlank()) problems += "description is blank"
        val names = definition.inputSchema.parameters.map { it.name }
        if (names.size != names.toSet().size) problems += "duplicate parameter names"
        if (problems.isNotEmpty()) {
            throw ToolExecutionError(
                code = ToolErrorCode.INVALID_DEFINITION,
                message = "Invalid tool definition '${definition.name}': ${problems.joinToString(", ")}",
                toolName = definition.name.takeIf { it.isNotBlank() },
            )
        }
    }

    @Synchronized
    override fun unregister(name: String): Boolean = tools.remove(name) != null

    @Synchronized
    override fun find(name: String): Tool? = tools[name]

    @Synchronized
    override fun contains(name: String): Boolean = tools.containsKey(name)

    @Synchronized
    override fun tools(): List<Tool> = tools.values.toList()

    @Synchronized
    override fun names(): List<String> = tools.keys.toList()

    @Synchronized
    override fun definitions(): List<ToolDefinition> = tools.values.map { it.definition }
}
