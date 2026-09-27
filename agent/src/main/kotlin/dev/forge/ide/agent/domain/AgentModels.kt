package dev.forge.ide.agent.domain

data class AgentDefinition(
    val role: AgentRole,
    val name: String,
    val systemInstructions: String,
    val allowedTools: List<String>,
    val permissionLevel: PermissionLevel,
    val isReadOnly: Boolean,
    val maxSteps: Int,
    val modelPreference: String? = null,
) {
    init {
        require(name.isNotBlank()) { "Agent name must not be blank" }
        require(maxSteps > 0) { "maxSteps must be positive" }
    }

    val effectivePermission: PermissionLevel
        get() = if (isReadOnly) PermissionLevel.READ_ONLY else permissionLevel
}

data class AgentTask(
    val id: String,
    val prompt: String,
    val objective: String? = null,
    val workspaceId: String? = null,
)

data class AgentStep(
    val index: Int,
    val title: String,
    val role: AgentRole = AgentRole.MAIN,
    val status: AgentStatus = AgentStatus.IDLE,
    val detail: String? = null,
)

data class AgentPlan(
    val steps: List<AgentStep> = emptyList(),
    val revision: Int = 1,
)

data class ToolActionRecord(
    val toolName: String,
    val success: Boolean,
    val summary: String,
    val path: String? = null,
)

data class AgentSession(
    val id: String,
    val parentSessionId: String? = null,
    val role: AgentRole,
    val status: AgentStatus,
    val task: AgentTask,
    val plan: AgentPlan? = null,
    val steps: List<AgentStep> = emptyList(),
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val workspaceId: String? = null,
)

data class AgentResult(
    val sessionId: String,
    val status: AgentStatus,
    val summary: String,
    val findings: List<String> = emptyList(),
    val filesInspected: List<String> = emptyList(),
    val filesChanged: List<String> = emptyList(),
    val toolActions: List<ToolActionRecord> = emptyList(),
    val errors: List<AgentError> = emptyList(),
    val plan: AgentPlan? = null,
    val role: AgentRole = AgentRole.MAIN,
)

data class SubAgentRequest(
    val role: AgentRole,
    val task: String,
    val objective: String,
    val scopedContext: String = "",
    val allowedTools: List<String> = emptyList(),
    val permissionLevel: PermissionLevel? = null,
    val maxSteps: Int? = null,
    val parentSessionId: String,
    val workspaceId: String? = null,
    val sessionId: String,
)

data class SubAgentResult(
    val sessionId: String,
    val role: AgentRole,
    val status: AgentStatus,
    val summary: String,
    val findings: List<String> = emptyList(),
    val filesInspected: List<String> = emptyList(),
    val filesChanged: List<String> = emptyList(),
    val toolActions: List<ToolActionRecord> = emptyList(),
    val errors: List<AgentError> = emptyList(),
) {
    fun toAgentResult(plan: AgentPlan? = null): AgentResult = AgentResult(
        sessionId = sessionId,
        status = status,
        summary = summary,
        findings = findings,
        filesInspected = filesInspected,
        filesChanged = filesChanged,
        toolActions = toolActions,
        errors = errors,
        plan = plan,
        role = role,
    )
}

data class AgentRunRequest(
    val prompt: String,
    val sessionId: String? = null,
    val workspaceId: String? = null,
    val timeoutMillis: Long? = null,
    val context: String = "",
)

data class AgentContext(
    val workspaceId: String? = null,
    val snippets: List<String> = emptyList(),
)

fun interface AgentContextSource {
    suspend fun assemble(query: String, workspaceId: String?): AgentContext
}
