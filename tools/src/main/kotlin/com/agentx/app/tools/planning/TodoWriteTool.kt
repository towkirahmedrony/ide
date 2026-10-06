package com.agentx.app.tools.planning

import com.agentx.app.tools.Json
import com.agentx.app.tools.Tool
import com.agentx.app.tools.ToolCapability
import com.agentx.app.tools.ToolCategory
import com.agentx.app.tools.ToolDefinition
import com.agentx.app.tools.ToolErrorCode
import com.agentx.app.tools.ToolExecutionContext
import com.agentx.app.tools.ToolExecutionError
import com.agentx.app.tools.ToolInput
import com.agentx.app.tools.ToolInputSchema
import com.agentx.app.tools.ToolOutput
import com.agentx.app.tools.ToolOutputSpec
import com.agentx.app.tools.ToolParameter
import com.agentx.app.tools.ToolParameterType
import com.agentx.app.tools.ToolPermissionDecision
import com.agentx.app.tools.ToolPermissionLevel

enum class TodoStatus { PENDING, IN_PROGRESS, COMPLETED }

data class TodoItem(val title: String, val status: TodoStatus)

/** Parses and renders the one-step-per-line checklist the model sends. */
object TodoList {
    const val MAX_ITEMS = 30
    const val MAX_TITLE = 160

    /** `[ ] title` pending, `[~] title` in progress, `[x] title` done. Bullets/numbers are tolerated. */
    fun parse(text: String): List<TodoItem> =
        text.lineSequence().mapNotNull { parseLine(it) }.take(MAX_ITEMS).toList()

    fun render(items: List<TodoItem>): String =
        items.joinToString("\n") { item -> "${marker(item.status)} ${item.title}" }

    private fun marker(status: TodoStatus): String = when (status) {
        TodoStatus.PENDING -> "[ ]"
        TodoStatus.IN_PROGRESS -> "[~]"
        TodoStatus.COMPLETED -> "[x]"
    }

    private fun parseLine(raw: String): TodoItem? {
        var line = raw.trim().trimStart('-', '*', '•').trim()
        line = line.replace(Regex("^\\d+[.)]\\s+"), "")
        var status = TodoStatus.PENDING
        if (line.startsWith("[")) {
            val close = line.indexOf(']')
            if (close in 1..3) {
                status = when (line.substring(1, close).trim().lowercase()) {
                    "x", "✓", "✔" -> TodoStatus.COMPLETED
                    "~", ">", "/", "*" -> TodoStatus.IN_PROGRESS
                    else -> TodoStatus.PENDING
                }
                line = line.substring(close + 1).trim()
            }
        }
        return if (line.isEmpty()) null else TodoItem(line.take(MAX_TITLE), status)
    }
}

/**
 * The visible to-do checklist for the current task. It has no side effects on the
 * workspace; the app shows the list it receives, and the result echoes the
 * normalized list back so the model keeps its own plan consistent.
 */
class TodoWriteTool : Tool {

    override val definition = ToolDefinition(
        name = NAME,
        title = "Update to-do list",
        description = "Create or update the visible to-do checklist for the current task. " +
            "Send the COMPLETE list every time, one step per line: \"[ ] title\" is pending, " +
            "\"[~] title\" is in progress, \"[x] title\" is done. Keep at most one step in progress. " +
            "Use it for tasks with three or more steps; skip it for simple requests and conversation.",
        inputSchema = ToolInputSchema(
            parameters = listOf(
                ToolParameter(
                    name = "todos",
                    type = ToolParameterType.STRING,
                    description = "The full checklist, one step per line, each starting with [ ], [~] or [x].",
                    required = true,
                ),
            ),
        ),
        output = ToolOutputSpec(description = "Confirmation with the normalized checklist."),
        permission = ToolPermissionDecision.ALLOW,
        capabilities = setOf(ToolCapability.READ_ONLY),
        category = ToolCategory.OTHER,
        requiredPermissions = setOf(ToolPermissionLevel.READ_ONLY),
        metadata = mapOf("sideEffects" to "none"),
    )

    override suspend fun execute(input: ToolInput, context: ToolExecutionContext): ToolOutput {
        val items = TodoList.parse(input.string("todos").orEmpty())
        if (items.isEmpty()) {
            throw ToolExecutionError(
                code = ToolErrorCode.INVALID_ARGUMENTS,
                message = "todo_write needs 'todos' as text with one step per line, " +
                    "for example \"[~] Read the config\" and \"[ ] Fix the bug\".",
                toolName = NAME,
            )
        }
        val done = items.count { it.status == TodoStatus.COMPLETED }
        val active = items.count { it.status == TodoStatus.IN_PROGRESS }
        val warning = if (active > 1) "\nNote: more than one step is marked in progress; keep only one." else ""
        return ToolOutput(
            content = mapOf(
                "total" to Json.of(items.size),
                "completed" to Json.of(done),
                "inProgress" to Json.of(active),
            ),
            displayText = "To-do list updated ($done of ${items.size} done):\n" +
                TodoList.render(items) + warning,
        )
    }

    companion object {
        const val NAME = "todo_write"
    }
}
