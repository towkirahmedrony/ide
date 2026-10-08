package com.agentx.app.agent.delegation

import com.agentx.app.agent.domain.AgentRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 4 — the deterministic, model-free UI task classifier and the planning
 * policy built on it.
 *
 * Every assertion here is over pure functions, proving the classification is
 * cheap (no model call, no filesystem access) and reproducible, that a backend
 * task mentioning a UI class is not mistaken for UI design, and that ambiguous
 * requests fall back to the existing non-UI behaviour.
 */
class UiTaskClassifierTest {

    // ───────────────────────── 1. non-UI ─────────────────────────

    @Test
    fun `ordinary backend and tooling tasks are not UI`() {
        val tasks = listOf(
            "fix the database query used by the user profile loader",
            "implement an API endpoint for password reset",
            "fix authentication so the token is refreshed",
            "refactor the repository layer to remove duplication",
            "optimize the parser so large files load faster",
            "fix a crash in the networking layer",
            "write a background worker that drains the queue",
            "update CI to run the unit tests on every push",
            "fix the Gradle configuration for the release build",
        )
        for (task in tasks) {
            assertEquals(UiTaskClass.NON_UI, UiTaskClassifier.classify(task), task)
        }
    }

    // ───────────────────────── 2. simple UI ─────────────────────────

    @Test
    fun `a localized UI fix is simple`() {
        assertEquals(UiTaskClass.UI_SIMPLE, UiTaskClassifier.classify("change the button text on the profile screen"))
        assertEquals(UiTaskClass.UI_SIMPLE, UiTaskClassifier.classify("fix one padding issue in the settings screen"))
        assertEquals(UiTaskClass.UI_SIMPLE, UiTaskClassifier.classify("adjust one existing color in the theme"))
        assertEquals(UiTaskClass.UI_SIMPLE, UiTaskClassifier.classify("change the spacing on the checkout page"))
        assertEquals(
            UiTaskClass.UI_SIMPLE,
            UiTaskClassifier.classify("fix a clearly localized Compose UI bug in the badge"),
        )
    }

    // ───────────────────────── 3. design ─────────────────────────

    @Test
    fun `a redesign request is design work`() {
        assertEquals(
            UiTaskClass.UI_DESIGN,
            UiTaskClassifier.classify("Redesign the SettingsScreen to make the hierarchy clearer and more distinctive."),
        )
        assertEquals(UiTaskClass.UI_DESIGN, UiTaskClassifier.classify("improve the visual hierarchy of the home screen"))
        assertEquals(UiTaskClass.UI_DESIGN, UiTaskClassifier.classify("make the interface more distinctive"))
        assertEquals(UiTaskClass.UI_DESIGN, UiTaskClassifier.classify("implement a responsive navigation layout"))
    }

    // ───────────────────────── 4. new screen/page ─────────────────────────

    @Test
    fun `a new screen or page request is design work`() {
        assertEquals(UiTaskClass.UI_DESIGN, UiTaskClassifier.classify("create a new settings screen"))
        assertEquals(UiTaskClass.UI_DESIGN, UiTaskClassifier.classify("build a landing page for the product"))
        assertEquals(UiTaskClass.UI_DESIGN, UiTaskClassifier.classify("create a portfolio page"))
        assertEquals(UiTaskClass.UI_DESIGN, UiTaskClassifier.classify("build an onboarding flow"))
        assertEquals(UiTaskClass.UI_DESIGN, UiTaskClassifier.classify("create a Compose screen for the profile"))
        assertEquals(UiTaskClass.UI_DESIGN, UiTaskClassifier.classify("design a mobile app screen for checkout"))
    }

    // ───────────────────────── 5. complex UI ─────────────────────────

    @Test
    fun `a large multi-surface UI request is complex`() {
        assertEquals(
            UiTaskClass.UI_COMPLEX,
            UiTaskClassifier.classify("Create an entire dashboard with sidebar navigation and multiple screens"),
        )
        assertEquals(UiTaskClass.UI_COMPLEX, UiTaskClassifier.classify("build a full landing page for the marketing site"))
        assertEquals(
            UiTaskClass.UI_COMPLEX,
            UiTaskClassifier.classify("redesign the whole application surface across every screen"),
        )
        assertEquals(UiTaskClass.UI_COMPLEX, UiTaskClassifier.classify("build a new navigation and layout system"))
    }

    // ───────────────────────── 6. UI name, backend task ─────────────────────────

    @Test
    fun `a backend task that mentions a UI class is not UI design`() {
        // The camelCase class name is one token, but even a spaced-out mention is
        // dominated by the data work and must stay on the existing path.
        assertEquals(
            UiTaskClass.NON_UI,
            UiTaskClassifier.classify("Fix the SQL query used by the SettingsScreen repository."),
        )
        assertEquals(
            UiTaskClass.NON_UI,
            UiTaskClassifier.classify("Fix the SQL query used by the Settings screen repository."),
        )
        assertEquals(
            UiTaskClass.NON_UI,
            UiTaskClassifier.classify("optimize the database view model that backs the dashboard screen"),
        )
    }

    // ───────────────────────── 7. ambiguity is safe ─────────────────────────

    @Test
    fun `an ambiguous request keeps the existing behaviour`() {
        assertEquals(UiTaskClass.NON_UI, UiTaskClassifier.classify("look into the login issue"))
        assertEquals(UiTaskClass.NON_UI, UiTaskClassifier.classify("make the app faster"))
        assertEquals(UiTaskClass.NON_UI, UiTaskClassifier.classify("clean up the messy module"))
        assertEquals(UiTaskClass.NON_UI, UiTaskClassifier.classify("   "))
        assertEquals(UiTaskClass.NON_UI, UiTaskClassifier.classify("hello there"))
    }

    @Test
    fun `an intent word alone is not enough to make a task UI`() {
        assertEquals(UiTaskClass.NON_UI, UiTaskClassifier.classify("redesign the API contract"))
        assertEquals(UiTaskClass.NON_UI, UiTaskClassifier.classify("modernize the build pipeline"))
    }

    @Test
    fun `classification is deterministic and cheap`() {
        val task = "Redesign the settings screen to make the hierarchy clearer"
        val first = UiTaskClassifier.classify(task)
        val second = UiTaskClassifier.classify(task)
        assertEquals(first, second)
        assertEquals(UiTaskClass.UI_DESIGN, first)
    }

    // ───────────────────────── planning policy ─────────────────────────

    @Test
    fun `only design classifications require planning`() {
        assertFalse(UiTaskClass.NON_UI.requiresPlanning)
        assertFalse(UiTaskClass.UI_SIMPLE.requiresPlanning)
        assertTrue(UiTaskClass.UI_DESIGN.requiresPlanning)
        assertTrue(UiTaskClass.UI_COMPLEX.requiresPlanning)
    }

    @Test
    fun `implementation roles are gated until a usable plan exists`() {
        // Simple and non-UI work never needs a plan.
        assertNull(UiPlanningPolicy.gate(UiTaskClass.NON_UI, AgentRole.CODER, hasUsablePlan = false))
        assertNull(UiPlanningPolicy.gate(UiTaskClass.UI_SIMPLE, AgentRole.CODER, hasUsablePlan = false))

        // Design work blocks implementation without a plan...
        assertTrue(UiPlanningPolicy.gate(UiTaskClass.UI_DESIGN, AgentRole.CODER, hasUsablePlan = false) != null)
        assertTrue(UiPlanningPolicy.gate(UiTaskClass.UI_COMPLEX, AgentRole.FAST_CODER, hasUsablePlan = false) != null)

        // ...and allows it once one exists.
        assertNull(UiPlanningPolicy.gate(UiTaskClass.UI_DESIGN, AgentRole.CODER, hasUsablePlan = true))
        assertNull(UiPlanningPolicy.gate(UiTaskClass.UI_COMPLEX, AgentRole.FAST_CODER, hasUsablePlan = true))
    }

    @Test
    fun `non-implementation roles are never blocked by the planning gate`() {
        for (role in listOf(AgentRole.PLANNER, AgentRole.EXPLORER, AgentRole.REVIEWER, AgentRole.RESEARCHER)) {
            assertNull(
                UiPlanningPolicy.gate(UiTaskClass.UI_COMPLEX, role, hasUsablePlan = false),
                role.name,
            )
        }
    }

    @Test
    fun `implementation and review roles receive the plan, others do not`() {
        assertTrue(UiPlanningPolicy.shouldReceivePlan(UiTaskClass.UI_DESIGN, AgentRole.CODER))
        assertTrue(UiPlanningPolicy.shouldReceivePlan(UiTaskClass.UI_DESIGN, AgentRole.FAST_CODER))
        assertTrue(UiPlanningPolicy.shouldReceivePlan(UiTaskClass.UI_COMPLEX, AgentRole.REVIEWER))
        // No plan is flowing on a non-UI or simple task.
        assertFalse(UiPlanningPolicy.shouldReceivePlan(UiTaskClass.NON_UI, AgentRole.CODER))
        assertFalse(UiPlanningPolicy.shouldReceivePlan(UiTaskClass.UI_SIMPLE, AgentRole.CODER))
        // The planner never receives its own output.
        assertFalse(UiPlanningPolicy.shouldReceivePlan(UiTaskClass.UI_DESIGN, AgentRole.PLANNER))
    }

    @Test
    fun `the phase contract distinguishes planning, implementation and review`() {
        assertEquals(AgentPhase.PLANNING, AgentPhase.of(AgentRole.PLANNER))
        assertEquals(AgentPhase.IMPLEMENTATION, AgentPhase.of(AgentRole.CODER))
        assertEquals(AgentPhase.IMPLEMENTATION, AgentPhase.of(AgentRole.FAST_CODER))
        assertEquals(AgentPhase.REVIEW, AgentPhase.of(AgentRole.REVIEWER))
        assertEquals(AgentPhase.REVIEW, AgentPhase.of(AgentRole.SECURITY_REVIEWER))
        assertEquals(AgentPhase.OTHER, AgentPhase.of(AgentRole.EXPLORER))
    }

    // ───────────────────────── delegation state ─────────────────────────

    @Test
    fun `a failed or empty planner never yields a usable plan`() {
        val failed = DelegationState().record(AgentRole.PLANNER, "plan it", succeeded = false)
        assertFalse(failed.hasUsablePlan())

        val empty = DelegationState().record(AgentRole.PLANNER, "plan it", succeeded = true)
        assertFalse(empty.hasUsablePlan(), "a successful but plan-less state must not count as planned")

        val planned = empty.withPlan("Hero, features, footer. Reuse the existing theme.")
        assertTrue(planned.hasUsablePlan())
        assertTrue(planned.record(AgentRole.CODER, "edit", succeeded = true).hasUsablePlan())
    }
}
