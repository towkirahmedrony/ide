package com.agentx.app.context

import com.agentx.app.skills.SkillDefinition
import com.agentx.app.skills.SkillManager
import com.agentx.app.skills.SkillPriority

/**
 * Why one installed skill is, or is not, part of the skill context.
 *
 * The runtime already decided these outcomes; this enum only makes the decision
 * reportable instead of silent.
 */
enum class SkillContextStatus {
    /** The whole instruction body is in the model request. */
    INCLUDED,

    /** The body was shortened to fit the per-skill character limit. */
    TRUNCATED,

    /** Dropped because the whole skill block would exceed its character ceiling. */
    EXCLUDED_DUE_TO_BUDGET,

    /** Dropped because the skill count (or item count) limit was reached. */
    EXCLUDED_DUE_TO_LIMIT,

    /** Installed but switched off in Settings. */
    DISABLED,

    /** Enabled, but not assigned to this role. */
    NOT_ASSIGNED,

    /**
     * Enabled and assigned, but the user asked for a different set of skills on this
     * message. Selecting skills narrows what the role would normally contribute; it
     * never adds a skill the role was not entitled to.
     */
    NOT_SELECTED,

    /** Failed validation, so it is never offered to any role. */
    INVALID,
}

/** One skill's outcome for one role, with the reason it was decided. */
data class SkillContextEntry(
    val id: String,
    val name: String,
    val status: SkillContextStatus,
    /** Short, deterministic, content-free explanation. */
    val reason: String,
    /** Characters that made it into the request. */
    val keptChars: Int = 0,
    /** Characters the skill's instructions had before any shortening. */
    val originalChars: Int = 0,
) {
    val inRequest: Boolean
        get() = status == SkillContextStatus.INCLUDED || status == SkillContextStatus.TRUNCATED
}

/**
 * The structured skill context an agent role should receive, already bounded by
 * the Context Engine's budget.
 *
 * [items] and [rendered] are what the model sees; [entries] records the outcome
 * of every installed skill, so an excluded skill is always observable rather
 * than silently missing.
 */
data class SkillContext(
    val items: List<ContextItem> = emptyList(),
    /** Final, model-ready rendering. Empty when no skill applies. */
    val rendered: String = "",
    /** Outcome of every installed skill for this role, in selection order. */
    val entries: List<SkillContextEntry> = emptyList(),
    /** Characters the rendered skill block occupies. */
    val usedChars: Int = 0,
) {
    val isEmpty: Boolean get() = items.isEmpty()

    /** Skills whose instructions are in the request. */
    val delivered: List<SkillContextEntry> get() = entries.filter { it.inRequest }

    /** Skills that did not make it in, with the reason they did not. */
    val withheld: List<SkillContextEntry> get() = entries.filterNot { it.inRequest }

    fun count(status: SkillContextStatus): Int = entries.count { it.status == status }

    /**
     * Content-free diagnostics for logging. Skill ids, statuses and sizes are
     * reported; no instruction text is ever included.
     */
    fun diagnosticFields(): Map<String, Any?> = mapOf(
        "skillsInstalled" to entries.size,
        "skillsIncluded" to count(SkillContextStatus.INCLUDED),
        "skillsTruncated" to count(SkillContextStatus.TRUNCATED),
        "skillsExcludedBudget" to count(SkillContextStatus.EXCLUDED_DUE_TO_BUDGET),
        "skillsExcludedLimit" to count(SkillContextStatus.EXCLUDED_DUE_TO_LIMIT),
        "skillsDisabled" to count(SkillContextStatus.DISABLED),
        "skillsNotAssigned" to count(SkillContextStatus.NOT_ASSIGNED),
        "skillsNotSelected" to count(SkillContextStatus.NOT_SELECTED),
        "skillsInvalid" to count(SkillContextStatus.INVALID),
        "skillChars" to usedChars,
        "skillBlockChars" to rendered.length,
        "skillWithheld" to withheld.take(MAX_REPORTED).joinToString(",") { "${it.id}:${it.status.name}" },
    )

    companion object {
        val EMPTY = SkillContext()

        /** Upper bound on how many withheld skills one log line names. */
        private const val MAX_REPORTED = 8
    }
}

/**
 * Resolves the skills for one agent role as structured context.
 *
 * This is the only bridge between the Skills layer and Agent Core: the loop asks
 * for a role's skill context, and receives budgeted [ContextItem]s rendered by
 * the Context Engine. Agent Core never reads skill storage and never appends raw
 * skill strings in more than one place.
 */
interface SkillContextResolver {
    /**
     * @param skillIds the skills the user picked for this message, or null to use
     *   whatever the role would normally contribute. A non-null set narrows the
     *   role's own resolution; it can never widen it, so a skill that is invalid,
     *   disabled or not assigned to the role stays out even when named here.
     */
    suspend fun resolve(
        role: String,
        budget: ContextBudget,
        skillIds: Set<String>? = null,
    ): SkillContext
}

/**
 * Default resolver: selects the enabled skills assigned to the role, turns them
 * into [ContextSource.SKILL] items and lets the engine rank, truncate and render
 * them under [budget]. Skills that do not fit are dropped by the budget, never
 * forced into the model request.
 *
 * Every installed skill is accounted for in [SkillContext.entries]: the ones
 * that reach the model, the ones shortened to fit, and the ones left out with
 * the reason (disabled, not assigned, over the count limit, over the character
 * ceiling). Nothing is dropped without a recorded reason.
 */
class SkillContextProvider(
    private val skills: SkillManager,
    private val engine: ContextEngine,
) : SkillContextResolver {

    override suspend fun resolve(
        role: String,
        budget: ContextBudget,
        skillIds: Set<String>?,
    ): SkillContext {
        val target = role.trim().uppercase()
        val applicable = skills.resolveForAgent(role).associateBy { it.id }
        // Selection only ever narrows: it is applied to the role's own resolution rather than
        // replacing it, so validity, enablement and role assignment keep deciding first.
        val selected = skillIds?.let { ids -> applicable.filterKeys { it in ids } } ?: applicable

        val candidates = mutableListOf<ContextItem>()
        // Keyed by skill id, filled in installed order so the result is stable.
        val entries = LinkedHashMap<String, SkillContextEntry>()

        skills.installed().forEach { skill ->
            val withheldStatus = when {
                !skill.valid -> SkillContextStatus.INVALID
                !skills.isEnabled(skill.id) -> SkillContextStatus.DISABLED
                skill.id !in applicable -> SkillContextStatus.NOT_ASSIGNED
                skill.id !in selected -> SkillContextStatus.NOT_SELECTED
                else -> null
            }
            if (withheldStatus != null) {
                entries[skill.id] = SkillContextEntry(
                    id = skill.id,
                    name = skill.name,
                    status = withheldStatus,
                    reason = withheldReason(withheldStatus, target, skill),
                    originalChars = skill.instructions.length,
                )
                return@forEach
            }

            // Bounded before the engine sees it, so the per-skill shortening is
            // line-aware and says what it dropped instead of cutting blindly.
            val documented = skill.describedBody()
            val bounded = SkillTruncator.truncate(documented, budget.maxSkillChars)
            candidates += skill.toContextItem(target, bounded)
            entries[skill.id] = SkillContextEntry(
                id = skill.id,
                name = skill.name,
                status = if (bounded.truncated) SkillContextStatus.TRUNCATED else SkillContextStatus.INCLUDED,
                reason = when {
                    bounded.truncated ->
                        "instructions shortened to fit the ${budget.maxSkillChars}-character skill limit"

                    skillIds != null -> "selected for this message"

                    else -> "assigned to $target"
                },
                keptChars = bounded.text.length,
                originalChars = documented.length,
            )
        }

        if (candidates.isEmpty()) {
            return SkillContext(entries = entries.values.toList())
        }

        val selection = engine.enforceBudget(candidates, budget.forSkillBlock())
        val exclusions = selection.excluded.associateBy { it.id }
        val accounted = entries.mapValues { (id, entry) ->
            if (!entry.inRequest) return@mapValues entry
            when (val exclusion = exclusions["skill:$id"]) {
                null -> entry
                else -> when (exclusion.reason) {
                    ContextExclusionReason.OVER_CHAR_BUDGET -> entry.copy(
                        status = SkillContextStatus.EXCLUDED_DUE_TO_BUDGET,
                        reason = "skill block would exceed the ${budget.forSkillBlock().charLimit}-character ceiling",
                        keptChars = 0,
                    )

                    else -> entry.copy(
                        status = SkillContextStatus.EXCLUDED_DUE_TO_LIMIT,
                        reason = "skill limit reached (${exclusion.reason.name.lowercase()})",
                        keptChars = 0,
                    )
                }
            }
        }

        return SkillContext(
            items = selection.items,
            rendered = engine.render(selection.items),
            entries = accounted.values.toList(),
            usedChars = selection.usedChars,
        )
    }

    private fun withheldReason(
        status: SkillContextStatus,
        target: String,
        skill: SkillDefinition,
    ): String = when (status) {
        SkillContextStatus.INVALID ->
            "invalid: ${skill.problems.firstOrNull() ?: "failed validation"}"

        SkillContextStatus.DISABLED -> "disabled in Settings"

        SkillContextStatus.NOT_ASSIGNED -> {
            val roles = skill.roles.sorted().joinToString(", ").ifBlank { "no role" }
            "assigned to $roles, not to $target"
        }

        SkillContextStatus.NOT_SELECTED -> "not selected for this message"

        else -> status.name.lowercase()
    }

    /**
     * The skill as it is offered to the model: its name, its one-line description
     * and its instructions. This is the exact text the per-skill limit applies to.
     */
    private fun SkillDefinition.describedBody(): String = buildString {
        append(name)
        if (description.isNotBlank()) append("\n").append(description.trim())
        append("\n\n").append(instructions.trim())
    }

    private fun SkillDefinition.toContextItem(targetRole: String, body: TruncatedContent): ContextItem =
        ContextItem(
            id = "skill:$id",
            source = ContextSource.SKILL,
            content = body.text,
            priority = priority.toContextPriority(),
            relevance = priority.toRelevance(),
            title = name,
            metadata = ContextMetadata(
                reason = "Skill '$name' assigned to $targetRole",
                selectedBecause = ContextReason.SKILL,
                attributes = buildMap {
                    put("skillName", name)
                    put("skillDescription", description)
                    put("skillSource", source.name)
                    put("skillPriority", SkillPriority.nameOf(priority))
                    put("targetAgent", targetRole)
                    put("instructionsChars", instructions.length.toString())
                },
            ),
            truncated = body.truncated,
            originalChars = body.originalChars,
        )

    private fun Int.toContextPriority(): ContextPriority = when {
        this >= SkillPriority.HIGH -> ContextPriority.HIGH
        this <= SkillPriority.LOW -> ContextPriority.LOW
        else -> ContextPriority.NORMAL
    }

    private fun Int.toRelevance(): Double = when {
        this >= SkillPriority.HIGH -> ContextRelevance.SKILL_HIGH
        this <= SkillPriority.LOW -> ContextRelevance.SKILL_LOW
        else -> ContextRelevance.SKILL_NORMAL
    }
}

/**
 * The budget the skill block is measured against.
 *
 * [ContextBudget.maxSkillTotalChars] bounds the block as a whole; a budget that
 * already allows less (a small `maxTotalChars` or a token ceiling) wins, so the
 * skills can never push the system prompt past what the model was configured for.
 */
internal fun ContextBudget.forSkillBlock(): ContextBudget {
    val limit = minOf(charLimit, maxSkillTotalChars)
    if (limit == charLimit) return this
    return copy(maxTotalChars = limit, maxTotalTokens = null)
}

/**
 * Line-aware shortening for skill instructions.
 *
 * A skill is prose; cutting it mid-line produces obviously broken instructions
 * and can split a bullet from its content. This keeps whole lines when a line
 * boundary is reasonably close to the limit and otherwise falls back to the
 * engine's own truncator, and it always states how much was dropped.
 */
object SkillTruncator {

    fun truncate(content: String, maxChars: Int): TruncatedContent {
        if (content.length <= maxChars) return TruncatedContent.whole(content)

        val head = content.take(maxChars)
        val lineBreak = head.lastIndexOf('\n')
        // Only prefer a line break that does not throw away most of the window.
        if (lineBreak < maxChars / 2) return ContextTruncator.truncate(content, maxChars)

        val kept = head.substring(0, lineBreak)
        return TruncatedContent(
            text = kept + marker(kept.length, content.length),
            truncated = true,
            originalChars = content.length,
        )
    }

    private fun marker(kept: Int, total: Int): String =
        "\n…[skill instructions truncated: showed $kept of $total characters]"
}
