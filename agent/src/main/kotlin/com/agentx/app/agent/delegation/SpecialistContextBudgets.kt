package com.agentx.app.agent.delegation

import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.context.ContextBudget

/**
 * Derives a per-specialist [ContextBudget] from the run's base budget.
 *
 * A specialist is given exactly what its job needs and no more: the Explorer and
 * Reviewer read widely but should not be handed the whole conversation; the
 * Coder and Debugger need generous file windows but little chat history; the
 * Researcher works from the network, not the repository. This is **not a new
 * context engine** — it only scales the knobs the existing [ContextBudget]
 * already exposes, so the one Context Engine still assembles and truncates
 * everything. The scaling is deterministic and relative to the base budget, so a
 * caller that tightens the base (a small model) tightens every specialist too.
 */
object SpecialistContextBudgets {

    /** Multipliers applied to the base budget's dimensions for one role. */
    private data class Scale(
        val totalChars: Double = 1.0,
        val fileCount: Double = 1.0,
        val fileChars: Double = 1.0,
        val toolResultCount: Double = 1.0,
        val conversationMessages: Double = 1.0,
        val conversationChars: Double = 1.0,
    )

    private val scales: Map<AgentRole, Scale> = mapOf(
        // Reads broadly across many files; little need for conversation history.
        AgentRole.EXPLORER to Scale(
            totalChars = 0.9,
            fileCount = 1.5,
            fileChars = 0.6,
            conversationMessages = 0.25,
            conversationChars = 0.25,
        ),
        // Works from the network; the repository is barely relevant.
        AgentRole.RESEARCHER to Scale(
            totalChars = 0.7,
            fileCount = 0.25,
            fileChars = 0.5,
            conversationMessages = 0.5,
            conversationChars = 0.5,
        ),
        // Edits a focused set of files deeply; keeps enough recent chat to follow intent.
        AgentRole.CODER to Scale(
            totalChars = 1.0,
            fileCount = 0.75,
            fileChars = 1.25,
            conversationMessages = 0.5,
            conversationChars = 0.5,
        ),
        AgentRole.FAST_CODER to Scale(
            totalChars = 0.6,
            fileCount = 0.5,
            fileChars = 1.0,
            conversationMessages = 0.25,
            conversationChars = 0.25,
        ),
        // Needs failing output and the files under test; deep file windows.
        AgentRole.DEBUGGER to Scale(
            totalChars = 1.0,
            fileCount = 0.75,
            fileChars = 1.25,
            toolResultCount = 1.5,
            conversationMessages = 0.5,
            conversationChars = 0.5,
        ),
        AgentRole.TESTER to Scale(
            totalChars = 0.9,
            fileCount = 0.75,
            fileChars = 1.0,
            toolResultCount = 1.5,
            conversationMessages = 0.25,
            conversationChars = 0.25,
        ),
        // Reads the diff/surrounding files; concise history.
        AgentRole.REVIEWER to Scale(
            totalChars = 0.9,
            fileCount = 1.0,
            fileChars = 0.75,
            conversationMessages = 0.25,
            conversationChars = 0.25,
        ),
        AgentRole.SECURITY_REVIEWER to Scale(
            totalChars = 0.9,
            fileCount = 1.0,
            fileChars = 0.75,
            conversationMessages = 0.25,
            conversationChars = 0.25,
        ),
        // Plans from the shape of the project, not its full contents.
        AgentRole.PLANNER to Scale(
            totalChars = 0.6,
            fileCount = 0.5,
            fileChars = 0.4,
            conversationMessages = 0.75,
            conversationChars = 0.5,
        ),
        AgentRole.DOCS to Scale(
            totalChars = 0.8,
            fileCount = 0.75,
            fileChars = 0.75,
            conversationMessages = 0.5,
            conversationChars = 0.5,
        ),
        // Works from the git state; very little repository or chat context.
        AgentRole.COMMIT_PR to Scale(
            totalChars = 0.5,
            fileCount = 0.5,
            fileChars = 0.4,
            conversationMessages = 0.5,
            conversationChars = 0.4,
        ),
    )

    /**
     * The budget a [role] should run with, derived from [base]. MAIN and any role
     * without a specific scale keep the base budget unchanged.
     */
    fun forRole(role: AgentRole, base: ContextBudget = ContextBudget.DEFAULT): ContextBudget {
        val scale = scales[role] ?: return base
        return base.copy(
            maxTotalChars = scaleAtLeast(base.maxTotalChars, scale.totalChars, floor = 2_000),
            maxFileCount = scaleCount(base.maxFileCount, scale.fileCount),
            maxFileChars = scaleAtLeast(base.maxFileChars, scale.fileChars, floor = 1_000),
            maxToolResultCount = scaleCount(base.maxToolResultCount, scale.toolResultCount),
            maxConversationMessages = scaleCount(base.maxConversationMessages, scale.conversationMessages),
            maxConversationChars = scaleAtLeast(base.maxConversationChars, scale.conversationChars, floor = 1_000),
        )
    }

    private fun scaleAtLeast(value: Int, factor: Double, floor: Int): Int =
        maxOf(floor, (value * factor).toInt())

    /** Counts may legitimately scale to zero (e.g. a role that needs no chat). */
    private fun scaleCount(value: Int, factor: Double): Int {
        if (value <= 0) return value
        val scaled = (value * factor).toInt()
        // Keep at least one when the role still uses the dimension at all.
        return if (factor > 0.0) scaled.coerceAtLeast(1) else 0
    }
}
