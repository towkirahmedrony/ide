package com.agentx.app.context

/**
 * The platforms this build ships guidance for.
 *
 * Deliberately coarse. A profile describes a *platform*, not a framework, so
 * React, Next.js, Flutter and SwiftUI all live under Web or their native platform
 * and need no new profile. Adding one means adding an enum entry, a
 * [PlatformProfile] and a detection signal — nothing in Agent Core changes.
 */
enum class PlatformProfileId(val displayName: String) {
    WEB("Web"),
    ANDROID_COMPOSE("Android (Compose)"),
    ANDROID_XML("Android (XML/Views)"),
    DESKTOP("Desktop"),
}

/**
 * One platform's implementation and UX conventions.
 *
 * This is AgentX-authored, compiled-in data — never read from a project file, so
 * nothing a project contains can define or replace it. It complements the other
 * two layers and replaces neither:
 *
 * | Layer | Answers |
 * |---|---|
 * | `anti-slop-design` skill | what must this *not* become, in every project |
 * | project design direction (`DESIGN.md`) | what should *this* product look like |
 * | platform profile | how is this *platform* best built and used |
 *
 * It states conventions, never bans. "Choose a navigation appropriate to the
 * information architecture and the platform" — not "always use a bottom bar";
 * "follow the project's existing design system" — not "always use Material 3".
 */
data class PlatformProfile(
    val id: PlatformProfileId,
    val instructions: String,
) {
    /** The heading this profile is introduced under in a prompt. */
    val heading: String get() = "$HEADING_PREFIX: ${id.displayName}"

    companion object {
        /** Stable prefix, so a prompt can be checked for exactly one profile block. */
        const val HEADING_PREFIX: String = "# Platform Profile"
    }
}

/**
 * The platform profiles this build ships, as data.
 *
 * Adding a platform is a profile plus a detection signal in [PlatformDetector];
 * the resolver, the budget, the prompt and the diagnostics path are untouched.
 */
object PlatformProfiles {

    val WEB: PlatformProfile = PlatformProfile(
        id = PlatformProfileId.WEB,
        instructions = """
            - Structure the page with the semantics the content actually has: headings in order, lists as lists, landmark regions, labelled controls, and links for navigation versus buttons for actions. Reach for the platform's own element before building a custom one, and give a custom control the role and keyboard behaviour its native counterpart has.
            - Design the narrow layout as its own layout, not a squeezed desktop one, and give the middle of the width range a design as well. Content must reflow rather than overflow, and nothing may become unreachable at any width.
            - Every interactive element must be reachable and operable by keyboard, in a sensible order, with a focus indicator the user can see. Do not remove focus styling without replacing it. Treat hover as an enhancement, never the only way to reach or understand something. Touch targets need enough size and spacing for a finger.
            - Give each form field a visible label, put an error next to the field it concerns, and keep what the user typed when a submit fails. Placeholder text is not a label.
            - Every data view needs empty, loading and error states that say what happened and what happens next.
            - Choose navigation from the information architecture, the available space and how often each destination is used. There is no default pattern to paste in.
            - Respect reduced-motion preferences, keep motion attached to a stated purpose, and never carry meaning in colour alone.
        """.trimIndent(),
    )

    val ANDROID_COMPOSE: PlatformProfile = PlatformProfile(
        id = PlatformProfileId.ANDROID_COMPOSE,
        instructions = """
            - Follow the project's existing design system. When it has components, tokens and a theme, use them; Material is a sensible default for a new project and not a mandate for an existing one.
            - Build screens from state: a composable renders a state and reports events upward. Keep data loading, business rules and navigation decisions out of the composable, and hold each piece of state in one place so it survives recomposition and configuration changes.
            - Make layouts adaptive. Design for the window sizes the app really runs in and let content reflow, rather than scaling one phone layout up. Respect insets so system bars and the on-screen keyboard never cover content or controls, and keep the focused field visible.
            - Give every data-backed screen its loading, empty, error and success states, and make the error say what to do next.
            - Touch targets need enough size and spacing, and every action must be reachable without precise pointing. Add content descriptions that name the action or the information, and mark decorative elements as such.
            - Take type and spacing from the theme so hierarchy stays consistent, and support both light and dark themes.
            - Check content that varies: long text, large font scales and dynamic values must not clip, truncate silently or break the layout.
        """.trimIndent(),
    )

    val ANDROID_XML: PlatformProfile = PlatformProfile(
        id = PlatformProfileId.ANDROID_XML,
        instructions = """
            - Keep the view hierarchy shallow and purposeful. Group by information hierarchy, not by convenience, and prefer a small number of well-chosen containers over deeply nested wrappers.
            - Set a content description on every meaningful image, icon and custom control that names the action or the information rather than the widget, and mark decorative nodes so assistive technology skips them.
            - Respect the minimum touch target size, and leave enough space between adjacent targets.
            - Handle configuration changes and process death: state that must survive belongs in saved state or a view model, not in a view field or an activity property.
            - Continue the existing widget and binding approach. Do not introduce another UI toolkit or a custom view where the project's own conventions already cover the case.
            - Use density-independent units and resource qualifiers so the layout is correct across densities, sizes and orientations, and keep user-facing text in string resources.
            - Give long lists a view that recycles, and give long content exactly one scroll container in a direction: nested scrolling in the same direction fights the user.
            - Handle input properly: the keyboard must not cover the focused field, and focus order and IME actions should follow the form.
            - Provide loading, empty and error states for any data-backed view.
        """.trimIndent(),
    )

    val DESKTOP: PlatformProfile = PlatformProfile(
        id = PlatformProfileId.DESKTOP,
        instructions = """
            - Design for a window that can be resized and can be small. Content should reflow as the window changes, and a minimum size is a decision to make deliberately rather than an accident to discover.
            - Treat the keyboard as a first-class input: full navigation without a pointer, a visible focus indicator, a sensible tab order, and shortcuts for frequent actions. Hover states are an enhancement, never the only affordance.
            - Higher information density suits this platform. Prefer scannable tables and lists and avoid spreading a small amount of content across a large window.
            - Use menus, toolbars and dialogs by convention: menus for the command set, toolbars for frequent actions, dialogs for decisions that genuinely interrupt. Long-running work belongs in progress the user can see, not in a blocking dialog.
            - Make scrolling and selection predictable, and keep the primary action reachable without scrolling in a typical window.
            - Follow the platform's conventions for window controls, shortcuts and text selection, and support text scaling without clipping.
            - Provide empty, loading and error states, and never leave a view blank while work is in progress.
        """.trimIndent(),
    )

    /** Every shipped profile, in a stable order. */
    val ALL: List<PlatformProfile> = listOf(WEB, ANDROID_COMPOSE, ANDROID_XML, DESKTOP)

    fun forId(id: PlatformProfileId): PlatformProfile = when (id) {
        PlatformProfileId.WEB -> WEB
        PlatformProfileId.ANDROID_COMPOSE -> ANDROID_COMPOSE
        PlatformProfileId.ANDROID_XML -> ANDROID_XML
        PlatformProfileId.DESKTOP -> DESKTOP
    }
}

/**
 * Why a platform profile is, or is not, part of a prompt.
 *
 * Mirrors [DesignContextStatus] so both prompt-side layers report the same way.
 */
enum class PlatformProfileStatus {
    /** The role does not create or judge UI, so no platform was looked for. */
    NOT_APPLICABLE,

    /** The project shows no platform signal this build recognises. */
    NOT_DETECTED,

    /** The project could be two platforms at once, so no profile was chosen. */
    AMBIGUOUS,

    /** The project could not be inspected; the run continues without a profile. */
    UNREADABLE,

    /** A profile was chosen but did not fit the context budget. */
    EXCLUDED_DUE_TO_BUDGET,

    /** The whole profile is in the request. */
    INCLUDED,

    /** The profile was shortened to fit its character limit. */
    TRUNCATED,
}

/**
 * The resolved platform profile for one role, already bounded by the Context
 * Engine's budget.
 *
 * [profileId] and [evidence] record what was decided and why, so a missing or
 * surprising profile is diagnosable without reproducing the workspace.
 */
data class PlatformProfileContext(
    val profileId: PlatformProfileId? = null,
    val items: List<ContextItem> = emptyList(),
    /** Final, model-ready rendering, heading included. Empty when there is none. */
    val rendered: String = "",
    val status: PlatformProfileStatus = PlatformProfileStatus.NOT_APPLICABLE,
    /** Short, deterministic, content-free explanation. */
    val reason: String = "",
    /** The signals that drove the decision: file names and marker tokens only. */
    val evidence: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = items.isEmpty()

    /** True when a profile reaches the model. */
    val inRequest: Boolean
        get() = status == PlatformProfileStatus.INCLUDED || status == PlatformProfileStatus.TRUNCATED

    /**
     * Content-free diagnostics. File names and marker tokens may appear; no file
     * content and no profile prose does.
     */
    fun diagnosticFields(): Map<String, Any?> = mapOf(
        "platformStatus" to status.name,
        "platformProfile" to profileId?.name,
        "platformChars" to rendered.length,
        "platformEvidence" to evidence.joinToString(",").takeIf { it.isNotEmpty() },
    )

    companion object {
        /** No platform profile, for the reason the resolver records. */
        val NONE = PlatformProfileContext()
    }
}

/**
 * Resolves the platform profile for one agent role.
 *
 * The Agent Core asks for a role and receives a budgeted, rendered block; it never
 * inspects the project itself. Details for the profile are deliberately unspecified
 * in the prompt builder, so a new platform needs no change there.
 */
interface PlatformProfileResolver {
    /**
     * @return the profile for [role], or a [PlatformProfileContext] whose status
     *   explains why there is none. Never throws for a missing or unreadable project.
     */
    suspend fun resolve(role: String, budget: ContextBudget): PlatformProfileContext
}

/**
 * Default resolver: probe the open project, detect its platform, and hand the
 * matching AgentX profile to the Context Engine as a [ContextSource.PLATFORM] item.
 *
 * Deliberate properties:
 *
 * - **Deterministic.** Selection depends only on paths, directory listings and a
 *   bounded read of build files — no model call, no content classification.
 * - **Bounded.** One probe listing a fixed set of directories and reading a fixed
 *   number of marker files; then the usual per-item and total budget accounting.
 * - **No guess.** No signal, or two contradictory strong signals, means no profile.
 * - **Fail-soft.** A workspace backend that throws, an unlistable project or an
 *   over-budget profile all produce a status, never an exception.
 * - **Project files choose, they do not define.** Marker contents only pick
 *   between profiles AgentX ships; nothing from the project reaches the prompt.
 */
class ProjectPlatformProfileResolver(
    private val workspace: WorkspaceContextProvider,
    private val engine: ContextEngine,
    private val roles: Set<String> = UiRoles.ALL,
    private val probe: ProjectPlatformProbe = ProjectPlatformProbe(),
) : PlatformProfileResolver {

    override suspend fun resolve(role: String, budget: ContextBudget): PlatformProfileContext {
        val target = role.trim().uppercase()
        if (target !in roles) {
            return PlatformProfileContext(
                status = PlatformProfileStatus.NOT_APPLICABLE,
                reason = "$target does not create or review UI",
            )
        }

        val fileSystem = runCatching { workspace.fileSystem() }.getOrNull()
            ?: return PlatformProfileContext(
                status = PlatformProfileStatus.NOT_DETECTED,
                reason = "no project is open",
            )

        val knownFiles = runCatching { workspace.snapshot() }.getOrNull()?.let { snapshot ->
            (snapshot.openFiles + snapshot.recentFiles).distinct()
        }.orEmpty()

        val evidence = runCatching { probe.collect(fileSystem, knownFiles) }.getOrNull()
            ?: return PlatformProfileContext(
                status = PlatformProfileStatus.UNREADABLE,
                reason = "the project could not be inspected",
            )

        val detection = PlatformDetector.detect(evidence)
        val profile = detection.platform?.let { PlatformProfiles.forId(it) }
            ?: return PlatformProfileContext(
                profileId = null,
                status = if (detection.ambiguous) {
                    PlatformProfileStatus.AMBIGUOUS
                } else {
                    PlatformProfileStatus.NOT_DETECTED
                },
                reason = detection.reason,
                evidence = detection.evidence,
            )

        val item = ContextItem(
            id = "platform:${profile.id.name.lowercase()}",
            source = ContextSource.PLATFORM,
            content = render(profile),
            priority = ContextPriority.NORMAL,
            relevance = ContextRelevance.PLATFORM_PROFILE,
            title = profile.id.displayName,
            metadata = ContextMetadata(
                reason = "Platform profile for ${profile.id.displayName}",
                selectedBecause = ContextReason.PLATFORM_PROFILE,
                attributes = mapOf(
                    "platform" to profile.id.name,
                    "evidence" to detection.evidence.joinToString(", "),
                ),
            ),
        )

        val selection = engine.enforceBudget(listOf(item), budget)
        val kept = selection.items.singleOrNull() ?: return PlatformProfileContext(
            profileId = profile.id,
            status = PlatformProfileStatus.EXCLUDED_DUE_TO_BUDGET,
            reason = selection.excluded.firstOrNull()?.reason?.name?.lowercase()
                ?: "did not fit the context budget",
            evidence = detection.evidence,
        )

        return PlatformProfileContext(
            profileId = profile.id,
            items = listOf(kept),
            rendered = kept.content,
            status = if (kept.truncated) {
                PlatformProfileStatus.TRUNCATED
            } else {
                PlatformProfileStatus.INCLUDED
            },
            reason = detection.reason,
            evidence = detection.evidence,
        )
    }

    /**
     * Composes the model-ready block: a heading that names the platform, the
     * precedence rule, then the conventions. Platform guidance is the most general
     * of the three UI layers, so it says in its own text that the project's design
     * direction and the rules above come first.
     */
    private fun render(profile: PlatformProfile): String = buildString {
        append(profile.heading).append('\n')
        append(PROFILE_LEAD_IN)
        append("\n\n")
        append(profile.instructions.trim())
    }

    private companion object {
        const val PROFILE_LEAD_IN: String =
            "Implementation and UX conventions for this target platform. Use them as " +
                "guidance, not as style direction: the project's own design direction and " +
                "the rules above take precedence, and where the project already has a " +
                "deliberate design system, follow it."
    }
}
