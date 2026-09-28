package com.agentx.app.context

/**
 * Where a piece of context came from.
 *
 * The source is the coarse classification the engine ranks and budgets by;
 * [ContextReason] records *why* one specific item was selected.
 */
enum class ContextSource {
    /** The current user request the agent is answering. */
    USER_MESSAGE,

    /** Earlier turns of the same conversation. */
    CONVERSATION,

    /** A file loaded from the open workspace. */
    FILE,

    /** A directory listing from the open workspace. */
    DIRECTORY,

    /** The result of a tool the agent ran. */
    TOOL_RESULT,

    /** Information about the workspace itself (name, root, open/recent files). */
    WORKSPACE_INFO,

    /** Progress and state of the running agent task. */
    AGENT_STATE,
    ;

    /** Coarse band this source falls back to when a builder does not set one. */
    val defaultPriority: ContextPriority
        get() = when (this) {
            USER_MESSAGE -> ContextPriority.CRITICAL
            FILE -> ContextPriority.NORMAL
            TOOL_RESULT -> ContextPriority.NORMAL
            DIRECTORY -> ContextPriority.LOW
            WORKSPACE_INFO -> ContextPriority.LOW
            CONVERSATION -> ContextPriority.LOW
            AGENT_STATE -> ContextPriority.LOW
        }
}

/**
 * Coarse ranking band. A higher band always outranks a lower one; [ContextItem]
 * relevance is only used to order items inside the same band.
 */
enum class ContextPriority(val rank: Int) {
    CRITICAL(4),
    HIGH(3),
    NORMAL(2),
    LOW(1),
    ;
}

/** Why one item was selected. This is the "why" the debug view reports. */
enum class ContextReason {
    CURRENT_REQUEST,
    MENTIONED_FILE,
    SELECTED_FILE,
    RECENT_FILE,
    SEARCH_RESULT,
    TOOL_RESULT,
    DIRECTORY,
    WORKSPACE_INFO,
    AGENT_STATE,
    CONVERSATION,
    PROVIDER,
    MANUAL,
}

/** Outcome of the tool call a [ToolContextResult] describes. */
enum class ToolContextStatus {
    SUCCESS,
    FAILURE,
    CANCELLED,
    APPROVAL_REQUIRED,
}

/**
 * A tool outcome handed to the engine by the agent loop. It deliberately does
 * not reuse the Tool System's `ToolResult` because the engine only needs the
 * rendered text plus provenance, and it must never depend on tool execution.
 */
data class ToolContextResult(
    val toolId: String,
    val status: ToolContextStatus,
    val content: String,
    val path: String? = null,
    val callId: String? = null,
    val timestampMillis: Long = 0L,
    val relevance: Double = ContextRelevance.toolResult(status),
)

/** Structured provenance kept next to every [ContextItem]. */
data class ContextMetadata(
    /** Short, human-readable explanation of the selection. */
    val reason: String? = null,
    /** Machine-checkable category of the selection. */
    val selectedBecause: ContextReason? = null,
    /** Tool name for [ContextSource.TOOL_RESULT] items. */
    val toolId: String? = null,
    /** Execution status for [ContextSource.TOOL_RESULT] items. */
    val toolStatus: ToolContextStatus? = null,
    /** When the described event happened, when it is known. */
    val timestampMillis: Long? = null,
    /** 0 = most recent, for recently used files and conversation messages. */
    val recencyRank: Int? = null,
    /** Size of the content before truncation, when it was truncated. */
    val originalChars: Int? = null,
    /** Free-form, non-secret extras (counts, flags, workspace name, …). */
    val attributes: Map<String, String> = emptyMap(),
) {
    fun attribute(key: String): String? = attributes[key]

    fun withAttributes(vararg pairs: Pair<String, String>): ContextMetadata =
        copy(attributes = attributes + pairs)
}

/**
 * One unit of context. Content is always text: the Context Engine shapes what
 * the model sees, it does not own a multimodal payload format.
 */
data class ContextItem(
    /** Stable and deterministic for a given source, so builds can be compared. */
    val id: String,
    val source: ContextSource,
    val content: String,
    val priority: ContextPriority = source.defaultPriority,
    val relevance: Double = ContextRelevance.defaultFor(source),
    /** Workspace-relative path for file and directory items. */
    val path: String? = null,
    val title: String? = null,
    val metadata: ContextMetadata = ContextMetadata(),
    /** True when [content] is a shortened form of [originalChars]. */
    val truncated: Boolean = false,
    val originalChars: Int = content.length,
    val createdAtMillis: Long = 0L,
) {
    val chars: Int get() = content.length

    init {
        require(id.isNotBlank()) { "ContextItem id must not be blank" }
    }
}

/**
 * Deterministic relevance scores.
 *
 * Relevance only breaks ties *inside* a priority band, so the numbers are
 * chosen so that the required selection order holds:
 *
 * 1. current user request (CRITICAL)
 * 2. explicitly mentioned files (HIGH, highest relevance)
 * 3. selected/open/recently used files (HIGH)
 * 4. tool results (NORMAL)
 * 5. files discovered by search (NORMAL, lower)
 * 6. workspace information and directories (LOW)
 * 7. older conversation context (LOW, lowest)
 */
object ContextRelevance {
    const val CURRENT_REQUEST = 100.0
    const val AGENT_STATE = 100.0

    const val MENTIONED_FILE = 100.0
    const val SELECTED_FILE = 95.0
    const val RECENT_FILE_BASE = 90.0
    const val RECENT_FILE_STEP = 1.0
    const val RECENT_FILE_MIN = 60.0

    const val TOOL_RESULT_FAILURE = 70.0
    const val TOOL_RESULT_SUCCESS = 60.0
    const val TOOL_RESULT_APPROVAL_REQUIRED = 55.0
    const val TOOL_RESULT_CANCELLED = 10.0

    const val SEARCH_RESULT = 50.0

    const val WORKSPACE_INFO = 100.0
    const val DIRECTORY = 80.0

    const val CONVERSATION = 40.0
    const val CONVERSATION_STEP = 2.0
    const val CONVERSATION_MIN = 10.0

    const val DEFAULT = 50.0

    fun defaultFor(source: ContextSource): Double = when (source) {
        ContextSource.USER_MESSAGE -> CURRENT_REQUEST
        ContextSource.AGENT_STATE -> AGENT_STATE
        ContextSource.FILE -> SEARCH_RESULT
        ContextSource.TOOL_RESULT -> TOOL_RESULT_SUCCESS
        ContextSource.DIRECTORY -> DIRECTORY
        ContextSource.WORKSPACE_INFO -> WORKSPACE_INFO
        ContextSource.CONVERSATION -> CONVERSATION
    }

    fun toolResult(status: ToolContextStatus): Double = when (status) {
        ToolContextStatus.FAILURE -> TOOL_RESULT_FAILURE
        ToolContextStatus.SUCCESS -> TOOL_RESULT_SUCCESS
        ToolContextStatus.APPROVAL_REQUIRED -> TOOL_RESULT_APPROVAL_REQUIRED
        ToolContextStatus.CANCELLED -> TOOL_RESULT_CANCELLED
    }

    /** [index] is 0 for the most recently used file. */
    fun recentFile(index: Int): Double =
        (RECENT_FILE_BASE - index * RECENT_FILE_STEP).coerceAtLeast(RECENT_FILE_MIN)

    /** [recencyRank] is 0 for the newest conversation message. */
    fun conversation(recencyRank: Int): Double =
        (CONVERSATION - recencyRank * CONVERSATION_STEP).coerceAtLeast(CONVERSATION_MIN)
}
