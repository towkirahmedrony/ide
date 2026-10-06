package com.agentx.app.ui.ide.state

import com.agentx.app.ui.ide.model.AgentActivityKind
import com.agentx.app.ui.ide.model.AgentActivityUiModel

/** One piece of an assistant turn, in the order it really happened. */
sealed interface TurnSegment {
    data class Text(val text: String) : TurnSegment

    data class Steps(val id: String, val steps: List<AgentActivityUiModel>) : TurnSegment
}

object AgentTurnSegments {

    /** Runtime status lines (thinking/waiting) are not steps the user needs to see. */
    fun isStep(activity: AgentActivityUiModel): Boolean =
        activity.kind != AgentActivityKind.THINKING && activity.kind != AgentActivityKind.WAITING

    /**
     * Interleaves the message text with its tool steps using each step's
     * [AgentActivityUiModel.textOffset]. Steps with no text between them join one
     * group. Steps without an offset (older saved sessions) come first.
     */
    fun build(rawText: String, activities: List<AgentActivityUiModel>): List<TurnSegment> {
        val out = mutableListOf<TurnSegment>()
        var cursor = 0
        var group = mutableListOf<AgentActivityUiModel>()

        for (step in activities.filter { isStep(it) }) {
            val offset = step.textOffset.coerceIn(0, rawText.length).coerceAtLeast(cursor)
            if (offset > cursor) {
                val slice = rawText.substring(cursor, offset)
                if (slice.isNotBlank()) {
                    if (group.isNotEmpty()) {
                        out.add(TurnSegment.Steps(group.first().id, group.toList()))
                        group = mutableListOf()
                    }
                    out.add(TurnSegment.Text(slice.trim()))
                    cursor = offset
                }
            }
            group.add(step)
        }
        if (group.isNotEmpty()) out.add(TurnSegment.Steps(group.first().id, group.toList()))

        val tail = rawText.substring(cursor)
        if (tail.isNotBlank()) out.add(TurnSegment.Text(tail.trim()))
        return out
    }

    /** `Searched files, ran command` for a collapsed group. */
    fun summary(steps: List<AgentActivityUiModel>): String {
        val reads = steps.count { it.kind == AgentActivityKind.FILE_READ }
        val edits = steps.count { it.kind == AgentActivityKind.FILE_WRITE }
        val commands = steps.count { it.kind == AgentActivityKind.TERMINAL }
        val phrases = linkedSetOf<String>()
        steps.forEach { step ->
            when (step.kind) {
                AgentActivityKind.FILE_READ -> phrases.add(if (reads == 1) "read file" else "read files")
                AgentActivityKind.SEARCH -> phrases.add("searched files")
                AgentActivityKind.FILE_WRITE -> phrases.add(if (edits == 1) "edited file" else "edited files")
                AgentActivityKind.TERMINAL -> phrases.add(if (commands == 1) "ran command" else "ran commands")
                AgentActivityKind.SUB_AGENT ->
                    phrases.add("delegated to ${AgentStages.roleName(step.role).lowercase()}")
                AgentActivityKind.TOOL -> phrases.add("used tools")
                AgentActivityKind.ERROR -> phrases.add("hit an error")
                else -> Unit
            }
        }
        if (phrases.isEmpty()) return "Worked"
        val text = phrases.take(3).joinToString(", ") + if (phrases.size > 3) "…" else ""
        return text.replaceFirstChar { it.uppercase() }
    }
}
