package com.agentx.app.tools.mock

import com.agentx.app.tools.Json
import com.agentx.app.tools.Tool
import com.agentx.app.tools.ToolCapability
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
import java.time.Clock
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Safe, side-effect-free tools used only to validate the tool infrastructure.
 * They never touch the filesystem, shell, network, or credentials.
 */
class EchoTool : Tool {

    override val definition = ToolDefinition(
        name = "echo",
        title = "Echo",
        description = "Returns the provided message unchanged. Used to validate the tool pipeline.",
        inputSchema = ToolInputSchema(
            parameters = listOf(
                ToolParameter(
                    name = "message",
                    type = ToolParameterType.STRING,
                    description = "Text to echo back.",
                    required = true,
                ),
            ),
        ),
        output = ToolOutputSpec(description = "The echoed message and its length."),
        permission = ToolPermissionDecision.ALLOW,
        capabilities = setOf(ToolCapability.READ_ONLY),
        metadata = mapOf("category" to "mock", "sideEffects" to "none"),
    )

    override suspend fun execute(input: ToolInput, context: ToolExecutionContext): ToolOutput {
        val message = input.string("message") ?: throw ToolExecutionError(
            code = ToolErrorCode.INVALID_ARGUMENTS,
            message = "Missing required argument 'message'",
            toolName = definition.name,
        )
        return ToolOutput(
            content = mapOf(
                "message" to Json.of(message),
                "length" to Json.of(message.length),
            ),
            displayText = message,
        )
    }
}

/** Returns the current time; the clock is injectable for deterministic tests. */
class CurrentTimeTool(
    private val clock: Clock = Clock.systemUTC(),
) : Tool {

    override val definition = ToolDefinition(
        name = "current_time",
        title = "Current Time",
        description = "Returns the current time as an ISO-8601 timestamp.",
        inputSchema = ToolInputSchema(
            parameters = listOf(
                ToolParameter(
                    name = "timeZone",
                    type = ToolParameterType.STRING,
                    description = "Optional IANA time zone id; defaults to the tool's clock zone.",
                    required = false,
                ),
            ),
        ),
        output = ToolOutputSpec(description = "The current time as ISO-8601, epoch millis, and zone."),
        permission = ToolPermissionDecision.ALLOW,
        capabilities = setOf(ToolCapability.READ_ONLY),
        metadata = mapOf("category" to "mock", "sideEffects" to "none"),
    )

    override suspend fun execute(input: ToolInput, context: ToolExecutionContext): ToolOutput {
        val zone = input.string("timeZone")?.let { id ->
            runCatching { ZoneId.of(id) }.getOrElse {
                throw ToolExecutionError(
                    code = ToolErrorCode.INVALID_ARGUMENTS,
                    message = "Unknown time zone '$id'",
                    toolName = definition.name,
                )
            }
        } ?: clock.zone

        val now = clock.instant().atZone(zone)
        val iso = now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        return ToolOutput(
            content = mapOf(
                "iso" to Json.of(iso),
                "epochMillis" to Json.of(clock.millis()),
                "timeZone" to Json.of(zone.id),
            ),
            displayText = iso,
        )
    }
}

/** Factory for the built-in mock tools. */
object MockTools {
    fun all(): List<Tool> = listOf(EchoTool(), CurrentTimeTool())

    fun echo(): Tool = EchoTool()

    fun currentTime(clock: Clock = Clock.systemUTC()): Tool = CurrentTimeTool(clock)
}
