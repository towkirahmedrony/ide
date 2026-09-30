package com.agentx.app.agent.conversation

import com.agentx.app.agent.domain.AgentPlan
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.domain.AgentSession
import com.agentx.app.agent.domain.AgentStatus
import com.agentx.app.agent.domain.AgentStep
import com.agentx.app.agent.domain.AgentTask
import com.agentx.app.model.json.Json
import com.agentx.app.model.json.JsonCodec
import com.agentx.app.model.json.JsonObject
import com.agentx.app.model.json.JsonValue
import com.agentx.app.model.json.arrayOrNull
import com.agentx.app.model.json.booleanOrNull
import com.agentx.app.model.json.numberOrNull
import com.agentx.app.model.json.objectOrNull
import com.agentx.app.model.json.stringOrNull

/**
 * Serializes conversations with the JSON codec already used by presets. Unknown
 * fields are ignored; an unreadable entry is dropped rather than guessed at.
 */
object ConversationCodec {

    fun encode(conversation: AgentConversation): String = JsonCodec.encode(toJson(conversation))

    fun decode(text: String): AgentConversation? = runCatching {
        JsonCodec.parse(text).objectOrNull()?.let(::fromJson)
    }.getOrNull()

    fun encodeAll(conversations: List<AgentConversation>): String =
        JsonCodec.encode(JsonValue.Arr(conversations.map(::toJson)))

    fun decodeAll(text: String): List<AgentConversation> = runCatching {
        JsonCodec.parse(text).arrayOrNull()
            ?.mapNotNull { item -> item.objectOrNull()?.let(::fromJson) }
            .orEmpty()
    }.getOrElse { emptyList() }

    private fun toJson(conversation: AgentConversation): JsonValue.Obj {
        val session = conversation.session
        val fields = LinkedHashMap<String, JsonValue>()
        fields["id"] = Json.of(session.id)
        session.parentSessionId?.let { fields["parentSessionId"] = Json.of(it) }
        fields["role"] = Json.of(session.role.name)
        fields["status"] = Json.of(session.status.name)
        fields["createdAtMillis"] = Json.of(session.createdAtMillis)
        fields["updatedAtMillis"] = Json.of(session.updatedAtMillis)
        session.workspaceId?.let { fields["workspaceId"] = Json.of(it) }
        session.title?.let { fields["title"] = Json.of(it) }
        session.modelProviderId?.let { fields["modelProviderId"] = Json.of(it) }
        session.modelId?.let { fields["modelId"] = Json.of(it) }
        fields["task"] = Json.obj(
            "id" to Json.of(session.task.id),
            "prompt" to Json.of(session.task.prompt),
            "objective" to (session.task.objective?.let { Json.of(it) } ?: JsonValue.Null),
            "workspaceId" to (session.task.workspaceId?.let { Json.of(it) } ?: JsonValue.Null),
        )
        session.plan?.let { fields["plan"] = encodePlan(it) }
        if (session.steps.isNotEmpty()) {
            fields["steps"] = Json.array(session.steps.map(::encodeStep))
        }
        fields["messages"] = Json.array(conversation.messages.map(::encodeMessage))
        if (!conversation.summary.isEmpty()) fields["summary"] = encodeSummary(conversation.summary)
        if (!conversation.taskState.isEmpty()) fields["taskState"] = encodeTaskState(conversation.taskState)
        return JsonValue.Obj(fields)
    }

    private fun fromJson(json: JsonObject): AgentConversation? {
        val id = json.stringOrNull("id")?.takeIf { it.isNotBlank() } ?: return null
        val role = fromName(AgentRole.entries, json.stringOrNull("role")) ?: AgentRole.MAIN
        val status = fromName(AgentStatus.entries, json.stringOrNull("status")) ?: AgentStatus.IDLE
        val taskJson = json.objectOrNull("task")
        val task = AgentTask(
            id = taskJson?.stringOrNull("id")?.takeIf { it.isNotBlank() } ?: id,
            prompt = taskJson?.stringOrNull("prompt").orEmpty(),
            objective = taskJson?.stringOrNull("objective")?.takeIf { it.isNotBlank() },
            workspaceId = taskJson?.stringOrNull("workspaceId")?.takeIf { it.isNotBlank() }
                ?: json.stringOrNull("workspaceId")?.takeIf { it.isNotBlank() },
        )
        val session = AgentSession(
            id = id,
            parentSessionId = json.stringOrNull("parentSessionId")?.takeIf { it.isNotBlank() },
            role = role,
            status = status,
            task = task,
            plan = json.objectOrNull("plan")?.let(::decodePlan),
            steps = json.arrayOrNull("steps")
                ?.mapNotNull { it.objectOrNull()?.let(::decodeStep) }
                .orEmpty(),
            createdAtMillis = json.numberOrNull("createdAtMillis")?.toLong() ?: 0L,
            updatedAtMillis = json.numberOrNull("updatedAtMillis")?.toLong() ?: 0L,
            workspaceId = json.stringOrNull("workspaceId")?.takeIf { it.isNotBlank() },
            title = json.stringOrNull("title")?.takeIf { it.isNotBlank() },
            modelProviderId = json.stringOrNull("modelProviderId")?.takeIf { it.isNotBlank() },
            modelId = json.stringOrNull("modelId")?.takeIf { it.isNotBlank() },
        )
        val messages = json.arrayOrNull("messages")
            ?.mapNotNull { it.objectOrNull()?.let { obj -> decodeMessage(obj, id) } }
            .orEmpty()
        return AgentConversation(
            session = session,
            messages = messages,
            summary = json.objectOrNull("summary")?.let(::decodeSummary) ?: SessionSummary(),
            taskState = json.objectOrNull("taskState")?.let(::decodeTaskState) ?: SessionTaskState(),
        )
    }

    private fun encodeMessage(message: ConversationMessage): JsonValue {
        val content = message.content
        val metadata = message.metadata
        val fields = LinkedHashMap<String, JsonValue>()
        fields["id"] = Json.of(message.id)
        fields["sessionId"] = Json.of(message.sessionId)
        fields["role"] = Json.of(message.role.name)
        fields["text"] = Json.of(content.text)
        content.toolName?.let { fields["toolName"] = Json.of(it) }
        content.toolArguments?.let { fields["toolArguments"] = Json.of(it) }
        content.toolResult?.let { fields["toolResult"] = Json.of(it) }
        content.toolCallId?.let { fields["toolCallId"] = Json.of(it) }
        content.subAgentRole?.let { fields["subAgentRole"] = Json.of(it) }
        content.subAgentSessionId?.let { fields["subAgentSessionId"] = Json.of(it) }
        content.errorCode?.let { fields["errorCode"] = Json.of(it) }
        fields["status"] = Json.of(metadata.status.name)
        fields["timestampMillis"] = Json.of(metadata.timestampMillis)
        metadata.modelProviderId?.let { fields["modelProviderId"] = Json.of(it) }
        metadata.modelId?.let { fields["modelId"] = Json.of(it) }
        metadata.toolSuccess?.let { fields["toolSuccess"] = Json.of(it) }
        if (metadata.truncated) fields["truncated"] = Json.of(true)
        if (metadata.redacted) fields["redacted"] = Json.of(true)
        if (metadata.attributes.isNotEmpty()) {
            fields["attributes"] = Json.obj(metadata.attributes.mapValues { Json.of(it.value) })
        }
        return JsonValue.Obj(fields)
    }

    private fun decodeMessage(json: JsonObject, fallbackSessionId: String): ConversationMessage? {
        val id = json.stringOrNull("id")?.takeIf { it.isNotBlank() } ?: return null
        val role = fromName(MessageRole.entries, json.stringOrNull("role")) ?: return null
        val attributes = json.objectOrNull("attributes")
            ?.mapNotNull { (key, value) -> value.stringOrNull()?.let { key to it } }
            ?.toMap()
            .orEmpty()
        return ConversationMessage(
            id = id,
            sessionId = json.stringOrNull("sessionId")?.takeIf { it.isNotBlank() } ?: fallbackSessionId,
            role = role,
            content = MessageContent(
                text = json.stringOrNull("text").orEmpty(),
                toolName = json.stringOrNull("toolName")?.takeIf { it.isNotBlank() },
                toolArguments = json.stringOrNull("toolArguments")?.takeIf { it.isNotBlank() },
                toolResult = json.stringOrNull("toolResult")?.takeIf { it.isNotBlank() },
                toolCallId = json.stringOrNull("toolCallId")?.takeIf { it.isNotBlank() },
                subAgentRole = json.stringOrNull("subAgentRole")?.takeIf { it.isNotBlank() },
                subAgentSessionId = json.stringOrNull("subAgentSessionId")?.takeIf { it.isNotBlank() },
                errorCode = json.stringOrNull("errorCode")?.takeIf { it.isNotBlank() },
            ),
            metadata = MessageMetadata(
                status = fromName(MessageStatus.entries, json.stringOrNull("status")) ?: MessageStatus.COMPLETED,
                timestampMillis = json.numberOrNull("timestampMillis")?.toLong() ?: 0L,
                modelProviderId = json.stringOrNull("modelProviderId")?.takeIf { it.isNotBlank() },
                modelId = json.stringOrNull("modelId")?.takeIf { it.isNotBlank() },
                toolSuccess = json.booleanOrNull("toolSuccess"),
                truncated = json.booleanOrNull("truncated") ?: false,
                redacted = json.booleanOrNull("redacted") ?: false,
                attributes = attributes,
            ),
        )
    }

    private fun encodePlan(plan: AgentPlan): JsonValue = Json.obj(
        "revision" to Json.of(plan.revision),
        "steps" to Json.array(plan.steps.map(::encodeStep)),
    )

    private fun decodePlan(json: JsonObject): AgentPlan = AgentPlan(
        steps = json.arrayOrNull("steps")
            ?.mapNotNull { it.objectOrNull()?.let(::decodeStep) }
            .orEmpty(),
        revision = json.numberOrNull("revision")?.toInt() ?: 1,
    )

    private fun encodeStep(step: AgentStep): JsonValue = Json.obj(
        "index" to Json.of(step.index),
        "title" to Json.of(step.title),
        "role" to Json.of(step.role.name),
        "status" to Json.of(step.status.name),
        "detail" to (step.detail?.let { Json.of(it) } ?: JsonValue.Null),
    )

    private fun decodeStep(json: JsonObject): AgentStep? {
        val title = json.stringOrNull("title")?.takeIf { it.isNotBlank() } ?: return null
        return AgentStep(
            index = json.numberOrNull("index")?.toInt() ?: 0,
            title = title,
            role = fromName(AgentRole.entries, json.stringOrNull("role")) ?: AgentRole.MAIN,
            status = fromName(AgentStatus.entries, json.stringOrNull("status")) ?: AgentStatus.IDLE,
            detail = json.stringOrNull("detail")?.takeIf { it.isNotBlank() },
        )
    }

    private fun encodeSummary(summary: SessionSummary): JsonValue {
        val fields = LinkedHashMap<String, JsonValue>()
        summary.currentTask?.let { fields["currentTask"] = Json.of(it) }
        if (summary.completed.isNotEmpty()) fields["completed"] = stringArray(summary.completed)
        if (summary.discoveredFiles.isNotEmpty()) fields["discoveredFiles"] = stringArray(summary.discoveredFiles)
        if (summary.decisions.isNotEmpty()) fields["decisions"] = stringArray(summary.decisions)
        if (summary.unresolved.isNotEmpty()) fields["unresolved"] = stringArray(summary.unresolved)
        if (summary.requirements.isNotEmpty()) fields["requirements"] = stringArray(summary.requirements)
        if (summary.constraints.isNotEmpty()) fields["constraints"] = stringArray(summary.constraints)
        if (summary.toolHighlights.isNotEmpty()) fields["toolHighlights"] = stringArray(summary.toolHighlights)
        fields["updatedAtMillis"] = Json.of(summary.updatedAtMillis)
        return JsonValue.Obj(fields)
    }

    private fun decodeSummary(json: JsonObject): SessionSummary = SessionSummary(
        currentTask = json.stringOrNull("currentTask")?.takeIf { it.isNotBlank() },
        completed = stringList(json, "completed"),
        discoveredFiles = stringList(json, "discoveredFiles"),
        decisions = stringList(json, "decisions"),
        unresolved = stringList(json, "unresolved"),
        requirements = stringList(json, "requirements"),
        constraints = stringList(json, "constraints"),
        toolHighlights = stringList(json, "toolHighlights"),
        updatedAtMillis = json.numberOrNull("updatedAtMillis")?.toLong() ?: 0L,
    )

    private fun encodeTaskState(state: SessionTaskState): JsonValue {
        val fields = LinkedHashMap<String, JsonValue>()
        state.activeTask?.let { fields["activeTask"] = Json.of(it) }
        state.currentPlan?.let { fields["currentPlan"] = Json.of(it) }
        state.currentStep?.let { fields["currentStep"] = Json.of(it) }
        if (state.completedSteps.isNotEmpty()) fields["completedSteps"] = stringArray(state.completedSteps)
        if (state.pendingSteps.isNotEmpty()) fields["pendingSteps"] = stringArray(state.pendingSteps)
        if (state.relevantFiles.isNotEmpty()) fields["relevantFiles"] = stringArray(state.relevantFiles)
        state.lastToolResult?.let { fields["lastToolResult"] = Json.of(it) }
        state.lastError?.let { fields["lastError"] = Json.of(it) }
        state.workspaceId?.let { fields["workspaceId"] = Json.of(it) }
        return JsonValue.Obj(fields)
    }

    private fun decodeTaskState(json: JsonObject): SessionTaskState = SessionTaskState(
        activeTask = json.stringOrNull("activeTask")?.takeIf { it.isNotBlank() },
        currentPlan = json.stringOrNull("currentPlan")?.takeIf { it.isNotBlank() },
        currentStep = json.stringOrNull("currentStep")?.takeIf { it.isNotBlank() },
        completedSteps = stringList(json, "completedSteps"),
        pendingSteps = stringList(json, "pendingSteps"),
        relevantFiles = stringList(json, "relevantFiles"),
        lastToolResult = json.stringOrNull("lastToolResult")?.takeIf { it.isNotBlank() },
        lastError = json.stringOrNull("lastError")?.takeIf { it.isNotBlank() },
        workspaceId = json.stringOrNull("workspaceId")?.takeIf { it.isNotBlank() },
    )

    private fun stringArray(values: List<String>): JsonValue =
        Json.array(values.map { Json.of(it) })

    private fun stringList(json: JsonObject, key: String): List<String> =
        json.arrayOrNull(key)?.mapNotNull { it.stringOrNull()?.takeIf { value -> value.isNotBlank() } }.orEmpty()

    private fun <T : Enum<T>> fromName(values: List<T>, raw: String?): T? =
        raw?.let { name -> values.firstOrNull { it.name == name } }
}
