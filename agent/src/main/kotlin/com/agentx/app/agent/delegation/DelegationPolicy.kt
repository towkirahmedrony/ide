package com.agentx.app.agent.delegation

import com.agentx.app.agent.domain.AgentRole

/**
 * The single authoritative, deterministic policy for how the Main Agent
 * delegates.
 *
 * It answers, without any model call, the questions Part 7 asks of the
 * orchestrator:
 *  - **Is delegation necessary at all?** ([shouldDelegate]) — a SIMPLE task is
 *    handled by the Main Agent directly.
 *  - **Which specialist fits a task?** ([selectRole]) — capability/role aware,
 *    using only the existing [AgentRole]s.
 *  - **Is a specific delegation allowed right now?** ([evaluate]) — enforces max
 *    depth, max total specialists, max repeats of one role, and rejects a
 *    redundant repeat of a role that already succeeded on the same task.
 *
 * The Main Agent remains the sole orchestrator: this policy never spawns
 * anything and never runs in parallel. It is a pure decision function over a
 * [DelegationState] the caller threads through one run.
 */
object DelegationPolicy {

    /** No specialist may be nested deeper than this below the Main Agent. */
    const val MAX_DEPTH = 2

    /** A single run may delegate to at most this many specialists in total. */
    const val MAX_TOTAL_SPECIALISTS = 8

    /** One role may be delegated to at most this many times in a run. */
    const val MAX_REPEATS_PER_ROLE = 3

    /** Largest scoped-context string a single delegation may carry, in characters. */
    const val MAX_SCOPED_CONTEXT_CHARS = 8_000

    /** A SIMPLE task is executed by the Main Agent itself, never delegated. */
    fun shouldDelegate(complexity: TaskComplexity): Boolean =
        complexity != TaskComplexity.SIMPLE

    /**
     * The specialist best suited to [task], or null when the Main Agent should
     * handle it directly. Deterministic keyword/intent matching over the fixed
     * role set — never a model call, never a new role.
     */
    fun selectRole(task: String, objective: String? = null): AgentRole? {
        val text = (task + " " + objective.orEmpty()).lowercase()
        if (text.isBlank()) return null

        // Order matters: the most specific intents win over generic ones. Single
        // words match on word boundaries so a substring like "latest" never counts
        // as "test"; multi-word intents match as phrases.
        return when {
            phrase(text, "pull request") || word(text, "commit", "git", "pr", "push", "branch", "stage", "merge") ->
                AgentRole.COMMIT_PR
            word(text, "vulnerability", "vulnerabilities", "vulnerable", "security", "exploit", "injection", "secret", "cve") ||
                phrase(text, "credential leak") ->
                AgentRole.SECURITY_REVIEWER
            word(text, "test", "tests", "testing", "assert", "coverage", "spec", "specs") ||
                phrase(text, "unit test") ->
                AgentRole.TESTER
            word(text, "debug", "crash", "crashes", "exception", "failing", "failed", "reproduce", "repro") ||
                phrase(text, "stack trace", "why is") ->
                AgentRole.DEBUGGER
            word(text, "review", "critique", "lgtm") || phrase(text, "code review", "feedback on") ->
                AgentRole.REVIEWER
            word(text, "document", "documentation", "readme", "docs", "javadoc", "kdoc", "changelog") ->
                AgentRole.DOCS
            word(text, "research", "latest") || phrase(text, "look up", "search the web", "find online", "best practice", "latest version") ->
                AgentRole.RESEARCHER
            word(text, "plan", "roadmap", "strategy") || phrase(text, "break down", "design a", "approach for") ->
                AgentRole.PLANNER
            word(text, "explore", "map", "locate", "trace", "understand") || phrase(text, "where is", "how does") ->
                AgentRole.EXPLORER
            word(text, "quick", "trivial", "rename", "typo", "tweak") || phrase(text, "small edit", "one-line") ->
                AgentRole.FAST_CODER
            word(text, "implement", "add", "fix", "edit", "refactor", "change", "create", "build", "write", "patch") ->
                AgentRole.CODER
            else -> null
        }
    }

    /**
     * Decides whether a delegation to [role] for [task] is allowed given the
     * current [state]. Returns the concrete [DelegationDecision].
     */
    fun evaluate(role: AgentRole, task: String, state: DelegationState): DelegationDecision {
        if (role == AgentRole.MAIN) {
            return DelegationDecision.Reject(DelegationRejection.INVALID_ROLE, "The Main Agent cannot be a specialist")
        }
        if (state.depth >= MAX_DEPTH) {
            return DelegationDecision.Reject(
                DelegationRejection.MAX_DEPTH,
                "Delegation depth ${state.depth} would exceed MAX_DEPTH=$MAX_DEPTH",
            )
        }
        if (state.totalDelegations >= MAX_TOTAL_SPECIALISTS) {
            return DelegationDecision.Reject(
                DelegationRejection.MAX_TOTAL,
                "Already delegated ${state.totalDelegations} times (max $MAX_TOTAL_SPECIALISTS)",
            )
        }
        val roleCount = state.countFor(role)
        if (roleCount >= MAX_REPEATS_PER_ROLE) {
            return DelegationDecision.Reject(
                DelegationRejection.MAX_REPEATS,
                "Role ${role.name} already delegated $roleCount times (max $MAX_REPEATS_PER_ROLE)",
            )
        }
        if (state.alreadySucceeded(role, task)) {
            return DelegationDecision.Reject(
                DelegationRejection.REDUNDANT,
                "Role ${role.name} already completed this task; re-delegating would be redundant",
            )
        }
        // Avoid delegating a role whose input does not exist yet: a reviewer or
        // tester with nothing inspected and nothing changed has no meaningful work.
        val missingInput = missingInputFor(role, state.priorWork)
        if (missingInput != null) {
            return DelegationDecision.Reject(DelegationRejection.MISSING_INPUT, missingInput)
        }
        return DelegationDecision.Allow
    }

    /**
     * Roles that operate on existing work must have something to operate on.
     * Returns a human-readable reason when a role's required input is absent.
     */
    private fun missingInputFor(role: AgentRole, priorWork: PriorWork): String? = when {
        (role == AgentRole.REVIEWER || role == AgentRole.SECURITY_REVIEWER) &&
            priorWork.isEmpty() ->
            "${role.name} needs something to review: no file has been inspected or changed yet"
        role == AgentRole.TESTER && priorWork.changedFiles.isEmpty() && priorWork.inspectedFiles.isEmpty() ->
            "TESTER needs something to test: no change or inspected file exists yet"
        else -> null
    }

    /** True when any [needle] appears in [text] as a whole word (boundary-aware). */
    private fun word(text: String, vararg needles: String): Boolean =
        needles.any { needle ->
            Regex("(^|[^a-z0-9])" + Regex.escape(needle) + "([^a-z0-9]|$)").containsMatchIn(text)
        }

    /** True when any multi-word [needle] phrase appears in [text]. */
    private fun phrase(text: String, vararg needles: String): Boolean =
        needles.any { text.contains(it) }
}

/** Why a delegation was refused. */
enum class DelegationRejection {
    INVALID_ROLE,
    MAX_DEPTH,
    MAX_TOTAL,
    MAX_REPEATS,
    REDUNDANT,

    /** The role's required input (a change to review, a file to test) does not exist yet. */
    MISSING_INPUT,
}

/** The outcome of [DelegationPolicy.evaluate]. */
sealed interface DelegationDecision {
    data object Allow : DelegationDecision
    data class Reject(val reason: DelegationRejection, val message: String) : DelegationDecision

    val isAllowed: Boolean get() = this is Allow
}

/**
 * The delegation accounting for one Main Agent run, threaded through the loop.
 *
 * It is immutable: each recorded delegation returns a new state, so the running
 * loop keeps an honest, replayable history and the policy never mutates shared
 * state behind the caller's back.
 */
data class DelegationState(
    /** How deep the current agent is below the Main Agent (0 = Main). */
    val depth: Int = 0,
    /** Every delegation attempted so far, in order. */
    val records: List<DelegationRecord> = emptyList(),
    /** What work already exists, so precondition checks can avoid empty delegations. */
    val priorWork: PriorWork = PriorWork(),
) {
    val totalDelegations: Int get() = records.size

    fun countFor(role: AgentRole): Int = records.count { it.role == role }

    fun alreadySucceeded(role: AgentRole, task: String): Boolean {
        val key = normalize(task)
        return records.any { it.role == role && it.succeeded && normalize(it.task) == key }
    }

    /** Records a delegation and returns the updated state. */
    fun record(
        role: AgentRole,
        task: String,
        succeeded: Boolean,
        changedFiles: Collection<String> = emptyList(),
        inspectedFiles: Collection<String> = emptyList(),
    ): DelegationState = copy(
        records = records + DelegationRecord(role, task, succeeded),
        priorWork = priorWork.plus(changedFiles, inspectedFiles),
    )

    private fun normalize(task: String): String =
        task.trim().lowercase().replace(Regex("\\s+"), " ")
}

/**
 * The concrete work a run has already produced, used only to decide whether a
 * role's required input exists. Deliberately file-level and coarse: it is not a
 * second context store, just a small gate for the delegation policy.
 */
data class PriorWork(
    val changedFiles: Set<String> = emptySet(),
    val inspectedFiles: Set<String> = emptySet(),
) {
    fun plus(changed: Collection<String>, inspected: Collection<String>): PriorWork =
        PriorWork(changedFiles + changed, inspectedFiles + inspected)

    fun isEmpty(): Boolean = changedFiles.isEmpty() && inspectedFiles.isEmpty()
}

/** One delegation that was attempted during a run. */
data class DelegationRecord(
    val role: AgentRole,
    val task: String,
    val succeeded: Boolean,
)
