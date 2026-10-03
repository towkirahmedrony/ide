package com.agentx.app.agent.delegation

import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.context.ContextBudget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Part 7 — the deterministic delegation brain: task-complexity classification,
 * capability-aware role selection, per-specialist context budgets, and the
 * authoritative delegation limits. None of this may depend on a model call, so
 * every assertion here is over pure functions.
 */
class DelegationPolicyTest {

    // ───────────────────────── Task complexity ─────────────────────────

    @Test
    fun `a short single action is simple`() {
        assertEquals(TaskComplexity.SIMPLE, TaskComplexityClassifier.classify("read Main.kt"))
        assertEquals(TaskComplexity.SIMPLE, TaskComplexityClassifier.classify("show the imports"))
    }

    @Test
    fun `classification is deterministic for the same input`() {
        val task = "Refactor the auth module and then update every caller across the codebase"
        val first = TaskComplexityClassifier.classify(task)
        val second = TaskComplexityClassifier.classify(task)
        assertEquals(first, second)
    }

    @Test
    fun `multi step cross-cutting work is complex`() {
        val task = "Refactor the session store, then migrate every caller across the whole " +
            "codebase, update AuthRepository.kt, SessionManager.kt and TokenCache.kt, and finally add tests"
        assertEquals(TaskComplexity.COMPLEX, TaskComplexityClassifier.classify(task))
    }

    @Test
    fun `a single focused edit is moderate`() {
        val task = "Add a null check to the login handler in AuthRepository.kt so it stops crashing"
        assertEquals(TaskComplexity.MODERATE, TaskComplexityClassifier.classify(task))
    }

    @Test
    fun `blank task is simple`() {
        assertEquals(TaskComplexity.SIMPLE, TaskComplexityClassifier.classify("   "))
    }

    // ───────────────────────── should delegate ─────────────────────────

    @Test
    fun `simple tasks are handled directly, not delegated`() {
        assertFalse(DelegationPolicy.shouldDelegate(TaskComplexity.SIMPLE))
        assertTrue(DelegationPolicy.shouldDelegate(TaskComplexity.MODERATE))
        assertTrue(DelegationPolicy.shouldDelegate(TaskComplexity.COMPLEX))
    }

    // ───────────────────────── role selection ─────────────────────────

    @Test
    fun `role selection is capability aware`() {
        assertEquals(AgentRole.TESTER, DelegationPolicy.selectRole("write unit tests for the parser"))
        assertEquals(AgentRole.DEBUGGER, DelegationPolicy.selectRole("debug why the login crashes"))
        assertEquals(AgentRole.REVIEWER, DelegationPolicy.selectRole("review this change for correctness"))
        assertEquals(AgentRole.RESEARCHER, DelegationPolicy.selectRole("research the latest version of Ktor"))
        assertEquals(AgentRole.EXPLORER, DelegationPolicy.selectRole("map how the context engine works"))
        assertEquals(AgentRole.COMMIT_PR, DelegationPolicy.selectRole("commit the changes and open a pull request"))
        assertEquals(AgentRole.SECURITY_REVIEWER, DelegationPolicy.selectRole("audit for injection vulnerabilities"))
        assertEquals(AgentRole.DOCS, DelegationPolicy.selectRole("update the readme and changelog"))
        assertEquals(AgentRole.CODER, DelegationPolicy.selectRole("implement the retry logic"))
    }

    @Test
    fun `role selection never returns MAIN and is null when nothing fits`() {
        val role = DelegationPolicy.selectRole("hello there")
        assertTrue(role == null || role != AgentRole.MAIN)
        assertNull(DelegationPolicy.selectRole(""))
    }

    @Test
    fun `a keyword is not matched inside another word`() {
        // "latest" contains the substring "test"; word-boundary matching must not
        // misroute a research request to the TESTER role.
        assertEquals(AgentRole.RESEARCHER, DelegationPolicy.selectRole("what is the latest version of kotlin"))
    }

    // ───────────────────────── delegation limits ─────────────────────────

    @Test
    fun `delegating MAIN is rejected`() {
        val decision = DelegationPolicy.evaluate(AgentRole.MAIN, "x", DelegationState())
        val reject = assertIs<DelegationDecision.Reject>(decision)
        assertEquals(DelegationRejection.INVALID_ROLE, reject.reason)
    }

    @Test
    fun `depth beyond the max is rejected`() {
        val deep = DelegationState(depth = DelegationPolicy.MAX_DEPTH)
        val decision = DelegationPolicy.evaluate(AgentRole.CODER, "edit", deep)
        assertEquals(DelegationRejection.MAX_DEPTH, (decision as DelegationDecision.Reject).reason)
    }

    @Test
    fun `the total specialist count is capped`() {
        var state = DelegationState()
        repeat(DelegationPolicy.MAX_TOTAL_SPECIALISTS) { i ->
            state = state.record(AgentRole.EXPLORER, "task-$i", succeeded = true)
        }
        val decision = DelegationPolicy.evaluate(AgentRole.CODER, "edit", state)
        assertEquals(DelegationRejection.MAX_TOTAL, (decision as DelegationDecision.Reject).reason)
    }

    @Test
    fun `one role cannot be delegated more than its repeat cap`() {
        var state = DelegationState()
        repeat(DelegationPolicy.MAX_REPEATS_PER_ROLE) { i ->
            state = state.record(AgentRole.CODER, "edit-$i", succeeded = false)
        }
        val decision = DelegationPolicy.evaluate(AgentRole.CODER, "edit-again", state)
        assertEquals(DelegationRejection.MAX_REPEATS, (decision as DelegationDecision.Reject).reason)
    }

    @Test
    fun `re-delegating a task a role already completed is redundant`() {
        val state = DelegationState().record(AgentRole.EXPLORER, "Map  the AUTH flow", succeeded = true)
        // Normalized comparison: case and whitespace differences do not matter.
        val decision = DelegationPolicy.evaluate(AgentRole.EXPLORER, "map the auth flow", state)
        assertEquals(DelegationRejection.REDUNDANT, (decision as DelegationDecision.Reject).reason)
    }

    @Test
    fun `a failed task may be retried by the same role`() {
        val state = DelegationState().record(AgentRole.CODER, "fix bug", succeeded = false)
        assertTrue(DelegationPolicy.evaluate(AgentRole.CODER, "fix bug", state).isAllowed)
    }

    @Test
    fun `a fresh delegation within limits is allowed`() {
        assertTrue(DelegationPolicy.evaluate(AgentRole.CODER, "implement feature", DelegationState()).isAllowed)
    }

    @Test
    fun `a reviewer with nothing to review is rejected`() {
        val decision = DelegationPolicy.evaluate(AgentRole.REVIEWER, "review the change", DelegationState())
        assertEquals(DelegationRejection.MISSING_INPUT, (decision as DelegationDecision.Reject).reason)
    }

    @Test
    fun `a reviewer is allowed once something has been inspected`() {
        val state = DelegationState().record(
            AgentRole.EXPLORER,
            "map auth",
            succeeded = true,
            inspectedFiles = listOf("Auth.kt"),
        )
        assertTrue(DelegationPolicy.evaluate(AgentRole.REVIEWER, "review the change", state).isAllowed)
    }

    @Test
    fun `a tester with no change or inspected file is rejected`() {
        val decision = DelegationPolicy.evaluate(AgentRole.TESTER, "run the tests", DelegationState())
        assertEquals(DelegationRejection.MISSING_INPUT, (decision as DelegationDecision.Reject).reason)
    }

    @Test
    fun `a tester is allowed once a change exists`() {
        val state = DelegationState().record(
            AgentRole.CODER,
            "edit auth",
            succeeded = true,
            changedFiles = listOf("Auth.kt"),
        )
        assertTrue(DelegationPolicy.evaluate(AgentRole.TESTER, "run the tests", state).isAllowed)
    }

    @Test
    fun `state records are immutable and accumulate`() {
        val start = DelegationState()
        val next = start.record(AgentRole.CODER, "a", succeeded = true)
        assertEquals(0, start.totalDelegations)
        assertEquals(1, next.totalDelegations)
        assertEquals(1, next.countFor(AgentRole.CODER))
    }

    // ───────────────────────── specialist context budgets ─────────────────────────

    @Test
    fun `main and unknown roles keep the base budget`() {
        val base = ContextBudget.DEFAULT
        assertEquals(base, SpecialistContextBudgets.forRole(AgentRole.MAIN, base))
    }

    @Test
    fun `explorer reads more files but far less conversation than the base`() {
        val base = ContextBudget.DEFAULT
        val explorer = SpecialistContextBudgets.forRole(AgentRole.EXPLORER, base)
        assertTrue(explorer.maxFileCount >= base.maxFileCount)
        assertTrue(explorer.maxConversationMessages < base.maxConversationMessages)
    }

    @Test
    fun `researcher is given almost no repository budget`() {
        val base = ContextBudget.DEFAULT
        val researcher = SpecialistContextBudgets.forRole(AgentRole.RESEARCHER, base)
        assertTrue(researcher.maxFileCount < base.maxFileCount)
    }

    @Test
    fun `coder gets deeper file windows than the base`() {
        val base = ContextBudget.DEFAULT
        val coder = SpecialistContextBudgets.forRole(AgentRole.CODER, base)
        assertTrue(coder.maxFileChars > base.maxFileChars)
    }

    @Test
    fun `every derived budget stays valid`() {
        for (role in AgentRole.entries) {
            val budget = SpecialistContextBudgets.forRole(role, ContextBudget.DEFAULT)
            assertTrue(budget.validate().isEmpty(), "invalid budget for $role: ${budget.validate()}")
        }
    }

    @Test
    fun `tightening the base budget tightens every specialist`() {
        val small = ContextBudget.DEFAULT.copy(maxTotalChars = 8_000, maxFileChars = 2_000)
        val coder = SpecialistContextBudgets.forRole(AgentRole.CODER, small)
        assertTrue(coder.maxTotalChars <= small.maxTotalChars)
    }
}
