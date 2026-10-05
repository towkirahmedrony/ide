package com.agentx.app.context

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The budget must come from the selected model's own capacity. These tests exist to
 * pin that three models with three different windows produce three different
 * budgets, and that an undeclared window is treated as small rather than unlimited.
 */
class ModelContextBudgetTest {

    private val base = ContextBudget.DEFAULT

    @Test
    fun `a large-context model gets a budget derived from its metadata`() {
        val plan = ModelContextBudget.forModel(windowTokens = 200_000, maxOutputTokens = 8_192, overheadChars = 40_000)

        assertTrue(plan.report.windowKnown, "200k was declared, so it is known")
        assertEquals(200_000, plan.report.windowTokens)
        assertEquals(8_192, plan.report.reservedOutputTokens)
        assertEquals(10_000, plan.report.overheadTokens, "40,000 chars is ~10,000 tokens")
        assertTrue(
            plan.budget.maxTotalChars > base.maxTotalChars,
            "a 200k model must not be held to the global default",
        )
        assertTrue(plan.budget.maxTotalChars <= 200_000 * 4, "and must still fit the window")
    }

    @Test
    fun `a small-context model gets a smaller budget and cannot overflow`() {
        val plan = ModelContextBudget.forModel(windowTokens = 8_192, overheadChars = 0)

        assertTrue(
            plan.budget.maxTotalChars < base.maxTotalChars,
            "an 8k model must be given less than the global default",
        )
        assertTrue(
            plan.budget.maxFileChars < base.maxFileChars,
            "one file window must shrink with the model, or a single file could fill the window",
        )
        assertTrue(plan.budget.maxToolResultChars < base.maxToolResultChars)
        assertTrue(plan.budget.maxConversationChars < base.maxConversationChars)
        assertTrue(
            plan.budget.maxTotalChars <= 8_192 * ModelContextBudget.CHARS_PER_TOKEN,
            "the budget must never exceed the window it was derived from",
        )
        assertEquals(
            0,
            plan.budget.validate().size,
            "a derived budget must still be a valid budget: ${plan.budget.validate()}",
        )
    }

    @Test
    fun `three model sizes produce three different budgets`() {
        // The whole point of the phase: the same task, three models, three budgets.
        val large = ModelContextBudget.forModel(windowTokens = 200_000)
        val medium = ModelContextBudget.forModel(windowTokens = 32_000)
        val small = ModelContextBudget.forModel(windowTokens = 8_192)

        val budgets = listOf(large, medium, small).map { it.budget.maxTotalChars }
        assertEquals(budgets.sortedDescending(), budgets, "budget must track the window: $budgets")
        assertEquals(budgets.distinct().size, 3, "each model must get its own number: $budgets")
        assertTrue(small.budget.maxTotalChars < medium.budget.maxTotalChars)
        assertTrue(medium.budget.maxTotalChars < large.budget.maxTotalChars)
    }

    @Test
    fun `an unknown context window is conservative and never unlimited`() {
        val plan = ModelContextBudget.forModel(windowTokens = null)

        assertFalse(plan.report.windowKnown, "nothing declared a window")
        assertEquals(ModelContextBudget.UNKNOWN_CONTEXT_WINDOW_TOKENS, plan.report.windowTokens)
        assertEquals(
            ModelContextBudget.UNKNOWN_CONTEXT_WINDOW_TOKENS * ModelContextBudget.CHARS_PER_TOKEN,
            plan.report.windowTokens * ModelContextBudget.CHARS_PER_TOKEN,
        )
        assertTrue(
            plan.budget.maxTotalChars < base.maxTotalChars,
            "an unknown window must be budgeted below the global default, not above it",
        )
        assertTrue(
            plan.budget.maxTotalChars <= ModelContextBudget.UNKNOWN_CONTEXT_WINDOW_TOKENS * ModelContextBudget.CHARS_PER_TOKEN,
            "unknown must not be treated as unlimited",
        )
    }

    @Test
    fun `an unknown window budgets the same as the conservative default`() {
        val unknown = ModelContextBudget.forModel(windowTokens = null)
        val explicit = ModelContextBudget.forModel(windowTokens = ModelContextBudget.UNKNOWN_CONTEXT_WINDOW_TOKENS)

        assertEquals(explicit.budget.maxTotalChars, unknown.budget.maxTotalChars)
        assertEquals(explicit.report, unknown.report.copy(windowKnown = explicit.report.windowKnown))
    }

    @Test
    fun `reserving output reduces the input budget`() {
        val modest = ModelContextBudget.forModel(windowTokens = 32_000, maxOutputTokens = 2_000)
        val generous = ModelContextBudget.forModel(windowTokens = 32_000, maxOutputTokens = 12_000)

        assertTrue(
            generous.budget.maxTotalChars < modest.budget.maxTotalChars,
            "asking to write more must leave less room to read",
        )
        assertEquals(2_000, modest.report.reservedOutputTokens)
        assertEquals(12_000, generous.report.reservedOutputTokens)
    }

    @Test
    fun `the output reservation never consumes a small window`() {
        // A flat reservation would take most of an 4k window and leave nothing to read.
        val plan = ModelContextBudget.forModel(windowTokens = 4_096, maxOutputTokens = 4_096)

        assertEquals(2_048, plan.report.reservedOutputTokens, "capped at half the window")
        assertTrue(plan.report.availableInputTokens > 0)
    }

    @Test
    fun `tool definitions and the prompt reduce the input budget`() {
        val bare = ModelContextBudget.forModel(windowTokens = 32_000, maxOutputTokens = 4_000)
        val withOverhead = ModelContextBudget.forModel(
            windowTokens = 32_000,
            maxOutputTokens = 4_000,
            overheadChars = 24_000,
        )

        assertEquals(6_000, withOverhead.report.overheadTokens, "24,000 chars is ~6,000 tokens")
        assertTrue(
            withOverhead.budget.maxTotalChars < bare.budget.maxTotalChars,
            "tool schemas and prompts are not free",
        )
        assertTrue(
            withOverhead.report.availableInputTokens < bare.report.availableInputTokens,
        )
    }

    @Test
    fun `a safety margin is always held back`() {
        val plan = ModelContextBudget.forModel(windowTokens = 32_000, maxOutputTokens = 4_000)

        assertTrue(plan.report.safetyMarginTokens > 0, "an estimate must keep a margin")
        val spent = plan.report.reservedOutputTokens + plan.report.overheadTokens +
            plan.report.safetyMarginTokens + plan.report.availableInputTokens
        assertEquals(plan.report.windowTokens, spent, "the arithmetic must add up to the window")
    }

    @Test
    fun `an oversized fixed cost still leaves a usable minimum`() {
        // Degenerate but reachable: a huge prompt on a small window must not produce a
        // zero or negative ceiling that would silently drop the task.
        val plan = ModelContextBudget.forModel(windowTokens = 8_192, overheadChars = 200_000)

        assertTrue(plan.budget.maxTotalChars >= ModelContextBudget.MIN_INPUT_CHARS)
        assertEquals(0, plan.report.availableInputTokens)
        assertEquals(0, plan.budget.validate().size)
    }

    @Test
    fun `the budget is expressed in characters but derived in tokens`() {
        val plan = ModelContextBudget.forModel(windowTokens = 16_000, maxOutputTokens = 4_000)

        assertEquals(ModelContextBudget.CHARS_PER_TOKEN, plan.budget.charsPerToken)
        assertEquals(
            plan.report.availableInputTokens * ModelContextBudget.CHARS_PER_TOKEN,
            plan.report.inputCharBudget,
        )
        assertEquals(plan.report.inputCharBudget, plan.budget.maxTotalChars)
    }

    @Test
    fun `a model with no declared output limit still reserves some`() {
        val plan = ModelContextBudget.forModel(windowTokens = 64_000, maxOutputTokens = null)

        assertEquals(ModelContextBudget.DEFAULT_OUTPUT_RESERVATION_TOKENS, plan.report.reservedOutputTokens)
        assertTrue(plan.report.availableInputTokens > 0)
    }

    @Test
    fun `an invalid conversion ratio is rejected`() {
        val failure = runCatching { ModelContextBudget.forModel(windowTokens = 8_192, charsPerToken = 0) }
        assertTrue(failure.exceptionOrNull() is IllegalArgumentException)
    }
}
