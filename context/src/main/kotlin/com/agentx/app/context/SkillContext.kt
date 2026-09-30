package com.agentx.app.context

import com.agentx.app.skills.SkillDefinition
import com.agentx.app.skills.SkillManager
import com.agentx.app.skills.SkillPriority

/**
 * The structured skill context an agent role should receive, already bounded by
 * the Context Engine's budget.
 */
data class SkillContext(
    val items: List<ContextItem> = emptyList(),
    /** Final, model-ready rendering. Empty when no skill applies. */
    val rendered: String = "",
) {
    val isEmpty: Boolean get() = items.isEmpty()

    companion object {
        val EMPTY = SkillContext()
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
    suspend fun resolve(role: String, budget: ContextBudget): SkillContext
}

/**
 * Default resolver: selects the enabled skills assigned to the role, turns them
 * into [ContextSource.SKILL] items and lets the engine rank, truncate and render
 * them under [budget]. Skills that do not fit are dropped by the budget, never
 * forced into the model request.
 */
class SkillContextProvider(
    private val skills: SkillManager,
    private val engine: ContextEngine,
) : SkillContextResolver {

    override suspend fun resolve(role: String, budget: ContextBudget): SkillContext {
        val applicable = skills.resolveForAgent(role)
        if (applicable.isEmpty()) return SkillContext.EMPTY
        val items = applicable.map { it.toContextItem(role) }
        val selection = engine.enforceBudget(items, budget)
        return SkillContext(
            items = selection.items,
            rendered = engine.render(selection.items),
        )
    }

    private fun SkillDefinition.toContextItem(targetRole: String): ContextItem {
        val body = buildString {
            append(name)
            if (description.isNotBlank()) append("\n").append(description.trim())
            append("\n\n").append(instructions.trim())
        }
        return ContextItem(
            id = "skill:$id",
            source = ContextSource.SKILL,
            content = body,
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
        )
    }

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
