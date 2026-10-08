package com.agentx.app.context

/**
 * The project's own design direction.
 *
 * `DESIGN.md` describes what *this particular product* should look like: its
 * visual identity, brand personality, audience, typography and colour direction,
 * density, shape language, layout and interaction principles, accessibility
 * priorities, responsiveness expectations, and the conventions that must be
 * preserved. It is the positive half of design intent; the universal half — what
 * a UI must not become regardless of the product — is the `anti-slop-design`
 * skill, and the two are deliberately separate.
 *
 * ## What this is not
 *
 * - **Not a skill.** A skill carries instructions assigned to a role. This is a
 *   project's own data, delivered as reference context, and it must never be able
 *   to override a role, a permission, a tool grant, a safety constraint or a
 *   universal constraint.
 * - **Not configuration.** The file is read as plain text. Nothing in it is
 *   executed, interpreted as frontmatter, HTML or a script, or treated as a
 *   setting. Instruction-shaped text inside it stays prose.
 * - **Not required.** A project without one behaves exactly as it did before this
 *   existed. Nothing creates the file.
 * - **Not unbounded.** Its size is bounded by [ContextBudget.maxDesignChars], and
 *   an oversized file is truncated (and says so) rather than admitted whole.
 */
object ProjectDesign {

    /** The file, relative to the project root. */
    const val FILE_NAME: String = "DESIGN.md"

    /** The heading the design direction is introduced under in a prompt. */
    const val HEADING: String = "# Project Design Context"

    /**
     * The roles that can meaningfully create or judge UI.
     *
     * Only these receive the direction. The block is project-wide prose, so
     * handing it to an explorer or a docs agent would spend the budget those
     * roles need for their own work without changing what they do.
     */
    val UI_ROLES: Set<String> = setOf("MAIN", "PLANNER", "CODER", "FAST_CODER", "REVIEWER")
}

/**
 * Why the project design direction is, or is not, part of a prompt.
 *
 * The loader always reports one of these instead of failing silently, so "why did
 * this run not see the design direction?" is answerable from the outcome rather
 * than by re-reading the project.
 */
enum class DesignContextStatus {
    /** The role does not create or judge UI, so the direction was never read. */
    NOT_APPLICABLE,

    /** No project is open, or the project has no `DESIGN.md`. */
    ABSENT,

    /** The file exists but holds no text. */
    EMPTY,

    /** The file could not be read; the run continues without it. */
    UNREADABLE,

    /** The file was read but did not fit the context budget. */
    EXCLUDED_DUE_TO_BUDGET,

    /** The whole direction is in the request. */
    INCLUDED,

    /** The direction was shortened to fit its character limit. */
    TRUNCATED,
}

/**
 * The structured project design context for one role, already bounded by the
 * Context Engine's budget.
 *
 * [items] and [rendered] are what the model sees. [status] and [reason] record
 * what happened, so a missing direction is observable rather than silent.
 */
data class DesignContext(
    val items: List<ContextItem> = emptyList(),
    /** Final, model-ready rendering. Empty when there is no direction to show. */
    val rendered: String = "",
    val status: DesignContextStatus = DesignContextStatus.NOT_APPLICABLE,
    /** Short, deterministic, content-free explanation. */
    val reason: String = "",
    /** Workspace-relative path of the file, when one was read. */
    val path: String? = null,
    /** Characters the file held before any shortening. */
    val originalChars: Int = 0,
    /** Characters that made it into the request. */
    val keptChars: Int = 0,
) {
    val isEmpty: Boolean get() = items.isEmpty()

    /** True when the direction reaches the model. */
    val inRequest: Boolean
        get() = status == DesignContextStatus.INCLUDED || status == DesignContextStatus.TRUNCATED

    /**
     * Content-free diagnostics for logging. The status, the path and the sizes are
     * reported; no line of the project's design prose is ever included.
     */
    fun diagnosticFields(): Map<String, Any?> = mapOf(
        "designStatus" to status.name,
        "designPath" to path,
        "designChars" to keptChars,
        "designOriginalChars" to originalChars,
        "designBlockChars" to rendered.length,
    )

    companion object {
        /** No design direction, for the reason the caller records. */
        val NONE = DesignContext()
    }
}

/**
 * Resolves the project's design direction for one agent role.
 *
 * This is the only bridge between `DESIGN.md` and Agent Core: the loop asks for a
 * role's design context and receives budgeted [ContextItem]s rendered by the
 * Context Engine. Agent Core never reads project storage directly.
 */
interface DesignContextResolver {
    /**
     * @return the direction for [role], or a [DesignContext] whose status explains
     *   why there is none. Never throws for a missing, empty or unreadable file.
     */
    suspend fun resolve(role: String, budget: ContextBudget): DesignContext
}

/**
 * Default resolver: reads `DESIGN.md` from the open project's root through the
 * Workspace Runtime, and hands it to the Context Engine as a
 * [ContextSource.DESIGN] item so it is ranked, bounded and rendered like any
 * other context.
 *
 * Deliberate properties:
 *
 * - **The project root is the only place it looks.** The path is a single
 *   workspace-relative name; the workspace filesystem resolves it against the
 *   open project and rejects absolute paths and `..` traversal, so the file cannot
 *   be reached from outside the project.
 * - **One reader.** The file goes through [WorkspaceFileContextLoader], so the
 *   protected-path check, secret redaction, line-aware truncation and the read
 *   cache all apply exactly as they do to any other file.
 * - **Bounded twice.** The loader truncates to [ContextBudget.maxDesignChars],
 *   and the engine still enforces the request's total ceiling.
 * - **Fail-soft.** A workspace backend that throws, a missing file, a blank file
 *   and an over-budget file all produce a status, never an exception.
 */
class ProjectDesignContextResolver(
    private val workspace: WorkspaceContextProvider,
    private val engine: ContextEngine,
    private val roles: Set<String> = ProjectDesign.UI_ROLES,
    private val loader: WorkspaceFileContextLoader = WorkspaceFileContextLoader(WorkspaceFileCache()),
) : DesignContextResolver {

    override suspend fun resolve(role: String, budget: ContextBudget): DesignContext {
        val target = role.trim().uppercase()
        if (target !in roles) {
            return DesignContext(
                status = DesignContextStatus.NOT_APPLICABLE,
                reason = "$target does not create or review UI",
            )
        }

        val fileSystem = runCatching { workspace.fileSystem() }.getOrNull()
            ?: return DesignContext(
                status = DesignContextStatus.ABSENT,
                reason = "no project is open",
            )

        val load = runCatching {
            loader.loadFile(
                fileSystem = fileSystem,
                // Part of the cache key: two open projects must never share one
                // cached DESIGN.md.
                workspaceId = runCatching { workspace.snapshot()?.workspaceId }.getOrNull(),
                path = ProjectDesign.FILE_NAME,
                reason = ContextReason.PROJECT_DESIGN,
                priority = ContextPriority.NORMAL,
                relevance = ContextRelevance.PROJECT_DESIGN,
                maxChars = budget.maxDesignChars,
            )
        }.getOrNull() ?: return DesignContext(
            status = DesignContextStatus.UNREADABLE,
            reason = "the project design file could not be read",
            path = ProjectDesign.FILE_NAME,
        )

        val loaded = when (load) {
            is ContextLoad.Rejected -> return DesignContext(
                status = when (load.exclusion.reason) {
                    ContextExclusionReason.NOT_FOUND -> DesignContextStatus.ABSENT
                    ContextExclusionReason.EMPTY -> DesignContextStatus.EMPTY
                    else -> DesignContextStatus.UNREADABLE
                },
                reason = load.exclusion.detail ?: load.exclusion.reason.name.lowercase(),
                path = load.exclusion.path ?: ProjectDesign.FILE_NAME,
            )

            is ContextLoad.Loaded -> load.item
        }

        // Re-sourced from FILE: the same text, but ranked and reported as the
        // project's design direction rather than as one more file.
        val design = loaded.copy(
            id = "design:$DESIGN_PATH",
            source = ContextSource.DESIGN,
            priority = ContextPriority.NORMAL,
            relevance = ContextRelevance.PROJECT_DESIGN,
            path = DESIGN_PATH,
            title = ProjectDesign.FILE_NAME,
            metadata = loaded.metadata.copy(
                reason = "Project design direction from ${ProjectDesign.FILE_NAME}",
                selectedBecause = ContextReason.PROJECT_DESIGN,
            ),
        )

        val selection = engine.enforceBudget(listOf(design), budget)
        val kept = selection.items.singleOrNull() ?: return DesignContext(
            status = DesignContextStatus.EXCLUDED_DUE_TO_BUDGET,
            reason = selection.excluded.firstOrNull()?.reason?.name?.lowercase()
                ?: "did not fit the context budget",
            path = DESIGN_PATH,
            originalChars = design.originalChars,
        )

        return DesignContext(
            items = listOf(kept),
            rendered = engine.render(listOf(kept)),
            status = if (kept.truncated) {
                DesignContextStatus.TRUNCATED
            } else {
                DesignContextStatus.INCLUDED
            },
            reason = if (kept.truncated) {
                "shortened to fit the ${budget.maxDesignChars}-character design limit"
            } else {
                "read from the project root"
            },
            path = DESIGN_PATH,
            originalChars = design.originalChars,
            keptChars = kept.chars,
        )
    }

    private companion object {
        /** The workspace-relative path the project design direction is read from. */
        const val DESIGN_PATH = ProjectDesign.FILE_NAME
    }
}
