package com.agentx.app.tools

import com.agentx.app.core.logging.ForgeLogger
import com.agentx.app.core.logging.ForgeLoggers
import com.agentx.app.core.logging.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs an already-resolved and authorized tool, normalizing every outcome
 * (success, structured error, or unexpected throwable) into a [ToolResult].
 *
 * Execution is coroutine-based, cancellable, and never uses [kotlinx.coroutines.GlobalScope].
 * Blocking work is confined to [Dispatchers.Default] so the Android main thread stays free.
 */
interface ToolExecutor {
    suspend fun execute(
        tool: Tool,
        input: ToolInput,
        context: ToolExecutionContext,
    ): ToolResult
}

class DefaultToolExecutor(
    private val defaultTimeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val logger: ForgeLogger = ForgeLoggers.create(
        level = LogLevel.INFO,
        baseFields = mapOf("layer" to "tools"),
    ),
) : ToolExecutor {

    override suspend fun execute(
        tool: Tool,
        input: ToolInput,
        context: ToolExecutionContext,
    ): ToolResult {
        val name = tool.definition.name
        val timeout = (context.timeoutMillis ?: defaultTimeoutMillis).coerceAtLeast(1L)
        val startedAt = System.currentTimeMillis()
        logger.debug(
            "Executing tool",
            mapOf(
                "tool" to name,
                "sessionId" to context.sessionId,
                "workspaceId" to context.workspaceId,
                "callId" to context.callId,
            ),
        )
        return try {
            val output = withContext(Dispatchers.Default) {
                withTimeout(timeout) {
                    SecretRedactor.redactOutput(tool.execute(input, context))
                }
            }
            val duration = System.currentTimeMillis() - startedAt
            logger.debug("Tool succeeded", mapOf("tool" to name, "durationMillis" to duration))
            ToolResult.Success(
                toolName = name,
                output = output,
                durationMillis = duration,
            )
        } catch (timeoutError: TimeoutCancellationException) {
            logger.warn("Tool timed out", mapOf("tool" to name, "timeoutMillis" to timeout))
            ToolResult.Failure(
                toolName = name,
                error = ToolExecutionError(
                    code = ToolErrorCode.TIMEOUT,
                    message = "Tool '$name' timed out after ${timeout}ms",
                    toolName = name,
                    cause = timeoutError,
                ),
            )
        } catch (cancellation: CancellationException) {
            logger.info("Tool cancelled", mapOf("tool" to name))
            throw cancellation
        } catch (error: ToolExecutionError) {
            logger.warn("Tool failed", mapOf("tool" to name, "code" to error.code.name))
            ToolResult.Failure(toolName = name, error = error)
        } catch (error: Throwable) {
            logger.error("Tool failed unexpectedly", error, mapOf("tool" to name))
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

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS: Long = 30_000L
    }
}
