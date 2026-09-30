package com.agentx.app.core.timeout

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Central, operation-specific execution budgets for the agent stack.
 *
 * An agentic coding task legitimately takes far longer than a couple of minutes:
 * it is inspect → search → read → plan → modify → run → build/test → debug →
 * review. A single short, global "agent timeout" therefore cannot be correct —
 * it truncates exactly the work the product exists to do, and it reports it as a
 * failure rather than as the partial progress that actually happened.
 *
 * So there is one place — this class — that owns every budget, and each category
 * of work has its own, generous, independently configurable value.
 *
 * Two rules keep these budgets from becoming accidental kill switches:
 * - a budget is a net for a *stuck* operation, not a deadline for real work;
 * - stopping long-running work normally is the user's explicit Stop/Cancel,
 *   which cancels the coroutine and everything it owns. [UNBOUNDED] opts a
 *   category out of the wall-clock net entirely, leaving cancellation as the
 *   only limit; no category does that by default.
 *
 * Values are milliseconds. A budget must be positive, or exactly [UNBOUNDED].
 */
data class AgentTimeouts(
    /** One model request, including a streamed response. */
    val modelRequestMillis: Long = MODEL_REQUEST_MILLIS,

    /** The whole Main Agent task: first model call through final answer. */
    val mainTaskMillis: Long = MAIN_TASK_MILLIS,

    /** The whole task of one sub-agent delegation. */
    val subAgentTaskMillis: Long = SUB_AGENT_TASK_MILLIS,

    /** One tool call for tools with no more specific category. */
    val toolExecutionMillis: Long = TOOL_EXECUTION_MILLIS,

    /** Shell/command work: builds, test suites, syncs. */
    val shellCommandMillis: Long = SHELL_COMMAND_MILLIS,

    /** A single filesystem read, list or search. */
    val fileOperationMillis: Long = FILE_OPERATION_MILLIS,

    /** A network/web call made through a tool (fetch, remote API). */
    val networkMillis: Long = NETWORK_MILLIS,
) {
    init {
        requireBudget("modelRequestMillis", modelRequestMillis)
        requireBudget("mainTaskMillis", mainTaskMillis)
        requireBudget("subAgentTaskMillis", subAgentTaskMillis)
        requireBudget("toolExecutionMillis", toolExecutionMillis)
        requireBudget("shellCommandMillis", shellCommandMillis)
        requireBudget("fileOperationMillis", fileOperationMillis)
        requireBudget("networkMillis", networkMillis)
    }

    companion object {
        const val SECOND: Long = 1_000L
        const val MINUTE: Long = 60 * SECOND

        /**
         * One model request. Generous on purpose: a large diff or a long streamed
         * answer is normal, and the transport already has its own socket timeouts.
         */
        const val MODEL_REQUEST_MILLIS: Long = 5 * MINUTE

        /**
         * The whole Main Agent task. A real inspect → modify → build → debug cycle
         * can run for a long time; this is the ceiling for a task that is stuck,
         * not a deadline. Users stop a long task with Stop/Cancel.
         */
        const val MAIN_TASK_MILLIS: Long = 60 * MINUTE

        /** A single delegated sub-agent task, one level below the Main budget. */
        const val SUB_AGENT_TASK_MILLIS: Long = 30 * MINUTE

        /** Generic per-tool budget when no category applies. */
        const val TOOL_EXECUTION_MILLIS: Long = 5 * MINUTE

        /** Shell/command budget: package sync, builds and test suites are slow. */
        const val SHELL_COMMAND_MILLIS: Long = 30 * MINUTE

        /** A filesystem read/list/search. */
        const val FILE_OPERATION_MILLIS: Long = 2 * MINUTE

        /** A tool-level network call. */
        const val NETWORK_MILLIS: Long = 3 * MINUTE

        /**
         * No wall-clock cap: only cancellation ends this category. Deliberately
         * not used by any default — opting in must be an explicit decision.
         */
        const val UNBOUNDED: Long = Long.MAX_VALUE

        /** The shipped configuration. */
        val DEFAULT: AgentTimeouts = AgentTimeouts()
    }
}

/** True when [this] budget means "run until cancelled", not "run until a deadline". */
val Long.isUnboundedBudget: Boolean get() = this >= AgentTimeouts.UNBOUNDED

private fun requireBudget(name: String, value: Long) {
    require(value > 0) { "$name must be positive, was $value" }
}

/**
 * Runs [block] under [millis], or with no wall-clock cap when [millis] is
 * unbounded ([isUnboundedBudget]).
 *
 * Whichever branch runs, cancelling the calling coroutine still stops the work:
 * the budget is an extra net for a stuck operation, never a replacement for
 * cancellation.
 *
 * @throws TimeoutCancellationException when the budget is exceeded.
 */
suspend fun <T> withExecutionBudget(millis: Long, block: suspend () -> T): T =
    if (millis.isUnboundedBudget) block() else withTimeout(millis) { block() }
