package com.agentx.app.ui.ide.model

import com.agentx.app.context.AgentAttachment
import com.agentx.app.context.AgentAttachmentKind

/**
 * Presentation models for the Agent chat. They are deliberately free of agent
 * core, model gateway and tool types — an attachment is carried as the context layer's own value
 * rather than as a second, lossy description of it: the backend's structured response is
 * mapped into these by [com.agentx.app.ui.ide.state.AgentChatPresentation]
 * before a Composable ever sees it, so no serialized JSON is rendered directly.
 */

/** What a chat entry represents. */
enum class ChatMessageKind { USER, ASSISTANT, SYSTEM, TOOL, ERROR }

/** Delivery lifecycle of one chat entry. */
enum class MessageState { STREAMING, COMPLETE, STOPPED, FAILED }

/** A block-level piece of a rendered markdown response. */
sealed interface MessageBlock {
    data class Paragraph(val spans: List<InlineSpan>) : MessageBlock

    data class Heading(val level: Int, val spans: List<InlineSpan>) : MessageBlock

    data class BulletList(val items: List<List<InlineSpan>>) : MessageBlock

    data class NumberedList(val items: List<List<InlineSpan>>) : MessageBlock

    data class Quote(val spans: List<InlineSpan>) : MessageBlock

    /** A fenced code block; [language] is whatever the fence declared. */
    data class Code(val language: String?, val code: String) : MessageBlock
}

/** An inline run of styled text inside a [MessageBlock]. */
sealed interface InlineSpan {
    data class Text(val text: String) : InlineSpan

    data class Code(val text: String) : InlineSpan

    data class Bold(val text: String) : InlineSpan

    data class Italic(val text: String) : InlineSpan

    data class Link(val text: String, val url: String) : InlineSpan
}

/** Lifecycle of one rendered tool execution. */
enum class ToolRunStatus { RUNNING, COMPLETED, FAILED, DENIED, CANCELLED }

/**
 * One tool execution as a compact card. [detail] and [summary] are always the
 * already-redacted, truncated values; secrets never reach this model.
 */
data class ToolActivityUiModel(
    val id: String,
    val toolName: String,
    val displayName: String,
    val detail: String? = null,
    val status: ToolRunStatus = ToolRunStatus.RUNNING,
    val startedAtMillis: Long = 0L,
    val elapsedMillis: Long? = null,
    val summary: String? = null,
)

/** Lifecycle of one Agent Activity row. */
enum class ActivityItemStatus { PENDING, ACTIVE, DONE, FAILED }

/**
 * What kind of execution step an activity row represents. It is derived from
 * the real AgentEvent/ToolEvent stream; it only decides presentation.
 */
enum class AgentActivityKind {
    /** A safe model/runtime progress summary, never private reasoning. */
    THINKING,

    /** The agent delegated to a sub-agent (Explorer, Coder, Reviewer, …). */
    SUB_AGENT,

    TERMINAL,
    FILE_READ,
    FILE_WRITE,
    SEARCH,
    TOOL,
    WAITING,
    ERROR,
}

/**
 * A concise, safe execution step shown in the collapsible Agent Activity
 * section. This is derived from real tool/loop events, never from private model
 * chain-of-thought.
 */
data class AgentActivityUiModel(
    val id: String,
    val label: String,
    val status: ActivityItemStatus,
    val kind: AgentActivityKind = AgentActivityKind.TOOL,
    val toolName: String? = null,
    val timestampMillis: Long = 0L,
    val elapsedMillis: Long? = null,
    /** Short safe argument preview (an argument value or the objective). */
    val detail: String? = null,
    /** Redacted, truncated output lines for expandable rows (terminal/tool). */
    val outputLines: List<String> = emptyList(),
    /** Sub-agent role name when [kind] is [AgentActivityKind.SUB_AGENT]. */
    val role: String? = null,
    /** Message text length when this step started; orders steps between text. */
    val textOffset: Int = -1,
)

/**
 * Status of one plan entry, as reported by the Agent Runtime.
 *
 * It mirrors the runtime's own step status; the UI never advances a step on its
 * own, so a plan can only ever show what the runtime actually said.
 */
enum class PlanStepStatus { PENDING, ACTIVE, DONE, FAILED }

/**
 * One high-level plan step produced by the Agent Runtime
 * ([com.agentx.app.agent.domain.AgentPlan] / [AgentStep]). Steps the runtime
 * never created are never shown; a simple task with no plan shows no plan block.
 */
data class PlanStepUiModel(
    val index: Int,
    val title: String,
    val status: PlanStepStatus,
)

/** A human-readable failure, without stack traces by default. */
data class UiError(
    val title: String,
    val message: String,
    val code: String? = null,
    val retryable: Boolean = true,
)

/** One rendered chat entry. */
data class ChatMessageUiModel(
    val id: String,
    val kind: ChatMessageKind,
    val blocks: List<MessageBlock> = emptyList(),
    val rawText: String = "",
    val timestampMillis: Long = 0L,
    val state: MessageState = MessageState.COMPLETE,
    val tool: ToolActivityUiModel? = null,
    val error: UiError? = null,
    val activities: List<AgentActivityUiModel> = emptyList(),
    /**
     * The runtime's plan for this turn, oldest step first. Empty when the Agent
     * Runtime produced no plan (a simple task), in which case no plan block is
     * rendered rather than a fabricated one.
     */
    val planSteps: List<PlanStepUiModel> = emptyList(),
    val elapsedMillis: Long? = null,
    val modelId: String? = null,
    /** Real files the runtime reports as changed this turn; empty when it edited none. */
    val filesChanged: List<String> = emptyList(),
    /** Real files the runtime inspected this turn; never fabricated. */
    val filesInspected: List<String> = emptyList(),
    /**
     * Files the user attached to this message. Live only for the current screen: attachments are
     * part of the turn's request, not of the stored transcript, so a restored conversation shows
     * the text it was sent with and no invented attachments.
     */
    val attachments: List<AttachmentUiModel> = emptyList(),
) {
    /** Plain text a copy action should place on the clipboard. */
    val copyText: String get() = rawText

    val isUser: Boolean get() = kind == ChatMessageKind.USER

    val isAssistant: Boolean get() = kind == ChatMessageKind.ASSISTANT
}

/** Outcome of one agent turn, driving the activity block's headline. */
enum class AgentTurnOutcome { RUNNING, SUCCESS, FAILED, STOPPED }

/** One entry in the Agent session sidebar. */
data class AgentSessionUiModel(
    val id: String,
    val title: String,
    val updatedAtMillis: Long,
    val messageCount: Int = 0,
    val active: Boolean = false,
)

/** Coarse phase of the current generation, driving status and controls. */
enum class GenerationPhase { IDLE, THINKING, TOOL, WAITING_PERMISSION, COMPLETED, STOPPED, FAILED }

/** Live state of a generation run, including its real elapsed timer. */
data class GenerationState(
    val phase: GenerationPhase = GenerationPhase.IDLE,
    val startedAtMillis: Long? = null,
    val elapsedMillis: Long = 0L,
) {
    val running: Boolean
        get() = phase == GenerationPhase.THINKING ||
            phase == GenerationPhase.TOOL ||
            phase == GenerationPhase.WAITING_PERMISSION

    val stopped: Boolean get() = phase == GenerationPhase.STOPPED
}

/** Everything the Agent screen renders. */
/**
 * A file attached to the message being composed, or shown on a sent one.
 *
 * It carries the domain [attachment] rather than a copy of its fields, so the composer can hand
 * exactly what the picker produced straight to the request without a lossy round-trip through
 * strings.
 */
data class AttachmentUiModel(val attachment: AgentAttachment) {
    val id: String get() = attachment.id
    val displayName: String get() = attachment.displayName
    val kind: AgentAttachmentKind get() = attachment.kind
    val sizeBytes: Long get() = attachment.sizeBytes

    /** Short, human type label for the chip: what the file is, not its full path. */
    val typeLabel: String
        get() = when (kind) {
            AgentAttachmentKind.IMAGE -> "Image"
            AgentAttachmentKind.DOCUMENT -> "Document"
            AgentAttachmentKind.FILE -> "File"
        }

    /** Compact size for a chip, or null when the size is not worth showing. */
    val sizeLabel: String?
        get() = when {
            sizeBytes <= 0L -> null
            sizeBytes < 1024L -> "$sizeBytes B"
            sizeBytes < 1024L * 1024L -> "${sizeBytes / 1024L} KB"
            else -> "${sizeBytes / (1024L * 1024L)} MB"
        }
}

/**
 * A skill the user can turn on for this message.
 *
 * Only skills the agent could actually use are listed: the chat asks the skill manager for the
 * role's own resolution, so a disabled, invalid or unassigned skill is never offered.
 */
data class SkillChoiceUiModel(
    val id: String,
    val name: String,
    val description: String,
    val selected: Boolean = false,
)

data class AgentChatUiState(
    val messages: List<ChatMessageUiModel> = emptyList(),
    val input: String = "",
    val generation: GenerationState = GenerationState(),
    val sessions: List<AgentSessionUiModel> = emptyList(),
    val activeSessionId: String? = null,
    val pendingPermission: PermissionPromptUi? = null,
    val activity: AgentActivity = AgentActivity(AgentActivityStatus.IDLE, "Idle"),
    val currentAgent: String = "Main",
    /** Model backing the agent, shown as a compact pill in the Agent header. */
    val modelId: String? = null,
    /** Files attached to the message being composed. Cleared when it is sent. */
    val attachments: List<AttachmentUiModel> = emptyList(),
    /** Skills the agent could use on this message, and which the user turned on. */
    val skills: List<SkillChoiceUiModel> = emptyList(),
    /** Set when attaching failed; shown above the composer until the next action. */
    val attachmentMessage: String? = null,
    /** True while a picker is open, so the action cannot be started twice. */
    val pickingAttachment: Boolean = false,
) {
    val running: Boolean get() = generation.running

    /** The skills the user turned on, or null to let the agent use the role's own set. */
    val selectedSkillIds: Set<String>?
        get() = skills.filter { it.selected }.map { it.id }.toSet().takeIf { it.isNotEmpty() }

    /**
     * A turn needs something to work on: text, or at least one attachment. A skill selection on its
     * own is not a request — choosing skills narrows how the agent works, it does not say what to do.
     */
    val hasContent: Boolean get() = input.isNotBlank() || attachments.isNotEmpty()

    /** Whether Send is available: there is content, and nothing else owns the turn. */
    val canSend: Boolean get() = hasContent && pendingPermission == null && !running

    /** Outcome of the latest turn, from real generation state only. */
    val turnOutcome: AgentTurnOutcome
        get() = when {
            generation.running -> AgentTurnOutcome.RUNNING
            generation.phase == GenerationPhase.FAILED -> AgentTurnOutcome.FAILED
            generation.phase == GenerationPhase.STOPPED -> AgentTurnOutcome.STOPPED
            generation.phase == GenerationPhase.COMPLETED -> AgentTurnOutcome.SUCCESS
            else -> AgentTurnOutcome.SUCCESS
        }
}

/**
 * A parked tool call awaiting the user's decision. Plain presentation data: the
 * UI never sees model or tool types directly.
 */
data class PermissionPromptUi(
    val sessionId: String,
    val toolCallId: String,
    val toolName: String,
    val detail: String,
    val requiredPermission: String,
    val reason: String,
)
