package com.agentx.app.agent

import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.model.ModelCapabilities
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.ModelFinishReason
import com.agentx.app.model.ModelGateway
import com.agentx.app.model.ModelMessage
import com.agentx.app.model.ModelProvider
import com.agentx.app.model.ModelProviderError
import com.agentx.app.model.ModelProviderErrorCode
import com.agentx.app.model.ModelRequest
import com.agentx.app.model.ModelResponse
import com.agentx.app.model.ModelRole
import com.agentx.app.model.ModelStreamEvent
import com.agentx.app.model.ModelToolCall
import com.agentx.app.model.json.JsonObject
import com.agentx.app.model.json.JsonValue
import com.agentx.app.tools.Json
import com.agentx.app.tools.Tool
import com.agentx.app.tools.ToolCapability
import com.agentx.app.tools.ToolDefinition
import com.agentx.app.tools.ToolExecutionContext
import com.agentx.app.tools.ToolInput
import com.agentx.app.tools.ToolInputSchema
import com.agentx.app.tools.ToolOutput
import com.agentx.app.tools.ToolParameter
import com.agentx.app.tools.ToolParameterType
import com.agentx.app.tools.ToolPermissionDecision
import com.agentx.app.tools.ToolPermissionLevel
import kotlinx.coroutines.runBlocking

internal fun <T> runAgent(block: suspend () -> T): T = runBlocking { block() }

internal fun testConfig(): ModelConfig = ModelConfig(
    providerId = "test",
    baseUrl = "http://localhost:9/v1",
    model = "test-model",
)

internal fun jsonArgs(vararg pairs: Pair<String, String>): JsonObject =
    pairs.associate { (key, value) -> key to JsonValue.Str(value) }

internal fun toolCall(name: String, vararg args: Pair<String, String>, id: String = "call-$name"): ModelToolCall =
    ModelToolCall(id = id, name = name, arguments = jsonArgs(*args))

internal fun response(content: String = "", vararg calls: ModelToolCall): ModelResponse = ModelResponse(
    model = "test-model",
    providerId = "test",
    content = content,
    toolCalls = calls.toList(),
    finishReason = if (calls.isEmpty()) ModelFinishReason.STOP else ModelFinishReason.TOOL_CALLS,
)

internal class ScriptedModelProvider(
    private val scripts: Map<AgentRole, MutableList<ModelResponse>>,
    override val id: String = "test",
) : ModelProvider {

    val completeCalls = mutableListOf<AgentRole>()

    /** Every request the agent loop sent, in order, for continuation assertions. */
    val requests = mutableListOf<ModelRequest>()

    override fun capabilities(modelId: String): ModelCapabilities =
        ModelCapabilities(toolCalling = true, streaming = true)

    override suspend fun complete(request: ModelRequest): ModelResponse {
        requests += request
        val role = detectRole(request.messages)
        completeCalls += role
        val queue = scripts[role] ?: return response("no script for $role")
        if (queue.isEmpty()) return response("exhausted $role")
        return queue.removeAt(0)
    }

    override suspend fun stream(request: ModelRequest, onEvent: (ModelStreamEvent) -> Unit): ModelResponse {
        val result = complete(request)
        onEvent(ModelStreamEvent.TextDelta(result.content))
        return result
    }

    private fun detectRole(messages: List<ModelMessage>): AgentRole {
        val system = messages.firstOrNull { it.role == ModelRole.SYSTEM }?.content.orEmpty()
        return AgentRole.entries.firstOrNull { role -> system.contains("Role: ${role.name}") } ?: AgentRole.MAIN
    }
}

internal class RecordingTool(
    name: String,
    private val capabilities: Set<ToolCapability>,
    permission: ToolPermissionDecision = ToolPermissionDecision.ALLOW,
    required: Set<ToolPermissionLevel> = emptySet(),
) : Tool {
    val invocations = mutableListOf<ToolInput>()

    override val definition = ToolDefinition(
        name = name,
        description = "Test tool $name",
        inputSchema = ToolInputSchema(
            parameters = listOf(
                ToolParameter("path", ToolParameterType.STRING, required = false),
                ToolParameter("content", ToolParameterType.STRING, required = false),
            ),
        ),
        permission = permission,
        capabilities = capabilities,
        requiredPermissions = required,
    )

    override suspend fun execute(input: ToolInput, context: ToolExecutionContext): ToolOutput {
        invocations += input
        val path = input.string("path")
        return ToolOutput(
            content = buildMap {
                path?.let { put("path", Json.of(it)) }
                put("ok", Json.of(true))
            },
            displayText = "ok ${path ?: definition.name}",
        )
    }
}

internal fun throwingGateway(error: Throwable = IllegalStateException("gateway down")): ModelGateway =
    object : ModelGateway {
        override fun register(provider: ModelProvider) = Unit
        override fun unregister(id: String): Boolean = false
        override fun providers(): List<ModelProvider> = emptyList()
        override fun provider(id: String): ModelProvider? = null
        override fun resolve(request: ModelRequest): ModelProvider? = null
        override fun capabilities(request: ModelRequest): ModelCapabilities = ModelCapabilities()
        override suspend fun complete(request: ModelRequest): ModelResponse = throw error
        override suspend fun stream(request: ModelRequest, onEvent: (ModelStreamEvent) -> Unit): ModelResponse =
            throw error
    }

internal fun providerError(
    code: ModelProviderErrorCode,
    message: String = code.name.lowercase(),
): ModelProviderError = ModelProviderError(
    code = code,
    message = message,
    providerId = "test",
    retryable = true,
)
