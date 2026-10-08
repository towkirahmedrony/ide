package com.agentx.app.skills

/**
 * Built-in skill catalog.
 *
 * These are the shipped defaults, migrated from the project's original skills
 * layout (android, debugging, testing, review, research). Nothing here is
 * hardcoded into Agent Core: the manager exposes them like any other skill, and
 * the user decides which ones are enabled and for which agents.
 *
 * Built-ins are disabled by default so installing this feature never changes an
 * existing agent's behaviour until the user opts in from Settings.
 *
 * [ANTI_SLOP_DESIGN] is the AgentX-native design-quality filter. Its rules use the
 * `AS-` namespace and are written to stay framework-agnostic, so the same constraints
 * apply to web, Compose, XML and desktop targets.
 */
object BuiltinSkills {

    val ANDROID = SkillDefinition(
        id = "android-development",
        name = "Android Development",
        description = "Android/Kotlin conventions, Gradle layout and the platform constraints of this IDE.",
        instructions = """
            - This is an Android-first project. Prefer Kotlin, coroutines and androidx APIs.
            - Keep changes inside the existing Gradle module layout; do not add a new module for a small feature.
            - Respect minSdk/targetSdk from the module build files; do not raise them casually.
            - A local APK build is not available. Verify with unit tests and CI, not an APK build.
            - Never modify OAuth or signing configuration without an explicit request.
        """.trimIndent(),
        source = SkillSource.BUILTIN,
        roles = setOf("CODER", "DEBUGGER", "REVIEWER", "TESTER"),
        priority = SkillPriority.NORMAL,
    )

    val TESTING = SkillDefinition(
        id = "testing",
        name = "Testing",
        description = "How to add and run focused tests for this repository.",
        instructions = """
            - Prefer focused unit tests near the code under test, following existing test conventions.
            - Kotlin modules use kotlin.test with JUnit Platform; Android modules use kotlin-test-junit.
            - Never claim tests pass without running them (or CI) and reading the output.
            - Cover the failure and edge cases, not only the happy path.
        """.trimIndent(),
        source = SkillSource.BUILTIN,
        roles = setOf("CODER", "TESTER"),
        priority = SkillPriority.NORMAL,
    )

    val DEBUGGING = SkillDefinition(
        id = "debugging",
        name = "Debugging",
        description = "Root-cause a failure before changing code.",
        instructions = """
            - Reproduce or read the exact error before proposing a fix.
            - Identify the smallest failing unit and state the root cause explicitly.
            - Prefer a diagnosis over speculative edits; do not change unrelated code to hide a symptom.
        """.trimIndent(),
        source = SkillSource.BUILTIN,
        roles = setOf("DEBUGGER", "CODER"),
        priority = SkillPriority.HIGH,
    )

    val CODE_REVIEW = SkillDefinition(
        id = "code-review",
        name = "Code Review",
        description = "Read-only review for correctness, regressions and security.",
        instructions = """
            - Review the diff, not the whole repository. Report concrete findings with file and line.
            - Look for correctness bugs, regressions, security issues and missing tests.
            - Do not modify files unless explicitly asked; this is a read-only pass.
        """.trimIndent(),
        source = SkillSource.BUILTIN,
        roles = setOf("REVIEWER"),
        priority = SkillPriority.NORMAL,
    )

    val WEB_RESEARCH = SkillDefinition(
        id = "web-research",
        name = "Web Research",
        description = "Gather and summarise information from documentation sources.",
        instructions = """
            - Use research tools only; do not edit the workspace during research.
            - Summarise findings and cite the sources you used.
            - Prefer primary documentation over secondary summaries.
        """.trimIndent(),
        source = SkillSource.BUILTIN,
        roles = setOf("RESEARCHER"),
        priority = SkillPriority.NORMAL,
    )

    /**
     * The design-quality filter for generated UI.
     *
     * This is a filter, not a style guide: no technique is banned, and a gradient, a
     * card, a corner radius or a dark theme is allowed whenever it serves the product,
     * the hierarchy, the interaction model, the platform or the project's own design
     * direction. What it rejects is technique with no purpose behind it, plus the
     * quality failures that are wrong on every platform (invented content, dead
     * controls, missing states, unverified accessibility claims).
     *
     * Deliberately narrow: every rule carries a stable `AS-` id so a reviewer can cite
     * it, and the body is kept under the per-skill context ceiling in
     * `ContextBudget.DEFAULT_MAX_SKILL_CHARS` so it is injected whole rather than
     * truncated. Platform-specific mechanics belong to a platform profile, and positive
     * visual direction belongs to the project's own design direction, not here.
     */
    val ANTI_SLOP_DESIGN = SkillDefinition(
        id = "anti-slop-design",
        name = "Anti-Slop Design",
        description = "Quality constraints for generated UI: purpose, honesty, states and accessibility.",
        instructions = """
            Anti-Slop: quality constraints for any UI. Not a style guide and not a list of bans: a
            technique is allowed when it serves the product, the hierarchy, the interaction model, the
            platform or the project's design direction. Judge purpose, context, consistency and
            execution, never a technique's name.

            Before building: inspect existing components, tokens, spacing, typography and interaction
            conventions, the target platform, and who this screen is for.

            AS-001 Purpose: every element answers "what does this serve?". Rework or drop what exists
              only because it is a familiar pattern; cut pure decoration.
            AS-002 Hierarchy: decoration must never compete with content or the primary action.
            AS-010 Honesty: never invent metrics, customers, logos, testimonials, reviews, people or
              activity, or product, security or performance claims.
            AS-011 Placeholders must read as placeholders; use real data only when the project supplies
              it. Empty beats fabricated.
            AS-020 Controls: a control performs a real action, says what it does, or is removed;
              navigation leads only to destinations that exist. No fake buttons, navigation, toggles,
              tabs or settings.
            AS-021 States: cover the states this view needs (empty, loading, error, disabled, success,
              selected or active, focus, pressed), where relevant.
            AS-030 Genericness: if it survives swapping the product name, logo, copy and category
              unchanged, reconsider it; composition follows this product's content, not a template.
            AS-040 Consistency: reuse existing components, tokens and conventions; do not add a second
              visual language without a reason.
            AS-050 Accessibility is correctness: readable contrast, no meaning carried by color alone,
              operability without a pointer where the platform offers one. Never claim a contrast ratio
              was verified unless it was computed.
            AS-060 Platform: follow the target platform's conventions; do not carry web patterns into
              native UI, or the reverse, without a reason.
            AS-070 Guardrail: a pattern is a problem when unexplained generic signals cluster, not when
              one is used with a reason. Never reject a gradient, radius, card, blur, dark mode, fonts,
              icons or a palette as such.

            Then re-read your work against these constraints, fix what fails, and finish. Keep this
            guidance internal; do not print it to the user.
        """.trimIndent(),
        source = SkillSource.BUILTIN,
        roles = setOf("MAIN", "PLANNER", "CODER", "FAST_CODER", "REVIEWER"),
        priority = SkillPriority.HIGH,
    )

    fun all(): List<SkillDefinition> = listOf(
        ANDROID,
        TESTING,
        DEBUGGING,
        CODE_REVIEW,
        WEB_RESEARCH,
        ANTI_SLOP_DESIGN,
    )
}
