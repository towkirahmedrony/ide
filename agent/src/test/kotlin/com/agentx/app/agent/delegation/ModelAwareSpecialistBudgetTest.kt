package com.agentx.app.agent.delegation

import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.domain.AgentStatus
import com.agentx.app.agent.domain.SubAgentRequest
import com.agentx.app.agent.domain.SubAgentResult
import com.agentx.app.context.ContextBudget
import com.agentx.app.context.ModelContextBudget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Where the two halves of this phase meet: role shaping *and* model capacity.
 *
 * The production composition is exactly what is reproduced here — the delegation
 * handler scales the run's base budget by the specialist's role profile
 * ([SpecialistContextBudgets]), and the run itself then bounds that by the capacity
 * of the model the role actually resolved to ([ModelContextBudget]). Neither half
 * alone is enough: role shaping sizes the *shape* of the context, and the model
 * window is the hard ceiling that stops a specialist overflowing a small local
 * model.
 *
 * These tests use the same composition rather than a re-implementation of it, so a
 * change to either rule shows up here.
 */
class ModelAwareSpecialistBudgetTest {

    /** Mirrors what the loop does for a delegated run. */
    private fun specialistBudget(
        role: AgentRole,
        base: ContextBudget,
        windowTokens: Int?,
        overheadChars: Int = 0,
    ): ContextBudget = ModelContextBudget.forModel(
        windowTokens = windowTokens,
        overheadChars = overheadChars,
        base = SpecialistContextBudgets.forRole(role, base),
    ).budget

    private fun mainBudget(windowTokens: Int?, overheadChars: Int = 0): ContextBudget =
        ModelContextBudget.forModel(windowTokens = windowTokens, overheadChars = overheadChars).budget

    // --- K / L. per-role budgets follow the role's own model ----------------

    @Test
    fun `a specialist model sets its own budget, not the main agent's`() {
        // MAIN on a large remote model; CODER on a small local one.
        val main = mainBudget(windowTokens = 200_000)
        val coder = specialistBudget(AgentRole.CODER, ContextBudget.DEFAULT, windowTokens = 8_192)

        assertTrue(
            coder.maxTotalChars < main.maxTotalChars,
            "the specialist must not inherit the main agent's capacity (${coder.maxTotalChars} vs ${main.maxTotalChars})",
        )
        assertTrue(
            coder.maxTotalChars <= 8_192 * ModelContextBudget.CHARS_PER_TOKEN,
            "the specialist budget must fit the specialist's own window",
        )
        assertTrue(
            coder.maxFileChars < main.maxFileChars,
            "a small specialist model needs a smaller file window too",
        )
    }

    @Test
    fun `two specialists on different models get different budgets`() {
        val coder = specialistBudget(AgentRole.CODER, ContextBudget.DEFAULT, windowTokens = 32_000)
        val reviewer = specialistBudget(AgentRole.REVIEWER, ContextBudget.DEFAULT, windowTokens = 8_192)

        assertTrue(
            coder.maxTotalChars > reviewer.maxTotalChars,
            "each specialist is budgeted for the model it resolved to " +
                "(${coder.maxTotalChars} vs ${reviewer.maxTotalChars})",
        )
    }

    @Test
    fun `the same role on a bigger model gets a bigger budget`() {
        val small = specialistBudget(AgentRole.EXPLORER, ContextBudget.DEFAULT, windowTokens = 8_192)
        val large = specialistBudget(AgentRole.EXPLORER, ContextBudget.DEFAULT, windowTokens = 128_000)

        assertTrue(large.maxTotalChars > small.maxTotalChars)
    }

    @Test
    fun `role shaping still applies under a roomy model`() {
        // Model capacity must not flatten the role profiles: an Explorer still reads
        // more files than a Commit/PR agent, whatever window it runs on.
        val explorer = SpecialistContextBudgets.forRole(AgentRole.EXPLORER, ContextBudget.DEFAULT)
        val commitPr = SpecialistContextBudgets.forRole(AgentRole.COMMIT_PR, ContextBudget.DEFAULT)

        assertTrue(explorer.maxFileCount > commitPr.maxFileCount)
        assertTrue(commitPr.maxTotalChars < explorer.maxTotalChars)

        val explorerLarge = specialistBudget(AgentRole.EXPLORER, ContextBudget.DEFAULT, windowTokens = 200_000)
        val commitLarge = specialistBudget(AgentRole.COMMIT_PR, ContextBudget.DEFAULT, windowTokens = 200_000)
        assertTrue(
            explorerLarge.maxTotalChars > commitLarge.maxTotalChars,
            "role shaping must survive a model-derived ceiling",
        )
    }

    @Test
    fun `an unknown specialist window is budgeted conservatively`() {
        val plan = ModelContextBudget.forModel(
            windowTokens = null,
            base = SpecialistContextBudgets.forRole(AgentRole.CODER, ContextBudget.DEFAULT),
        )

        assertFalse(plan.report.windowKnown)
        assertEquals(ModelContextBudget.UNKNOWN_CONTEXT_WINDOW_TOKENS, plan.report.windowTokens)
        assertTrue(plan.report.windowTokens != Int.MAX_VALUE, "unknown is never unlimited")
        assertTrue(plan.budget.maxTotalChars < ContextBudget.DEFAULT.maxTotalChars)
    }

    @Test
    fun `fixed overhead is charged against the specialist's own window`() {
        val bare = specialistBudget(AgentRole.DEBUGGER, ContextBudget.DEFAULT, windowTokens = 16_000)
        val loaded = specialistBudget(
            AgentRole.DEBUGGER,
            ContextBudget.DEFAULT,
            windowTokens = 16_000,
            overheadChars = 30_000,
        )

        assertTrue(
            loaded.maxTotalChars < bare.maxTotalChars,
            "the specialist's own prompt and tool schemas must come out of its window",
        )
    }

    // --- I / J. the delegation contract ------------------------------------

    @Test
    fun `a delegation carries a bounded context package, not a transcript`() {
        // The only channel from parent to specialist is the explicit context package,
        // and it is capped. Nothing here can carry a parent conversation: the type has
        // no such field, which is what makes isolation structural rather than a rule
        // someone has to remember to follow.
        val request = SubAgentRequest(
            role = AgentRole.CODER,
            task = "implement the parser",
            objective = "make tests pass",
            scopedContext = "x".repeat(500),
            parentSessionId = "parent",
            sessionId = "child",
            contextBudget = specialistBudget(AgentRole.CODER, ContextBudget.DEFAULT, windowTokens = 8_192),
        )

        assertEquals(500, request.scopedContext.length)
        assertTrue(
            request.contextBudget.maxTotalChars <= 8_192 * ModelContextBudget.CHARS_PER_TOKEN,
            "the package's own budget must already fit the specialist model",
        )
        assertTrue(DelegationPolicy.MAX_SCOPED_CONTEXT_CHARS > 0)
    }

    @Test
    fun `a specialist result returns a compact summary, not its transcript`() {
        val result = SubAgentResult(
            sessionId = "child",
            role = AgentRole.CODER,
            status = AgentStatus.COMPLETED,
            summary = "Extracted the parser and added a test.",
            findings = listOf("The parser was inlined in the service"),
            filesInspected = listOf("src/Parser.kt"),
            filesChanged = listOf("src/Parser.kt"),
        )

        assertEquals("Extracted the parser and added a test.", result.summary)
        // What crosses back is bounded, structured data. There is deliberately no
        // message list to append to the parent's context.
        assertTrue(result.findings.size <= 1)
        assertTrue(result.errors.isEmpty())

        val asAgentResult = result.toAgentResult()
        assertEquals("Extracted the parser and added a test.", asAgentResult.summary)
        assertTrue(asAgentResult.filesChanged.contains("src/Parser.kt"))
    }

    @Test
    fun `a failed specialist result still returns a structured summary`() {
        // A failed or cancelled delegate must not corrupt the parent: it reports a
        // status and a summary, and carries no partial transcript for the parent to
        // absorb.
        val result = SubAgentResult(
            sessionId = "child",
            role = AgentRole.TESTER,
            status = AgentStatus.FAILED,
            summary = "Sub-agent 'TESTER' exceeded its budget",
        )

        assertEquals(AgentStatus.FAILED, result.status)
        assertEquals("Sub-agent 'TESTER' exceeded its budget", result.summary)
        assertTrue(result.filesChanged.isEmpty())
    }
}
