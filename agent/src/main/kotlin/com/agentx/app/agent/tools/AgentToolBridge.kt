package com.agentx.app.agent.tools

import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.domain.PermissionLevel
import com.agentx.app.agent.protocol.AgentProtocol
import com.agentx.app.model.ModelToolParameter
import com.agentx.app.model.ModelToolParameterType
import com.agentx.app.model.ModelToolSpec
import com.agentx.app.model.json.JsonObject
import com.agentx.app.model.json.JsonValue
import com.agentx.app.tools.JsonObject as ToolJsonObject
import com.agentx.app.tools.JsonValue as ToolJsonValue
import com.agentx.app.tools.ToolCapability
import com.agentx.app.tools.ToolDefinition
import com.agentx.app.tools.ToolParameterType
import com.agentx.app.tools.ToolPreferences
import com.agentx.app.tools.AllowAllToolPreferences
import com.agentx.app.tools.ToolRegistry

/**
 * Maps Tool System definitions onto Model Gateway tool specs, and Model
 * arguments onto Tool System JSON. Never executes a tool itself.
 *
 * A tool the user turned off in Settings is filtered here as well as at the
 * router: this is the exposure side, so a disabled tool is never named to the
 * model, counted against the context budget or offered as a schema. Both sides
 * read the same [ToolPreferences] instance, so the two can never disagree.
 */
class AgentToolBridge(
    private val registry: ToolRegistry,
    private val preferences: ToolPreferences = AllowAllToolPreferences,
) {

    fun definitionsFor(names: Collection<String>): List<ToolDefinition> {
        val wanted = names.toSet()
        return registry.definitions().filter { it.name in wanted && preferences.isEnabled(it.name) }
    }

    fun filterAllowed(
        names: Collection<String>,
        permission: PermissionLevel,
        extraDenied: Set<String> = emptySet(),
    ): List<String> {
        val allowed = LinkedHashSet<String>()
        for (name in names) {
            if (name in extraDenied) continue
            if (name == AgentProtocol.DELEGATE_TOOL || name == AgentProtocol.FINISH_TOOL) {
                allowed += name
                continue
            }
            // The loop-handled protocol tools have no definition; everything else
            // must be a registered, enabled tool to be offered.
            if (!preferences.isEnabled(name)) continue
            val definition = registry.find(name)?.definition ?: continue
            if (permission.allows(definition.capabilities)) {
                allowed += name
            }
        }
        return allowed.toList()
    }

    fun toModelSpecs(toolNames: Collection<String>): List<ModelToolSpec> {
        val specs = mutableListOf<ModelToolSpec>()
        for (name in toolNames) {
            if (name == AgentProtocol.DELEGATE_TOOL) {
                specs += delegateSpec()
                continue
            }
            if (name == AgentProtocol.FINISH_TOOL) {
                specs += finishSpec()
                continue
            }
            if (!preferences.isEnabled(name)) continue
            val definition = registry.find(name)?.definition ?: continue
            specs += definition.toModelSpec()
        }
        return specs
    }

    fun toToolArguments(arguments: JsonObject): ToolJsonObject {
        val result = LinkedHashMap<String, ToolJsonValue>()
        for ((key, value) in arguments) {
            result[key] = value.toToolJson()
        }
        return result
    }

    fun renderToolOutput(content: ToolJsonObject, displayText: String?): String {
        if (!displayText.isNullOrBlank()) return displayText
        if (content.isEmpty()) return "{}"
        return content.entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            "\"$key\": ${renderToolJson(value)}"
        }
    }

    fun isReadOnly(definition: ToolDefinition): Boolean {
        if (definition.capabilities.contains(ToolCapability.READ_ONLY) &&
            definition.capabilities.none { it == ToolCapability.MUTATING || it == ToolCapability.SHELL }
        ) {
            return true
        }
        return definition.capabilities.isEmpty()
    }

    private fun ToolDefinition.toModelSpec(): ModelToolSpec = ModelToolSpec(
        name = name,
        description = description,
        parameters = inputSchema.parameters.map { parameter ->
            ModelToolParameter(
                name = parameter.name,
                type = parameter.type.toModelType(),
                description = parameter.description,
                required = parameter.required,
            )
        },
    )

    private fun ToolParameterType.toModelType(): ModelToolParameterType = when (this) {
        ToolParameterType.STRING -> ModelToolParameterType.STRING
        ToolParameterType.NUMBER -> ModelToolParameterType.NUMBER
        ToolParameterType.BOOLEAN -> ModelToolParameterType.BOOLEAN
        ToolParameterType.OBJECT -> ModelToolParameterType.OBJECT
        ToolParameterType.ARRAY -> ModelToolParameterType.ARRAY
        ToolParameterType.ANY -> ModelToolParameterType.ANY
    }

    private fun JsonValue.toToolJson(): ToolJsonValue = when (this) {
        is JsonValue.Null -> ToolJsonValue.Null
        is JsonValue.Bool -> ToolJsonValue.Bool(value)
        is JsonValue.Num -> ToolJsonValue.Num(value)
        is JsonValue.Str -> ToolJsonValue.Str(value)
        is JsonValue.Arr -> ToolJsonValue.Arr(items.map { it.toToolJson() })
        is JsonValue.Obj -> ToolJsonValue.Obj(fields.mapValues { it.value.toToolJson() })
    }

    private fun renderToolJson(value: ToolJsonValue): String = when (value) {
        is ToolJsonValue.Null -> "null"
        is ToolJsonValue.Bool -> value.value.toString()
        is ToolJsonValue.Num -> value.value.toString()
        is ToolJsonValue.Str -> "\"${value.value}\""
        is ToolJsonValue.Arr -> value.items.joinToString(prefix = "[", postfix = "]") { renderToolJson(it) }
        is ToolJsonValue.Obj -> value.fields.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
            "\"$k\": ${renderToolJson(v)}"
        }
    }

    private fun delegateSpec(): ModelToolSpec = ModelToolSpec(
        name = AgentProtocol.DELEGATE_TOOL,
        description = "Delegate a focused sub-task to a specialized agent. Sequential only: wait for " +
            "the result before continuing. Handle simple, single-step tasks yourself instead of " +
            "delegating. Pass only the scoped context the specialist needs — never the whole " +
            "repository or conversation. Delegations are bounded (max depth ${com.agentx.app.agent.delegation.DelegationPolicy.MAX_DEPTH}, " +
            "max ${com.agentx.app.agent.delegation.DelegationPolicy.MAX_TOTAL_SPECIALISTS} total, " +
            "max ${com.agentx.app.agent.delegation.DelegationPolicy.MAX_REPEATS_PER_ROLE} per role); " +
            "re-delegating a task a role already completed is rejected.",
        parameters = listOf(
            ModelToolParameter(
                name = AgentProtocol.ARG_ROLE,
                type = ModelToolParameterType.STRING,
                description = "One of ${AgentRole.entries.filter { it != AgentRole.MAIN }.joinToString { it.name }}",
                required = true,
            ),
            ModelToolParameter(
                name = AgentProtocol.ARG_TASK,
                type = ModelToolParameterType.STRING,
                description = "Concrete work the sub-agent should perform.",
                required = true,
            ),
            ModelToolParameter(
                name = AgentProtocol.ARG_OBJECTIVE,
                type = ModelToolParameterType.STRING,
                description = "What a successful result looks like.",
                required = true,
            ),
            ModelToolParameter(
                name = AgentProtocol.ARG_CONTEXT,
                type = ModelToolParameterType.STRING,
                description = "Scoped context the sub-agent needs; keep it small.",
                required = false,
            ),
            ModelToolParameter(
                name = AgentProtocol.ARG_MAX_STEPS,
                type = ModelToolParameterType.NUMBER,
                description = "Optional step budget for the sub-agent.",
                required = false,
            ),
            ModelToolParameter(
                name = AgentProtocol.ARG_PERMISSION,
                type = ModelToolParameterType.STRING,
                description = "Optional permission ceiling for the sub-agent.",
                required = false,
            ),
        ),
    )

    private fun finishSpec(): ModelToolSpec = ModelToolSpec(
        name = AgentProtocol.FINISH_TOOL,
        description = "Complete the current agent turn with a structured result.",
        parameters = listOf(
            ModelToolParameter(
                name = AgentProtocol.ARG_SUMMARY,
                type = ModelToolParameterType.STRING,
                description = "Final summary for the parent agent or user.",
                required = true,
            ),
            ModelToolParameter(
                name = AgentProtocol.ARG_FINDINGS,
                type = ModelToolParameterType.STRING,
                description = "Optional newline-separated findings.",
                required = false,
            ),
            ModelToolParameter(
                name = AgentProtocol.ARG_FILES_INSPECTED,
                type = ModelToolParameterType.STRING,
                description = "Optional newline-separated inspected paths.",
                required = false,
            ),
            ModelToolParameter(
                name = AgentProtocol.ARG_FILES_CHANGED,
                type = ModelToolParameterType.STRING,
                description = "Optional newline-separated changed paths.",
                required = false,
            ),
        ),
    )
}

fun JsonObject.stringOrNull(key: String): String? = this[key]?.let { value ->
    when (value) {
        is JsonValue.Str -> value.value
        is JsonValue.Num -> value.value.toString()
        is JsonValue.Bool -> value.value.toString()
        else -> null
    }
}

fun JsonObject.intOrNull(key: String): Int? = this[key]?.let { value ->
    when (value) {
        is JsonValue.Num -> value.value.toInt()
        is JsonValue.Str -> value.value.toIntOrNull()
        else -> null
    }
}
