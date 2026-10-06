package com.agentx.app.ui.ide.model

/**
 * Presentation models for the Agent chat. They are deliberately free of agent
 * core, model gateway and tool types: the backend's structured response is
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
) {
    val running: Boolean get() = generation.running

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
