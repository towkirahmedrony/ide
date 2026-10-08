package com.agentx.app.agent.delegation

import com.agentx.app.agent.domain.AgentRole

/**
 * How much a task is about user-interface and design work, and how much planning
 * it therefore deserves.
 *
 * Deliberately coarse and separate from [TaskComplexity]: a task can be *complex*
 * and completely non-visual (a parser rewrite), or *simple* and still a UI change
 * (one padding fix). Collapsing the two would either push backend work through a
 * design planner or let a redesign bypass planning, which is exactly the failure
 * this phase exists to prevent.
 */
enum class UiTaskClass {
    /** Not UI/design work: existing routing applies unchanged. */
    NON_UI,

    /** A small, localized UI change that planning would only slow down. */
    UI_SIMPLE,

    /** UI/design work that benefits from a design plan before implementation. */
    UI_DESIGN,

    /** Large or cross-cutting UI work that requires a design plan. */
    UI_COMPLEX,
    ;

    /**
     * True when the Main Agent must obtain a design plan before it may implement
     * through CODER/FAST_CODER. Simple UI fixes flow to the implementation role
     * directly; non-UI work is untouched.
     */
    val requiresPlanning: Boolean
        get() = this == UI_DESIGN || this == UI_COMPLEX
}

/**
 * Coarse phase of the anti-slop pipeline a role belongs to. This is a small,
 * stable internal contract so planning, implementation and review results can be
 * told apart without a new result schema: the role already carries the identity,
 * this only names the phase.
 */
enum class AgentPhase {
    PLANNING,
    IMPLEMENTATION,
    REVIEW,
    OTHER;

    companion object {
        fun of(role: AgentRole): AgentPhase = when (role) {
            AgentRole.PLANNER -> PLANNING
            AgentRole.CODER, AgentRole.FAST_CODER -> IMPLEMENTATION
            AgentRole.REVIEWER, AgentRole.SECURITY_REVIEWER -> REVIEW
            else -> OTHER
        }
    }
}

/**
 * Deterministic, model-free UI task classification.
 *
 * The whole point is that the Main Agent can ask "is this UI design work that
 * needs a plan?" **without spending a model call** and get the same answer twice.
 * It reads only the task text and the optional objective — information the current
 * turn already holds — so it never scans the filesystem and never talks to a
 * provider.
 *
 * ## Why it is not a keyword blacklist
 *
 * A task may name a UI artefact without being UI work at all
 * ("fix the SQL query used by the Settings screen repository"). So the classifier
 * weighs two opposing evidence sets and lets the *whole request* decide:
 *
 * - **UI evidence** — domain nouns (screen, layout, dashboard, theme, …) and
 *   design intent (redesign, visual hierarchy, responsive, "make it more
 *   distinctive") or the creation of a substantial new surface.
 * - **Non-UI evidence** — data/server/infrastructure terms (sql, query, api,
 *   repository, gradle, parser, crash, …).
 *
 * A task with no UI noun and no design intent is [UiTaskClass.NON_UI] regardless
 * of anything else. When UI evidence exists but technical terms dominate and no
 * design intent is present, the task stays [UiTaskClass.NON_UI] — that is what
 * keeps a backend fix that happens to mention a screen on the existing path.
 * Breadth markers (entire, multiple, across, system) promote a design task from
 * [UiTaskClass.UI_DESIGN] to [UiTaskClass.UI_COMPLEX].
 */
object UiTaskClassifier {

    /** Nouns that name a user-interface artefact or a visual property. */
    private val UI_NOUNS: Set<String> = setOf(
        "ui", "ux", "interface", "screen", "screens", "page", "pages", "layout", "layouts",
        "component", "components", "dashboard", "landing", "onboarding", "navigation",
        "navbar", "sidebar", "menu", "dialog", "modal", "form", "forms", "button", "buttons",
        "header", "footer", "card", "cards", "widget", "widgets", "view", "views", "theme",
        "themes", "style", "styles", "styling", "typography", "font", "fonts", "color",
        "colors", "colour", "colours", "palette", "spacing", "padding", "margin", "hierarchy",
        "icon", "icons", "banner", "hero", "toolbar", "tab", "tabs", "snackbar", "sheet",
        "portfolio", "visual", "brand", "branding", "mockup", "wireframe", "animation",
        "animations", "compose", "grid", "wizard", "flow", "flows",
    )

    /** Verbs and adjectives that express deliberate design intent. */
    private val DESIGN_INTENTS: Set<String> = setOf(
        "redesign", "redesigned", "restyle", "restyled", "modernize", "modernise", "beautify",
        "polish", "distinctive", "intuitive", "responsive", "adaptive", "cleaner", "elegant",
        "attractive", "reimagine", "overhaul", "refresh", "simplify", "visual",
    )

    /** Multi-word design-intent phrases. */
    private val DESIGN_PHRASES: List<String> = listOf(
        "look and feel", "visual hierarchy", "information hierarchy", "user experience",
        "ui design", "ux design", "design a", "design the", "make it look", "make the ui",
        "more distinctive", "improve the layout", "improve the visual", "responsive layout",
    )

    /** Verbs that, with a substantial surface noun, create a new UI surface. */
    private val SURFACE_VERBS: Set<String> = setOf(
        "create", "build", "implement", "add", "design", "make", "introduce", "develop",
    )

    /** Substantial surfaces: creating one is design work, not a localized tweak. */
    private val SURFACE_NOUNS: Set<String> = setOf(
        "screen", "screens", "page", "pages", "dashboard", "landing", "flow", "flows",
        "wizard", "onboarding", "navigation", "layout", "layouts", "portfolio", "interface",
        "application", "system", "sheet",
    )

    /** Breadth markers that promote a design task to complex. */
    private val COMPLEX_MARKERS: Set<String> = setOf(
        "entire", "whole", "full", "complete", "multiple", "multi", "all", "system", "across",
        "everywhere", "major", "comprehensive", "big", "application",
    )

    /** Multi-word breadth phrases. */
    private val COMPLEX_PHRASES: List<String> = listOf(
        "multi-screen", "multi screen", "end-to-end", "end to end", "application flow",
        "layout system", "navigation system", "design system", "full landing", "whole app",
    )

    /** Terms that indicate data, server, tooling or infrastructure work. */
    private val NON_UI_TERMS: Set<String> = setOf(
        "database", "db", "sql", "query", "queries", "migration", "migrations", "schema",
        "index", "endpoint", "endpoints", "api", "apis", "server", "backend", "authentication",
        "authorization", "auth", "token", "tokens", "session", "repository", "repositories",
        "repo", "repos", "parser", "parsing", "worker", "workers", "queue", "queues", "ci",
        "pipeline", "pipelines", "gradle", "compiler", "compile", "kernel", "terminal",
        "buffer", "socket", "networking", "network", "crash", "crashes", "exception",
        "concurrency", "thread", "threads", "cache", "algorithm", "deploy", "deployment",
        "benchmark", "throughput", "latency", "handler", "handlers", "logic", "service",
        "daemon", "render", "renderer",
    )

    /**
     * Strong data/server/infrastructure terms that dominate a localized UI mention
     * with no design intent at all: a task that says "optimize the database query
     * that backs the dashboard screen" is backend work, however many UI nouns it
     * happens to name. Design intent always overrides this set.
     */
    private val STRONG_NON_UI_TERMS: Set<String> = setOf(
        "database", "db", "sql", "query", "queries", "migration", "migrations", "schema",
        "endpoint", "endpoints", "api", "apis", "server", "backend", "authentication",
        "authorization", "parser", "parsing", "worker", "workers", "queue", "queues", "ci",
        "pipeline", "pipelines", "gradle", "compiler", "compile", "kernel", "daemon",
        "algorithm", "cache", "repo", "repos",
    )

    private val NON_ALNUM = Regex("[^a-z0-9]+")

    fun classify(task: String, objective: String? = null): UiTaskClass {
        val text = buildString {
            append(task.trim())
            if (!objective.isNullOrBlank()) {
                append(' ')
                append(objective.trim())
            }
        }
        if (text.isBlank()) return UiTaskClass.NON_UI

        val lower = text.lowercase()
        val words = lower.split(NON_ALNUM).filter { it.isNotEmpty() }.toSet()

        val uiNouns = UI_NOUNS.count { it in words }
        val intentHits = DESIGN_INTENTS.count { it in words } +
            DESIGN_PHRASES.count { lower.contains(it) }
        val nonUiHits = NON_UI_TERMS.count { it in words }
        val surfaceCreation = SURFACE_VERBS.any { it in words } && SURFACE_NOUNS.any { it in words }
        val complex = COMPLEX_MARKERS.any { it in words } || COMPLEX_PHRASES.any { lower.contains(it) }

        val uiScore = uiNouns * 2 + intentHits * 3 + if (surfaceCreation) 3 else 0
        if (uiScore == 0) return UiTaskClass.NON_UI

        val hasDesignIntent = intentHits > 0 || surfaceCreation
        // No UI noun at all, only a stray intent word ("redesign the API"): not UI.
        if (uiNouns == 0 && nonUiHits > 0) return UiTaskClass.NON_UI
        // A strong data/server term with no design intent dominates, however many UI
        // nouns the request happens to name.
        if (!hasDesignIntent && STRONG_NON_UI_TERMS.any { it in words }) return UiTaskClass.NON_UI
        // Technical terms dominate a localized UI mention (the Settings-screen repo fix).
        if (!hasDesignIntent && nonUiHits >= uiNouns) return UiTaskClass.NON_UI
        if (hasDesignIntent && nonUiHits >= uiNouns + 3) return UiTaskClass.NON_UI

        return when {
            hasDesignIntent && complex -> UiTaskClass.UI_COMPLEX
            hasDesignIntent -> UiTaskClass.UI_DESIGN
            else -> UiTaskClass.UI_SIMPLE
        }
    }
}

/**
 * The deterministic policy that turns a [UiTaskClass] into routing behaviour.
 *
 * It is a pure decision layer over the existing delegation machinery: it does not
 * run anything and does not introduce a parallel orchestrator. It answers only:
 *
 * - does this task require a plan before implementation? ([requiresPlanner])
 * - which roles are implementation roles gated on that plan? ([isImplementationRole])
 * - should a delegation carry the plan from a previous planner step? ([shouldReceivePlan])
 * - may [role] run yet for [ui], given whether a usable plan exists? ([gate])
 */
object UiPlanningPolicy {

    fun requiresPlanner(ui: UiTaskClass): Boolean = ui.requiresPlanning

    /** Roles that perform the change the plan describes. */
    fun isImplementationRole(role: AgentRole): Boolean =
        role == AgentRole.CODER || role == AgentRole.FAST_CODER

    /** Roles that judge the change and therefore need the plan too. */
    fun isReviewRole(role: AgentRole): Boolean =
        role == AgentRole.REVIEWER || role == AgentRole.SECURITY_REVIEWER

    /**
     * True when a delegated [role] should receive the planner's result as part of
     * its scoped context. Only applies to design tasks that required planning; the
     * planner itself never needs its own output echoed back.
     */
    fun shouldReceivePlan(ui: UiTaskClass, role: AgentRole): Boolean =
        requiresPlanner(ui) && (isImplementationRole(role) || isReviewRole(role))

    /**
     * The planning gate a delegation must pass.
     *
     * Returns null when the delegation may proceed. Returns a short, deterministic
     * reason when a design task's implementation role is asked to run before a
     * usable plan exists — including after a planner that failed or produced no
     * text, so a failed plan is never silently treated as a successful one.
     */
    fun gate(ui: UiTaskClass, role: AgentRole, hasUsablePlan: Boolean): String? {
        if (!requiresPlanner(ui)) return null
        if (!isImplementationRole(role)) return null
        if (hasUsablePlan) return null
        return "This is '${ui.name}' work: it must be planned before implementation. " +
            "Delegate to ${AgentRole.PLANNER.name} first, then delegate the implementation " +
            "once a design plan exists."
    }
}
