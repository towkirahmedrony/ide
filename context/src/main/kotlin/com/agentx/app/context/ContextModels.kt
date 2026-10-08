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

    /**
     * The *structure* of a file: its declarations, not its text. Produced by the
     * code intelligence layer, so the agent can reason about a file's shape
     * without paying for the whole source.
     */
    CODE_STRUCTURE,

    /** A directory listing from the open workspace. */
    DIRECTORY,

    /** The result of a tool the agent ran. */
    TOOL_RESULT,

    /** Information about the workspace itself (name, root, open/recent files). */
    WORKSPACE_INFO,

    /** Progress and state of the running agent task. */
    AGENT_STATE,

    /** Instructions from a skill assigned to the running agent role. */
    SKILL,

    /**
     * The project's own design direction, read from its `DESIGN.md`.
     *
     * This is reference *data about a project*, not an instruction: it says what
     * this product should look like and never overrides a role, a permission, a
     * tool grant or a universal constraint.
     */
    DESIGN,
    ;

    /** Coarse band this source falls back to when a builder does not set one. */
    val defaultPriority: ContextPriority
        get() = when (this) {
            USER_MESSAGE -> ContextPriority.CRITICAL
            FILE -> ContextPriority.NORMAL
            TOOL_RESULT -> ContextPriority.NORMAL
            CODE_STRUCTURE -> ContextPriority.NORMAL
            DIRECTORY -> ContextPriority.LOW
            WORKSPACE_INFO -> ContextPriority.LOW
            CONVERSATION -> ContextPriority.LOW
            AGENT_STATE -> ContextPriority.LOW
            SKILL -> ContextPriority.NORMAL
            DESIGN -> ContextPriority.NORMAL
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

    /** A file the user attached to this message, rather than named in it. */
    ATTACHMENT,
    SELECTED_FILE,
    RECENT_FILE,
    SEARCH_RESULT,
    TOOL_RESULT,
    DIRECTORY,
    WORKSPACE_INFO,
    AGENT_STATE,
    CONVERSATION,
    PROVIDER,
    SKILL,
    MANUAL,

    /** The project's own design direction file (`DESIGN.md`). */
    PROJECT_DESIGN,
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

    /**
     * An attachment. The user chose the file for *this* message, so it ranks with an
     * explicitly mentioned file and above whatever the editor happens to have open.
     */
    const val ATTACHMENT = 100.0
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

    /**
     * Structure. Sits just below a file read and above search results: an
     * outline is cheaper than the source, but the source wins when both fit.
     */
    const val CODE_STRUCTURE = 45.0

    const val CONVERSATION = 40.0
    const val CONVERSATION_STEP = 2.0
    const val CONVERSATION_MIN = 10.0

    /**
     * Skills. Sits between tool results and search results: skill instructions
     * are useful but must never displace the user's request or real file reads.
     */
    const val SKILL_HIGH = 68.0
    const val SKILL_NORMAL = 58.0
    const val SKILL_LOW = 42.0

    /**
     * The project's own design direction.
     *
     * It sits in the same band as the skills: it steers how UI work is done, so it
     * outranks the workspace descriptor and older conversation, but it must never
     * displace the user's request, a real file read or a tool result.
     */
    const val PROJECT_DESIGN = 66.0

    const val DEFAULT = 50.0

    fun defaultFor(source: ContextSource): Double = when (source) {
        ContextSource.USER_MESSAGE -> CURRENT_REQUEST
        ContextSource.AGENT_STATE -> AGENT_STATE
        ContextSource.FILE -> SEARCH_RESULT
        ContextSource.CODE_STRUCTURE -> CODE_STRUCTURE
        ContextSource.TOOL_RESULT -> TOOL_RESULT_SUCCESS
        ContextSource.DIRECTORY -> DIRECTORY
        ContextSource.WORKSPACE_INFO -> WORKSPACE_INFO
        ContextSource.CONVERSATION -> CONVERSATION
        ContextSource.SKILL -> SKILL_NORMAL
        ContextSource.DESIGN -> PROJECT_DESIGN
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
