package com.agentx.app.tools

import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs an already-resolved and authorized tool, normalizing every outcome
 * (success, structured error, or unexpected throwable) into a [ToolResult].
 */
interface ToolExecutor {
    suspend fun execute(
        tool: Tool,
        input: ToolInput,
        context: ToolExecutionContext,
    ): ToolResult
}

class DefaultToolExecutor : ToolExecutor {

    override suspend fun execute(
        tool: Tool,
        input: ToolInput,
        context: ToolExecutionContext,
    ): ToolResult {
        val name = tool.definition.name
        val startedAt = System.currentTimeMillis()
        return try {
            val output = tool.execute(input, context)
            ToolResult.Success(
                toolName = name,
                output = output,
                durationMillis = System.currentTimeMillis() - startedAt,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: ToolExecutionError) {
            ToolResult.Failure(toolName = name, error = error)
        } catch (error: Throwable) {
            ToolResult.Failure(
                toolName = name,
                error = ToolExecutionError(
                    code = ToolErrorCode.EXECUTION_FAILED,
                    message = error.message ?: "Tool '$name' failed",
                    toolName = name,
                    cause = error,
                ),
            )
        }
    }
}
