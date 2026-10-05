package com.agentx.app.tools.execution

import com.agentx.app.tools.Json
import com.agentx.app.tools.JsonValue
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
import com.agentx.app.tools.WorkspaceHostPathResolver
import com.agentx.app.workspace.ProcessExecutor
import com.agentx.app.workspace.ProcessOutput
import com.agentx.app.workspace.ProcessRequest
import com.agentx.app.workspace.ProcessResult
import com.agentx.app.workspace.ProcessState

/**
 * Runs one command in the open workspace through the platform's command
 * execution backend.
 *
 * The tool is a thin adapter: it does not spawn anything itself. It hands a
 * [ProcessRequest] to the [ProcessExecutor] the application registered (the
 * one-shot execution backend the human terminal and build/test runners also go
 * through) and normalizes the [ProcessResult] into structured output. Swapping
 * the runtime — a JVM process, the embedded Ubuntu runtime, a future remote
 * sandbox — therefore needs no change here.
 *
 * Authorization is unchanged and happens before this class runs: the role's
 * tool policy must grant `run_command`, the run must hold
 * [ToolPermissionLevel.COMMAND_EXECUTION], and the declared ASK permission
 * pauses the call for the user's decision. The tool never widens any of that.
 */
class RunCommandTool(
    private val executor: ProcessExecutor,
    private val hostPaths: WorkspaceHostPathResolver,
) : Tool {

    override val definition = ToolDefinition(
        name = NAME,
        title = "Run command",
        description = "Runs a shell command in the open workspace and returns its stdout, stderr and exit code. " +
            "Commands run with the workspace as the working directory; stdout and stderr are captured, not streamed.",
        inputSchema = ToolInputSchema(
            parameters = listOf(
                ToolParameter(
                    name = ARG_COMMAND,
                    type = ToolParameterType.STRING,
                    description = "The command line to run, interpreted by the workspace shell (sh -c).",
                    required = true,
                ),
                ToolParameter(
                    name = ARG_WORKING_DIRECTORY,
                    type = ToolParameterType.STRING,
                    description = "Optional workspace-relative working directory; defaults to the workspace root.",
                    required = false,
                ),
                ToolParameter(
                    name = ARG_TIMEOUT_MILLIS,
                    type = ToolParameterType.NUMBER,
                    description = "Optional wall-clock timeout in milliseconds.",
                    required = false,
                ),
            ),
        ),
        output = ToolOutputSpec(
            description = "Command, working directory, exit code, stdout and stderr, and whether it timed out.",
        ),
        // A shell command can change anything, so the router pauses for the
        // user's approval (WAITING_FOR_PERMISSION) instead of running it.
        permission = ToolPermissionDecision.ASK,
        capabilities = setOf(ToolCapability.SHELL, ToolCapability.MUTATING),
        category = ToolCategory.COMMAND,
        requiredPermissions = setOf(ToolPermissionLevel.COMMAND_EXECUTION),
        metadata = mapOf("sideEffects" to "process-execution"),
    )

    override suspend fun execute(input: ToolInput, context: ToolExecutionContext): ToolOutput {
        val command = input.string(ARG_COMMAND)?.trim().orEmpty()
        if (command.isEmpty()) {
            throw ToolExecutionError(
                code = ToolErrorCode.INVALID_ARGUMENTS,
                message = "'$ARG_COMMAND' must not be blank",
                toolName = NAME,
            )
        }
        val workingDirectory = resolveWorkingDirectory(input, context)
        val timeoutMillis = input.number(ARG_TIMEOUT_MILLIS)?.toLong()?.takeIf { it > 0 }
            ?: context.timeoutMillis
        val result = executor.execute(
            ProcessRequest(
                command = SHELL,
                arguments = listOf("-c", command),
                workingDirectory = workingDirectory,
                timeoutMillis = timeoutMillis,
            ),
        )
        return toOutput(command, workingDirectory, timeoutMillis, result)
    }

    private fun resolveWorkingDirectory(input: ToolInput, context: ToolExecutionContext): String {
        val requested = input.string(ARG_WORKING_DIRECTORY)?.takeIf { it.isNotBlank() }
        val hostPath = hostPaths.resolve(context)
        if (hostPath == null) {
            throw ToolExecutionError(
                code = ToolErrorCode.WORKSPACE_UNAVAILABLE,
                message = "No workspace directory is available to run '$NAME' in",
                toolName = NAME,
            )
        }
        // A workspace-relative subdirectory is joined onto the resolved host root
        // after rejecting traversal, so a command cannot change directory outside
        // the workspace it was scoped to.
        if (requested == null) return hostPath
        val normalized = requested.replace('\\', '/').trim('/')
        val safe = normalized.split('/').none { it == ".." || it.isEmpty() }
        if (!safe) {
            throw ToolExecutionError(
                code = ToolErrorCode.INVALID_ARGUMENTS,
                message = "Working directory must stay inside the workspace",
                toolName = NAME,
                details = mapOf(ARG_WORKING_DIRECTORY to Json.of(requested)),
            )
        }
        return "$hostPath/$normalized"
    }

    private fun toOutput(
        command: String,
        workingDirectory: String,
        timeoutMillis: Long?,
        result: ProcessResult,
    ): ToolOutput {
        when (result.state) {
            ProcessState.CANCELLED -> throw cancelledOrTimedOut(command, result)
            ProcessState.FAILED -> {
                val error = result.error
                val code = when {
                    error == null -> ToolErrorCode.EXECUTION_FAILED
                    error.code.name == "PROCESS_EXECUTION_UNAVAILABLE" -> ToolErrorCode.TOOL_UNAVAILABLE
                    else -> ToolErrorCode.EXECUTION_FAILED
                }
                throw ToolExecutionError(
                    code = code,
                    message = error?.message ?: "Command '$command' could not be started",
                    toolName = NAME,
                )
            }
            ProcessState.PENDING, ProcessState.RUNNING, ProcessState.COMPLETED -> Unit
        }
        val exitCode = result.exitCode
        val output = result.output
        val combined = buildOutput(command, workingDirectory, exitCode, output, timeoutMillis)
        return ToolOutput(
            content = mapOf(
                "command" to Json.of(command),
                "workingDirectory" to Json.of(workingDirectory),
                "state" to Json.of(result.state.name),
                "exitCode" to (exitCode?.let { Json.of(it) } ?: JsonValue.Null),
                "success" to Json.of(exitCode == 0),
                "timedOut" to Json.of(false),
                "stdout" to Json.of(result.output.stdout),
                "stderr" to Json.of(result.output.stderr),
            ),
            displayText = combined,
        )
    }

    private fun cancelledOrTimedOut(command: String, result: ProcessResult): Nothing {
        val message = result.error?.message.orEmpty()
        val timedOut = message.contains("timed out", ignoreCase = true)
        throw ToolExecutionError(
            code = if (timedOut) ToolErrorCode.TIMEOUT else ToolErrorCode.CANCELLED,
            message = if (timedOut) {
                "Command '$command' timed out"
            } else {
                "Command '$command' was cancelled"
            },
            toolName = NAME,
            details = mapOf(
                "stdout" to Json.of(result.output.stdout),
                "stderr" to Json.of(result.output.stderr),
            ),
        )
    }

    private fun buildOutput(
        command: String,
        workingDirectory: String,
        exitCode: Int?,
        output: ProcessOutput,
        timeoutMillis: Long?,
    ): String = buildString {
        append("$ ").append(command).append("  (").append(workingDirectory).append(')')
        exitCode?.let { append("\nexit ").append(it) }
        timeoutMillis?.let { append(" · timeout ").append(it).append("ms") }
        if (output.stdout.isNotBlank()) append("\n").append(output.stdout.trimEnd())
        if (output.stderr.isNotBlank()) append("\n[stderr]\n").append(output.stderr.trimEnd())
    }

    companion object {
        const val NAME = "run_command"
        const val ARG_COMMAND = "command"
        const val ARG_WORKING_DIRECTORY = "workingDirectory"
        const val ARG_TIMEOUT_MILLIS = "timeoutMillis"

        /** The shell commands are interpreted by; not configurable from the model. */
        const val SHELL = "sh"
    }
}
