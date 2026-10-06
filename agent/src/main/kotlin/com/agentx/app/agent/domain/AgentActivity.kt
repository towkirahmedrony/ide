package com.agentx.app.agent.domain

/**
 * The safe, high-level activity the agent is performing.
 *
 * This is the only lifecycle vocabulary the UI is shown beyond the plan: it never
 * carries model reasoning, prompt text or tool arguments, only the stage of the
 * inspect → understand → plan → read → modify → diff → verify → fix → commit →
 * push workflow that the run has reached.
 *
 * It is intentionally separate from [AgentStatus]: [AgentStatus] answers "how did
 * the run end" (terminal states, permission pauses), while this answers "what is
 * happening now". A run is [AgentStatus.RUNNING] while its activity moves through
 * several of these values.
 */
enum class AgentActivity {
    INSPECTING,
    PLANNING,
    READING,
    EDITING,
    REVIEWING_CHANGES,
    VERIFYING,
    VERIFICATION_FAILED,
    FIXING,
    RE_VERIFYING,
    COMMITTING,
    PUSHING,
    COMPLETED,
    BLOCKED,
    CANCELLED,
    ;

    /** True when the activity is a terminal stage of the workflow. */
    val isTerminal: Boolean
        get() = this in setOf(COMPLETED, BLOCKED, CANCELLED)
}
