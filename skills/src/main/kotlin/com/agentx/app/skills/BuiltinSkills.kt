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

    fun all(): List<SkillDefinition> = listOf(
        ANDROID,
        TESTING,
        DEBUGGING,
        CODE_REVIEW,
        WEB_RESEARCH,
    )
}
