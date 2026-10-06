package com.agentx.app.ui.ide.state

import com.agentx.app.ui.ide.model.ActivityItemStatus
import com.agentx.app.ui.ide.model.AgentActivityKind
import com.agentx.app.ui.ide.model.AgentActivityUiModel
import com.agentx.app.ui.ide.model.AgentTurnOutcome
import com.agentx.app.ui.ide.model.PlanStepStatus
import com.agentx.app.ui.ide.model.PlanStepUiModel

/** The short, user-facing workflow of one agent turn. */
enum class AgentStage(val label: String) {
    THINKING("Thinking"),
    READING("Reading"),
    PLANNING("Planning"),
    WORKING("Working"),
    VERIFYING("Verifying"),
    DONE("Done"),
}

data class StageProgress(
    val current: AgentStage,
    val reached: List<AgentStage>,
    val action: String?,
    val detail: String?,
) {
    val headline: String
        get() = if (detail.isNullOrBlank()) current.label else "${current.label} · $detail"
}

/**
 * Derives the stage trail purely from the real activity rows and the runtime's
 * own plan. Nothing is invented: a stage only appears once a matching step ran.
 */
object AgentStages {

    private val NOISE_LABELS = setOf(
        "idle", "sending", "thinking", "ai responding", "waiting", "completed",
        "stopped", "error", "permission granted", "permission denied",
    )

    private val VERIFY_REGEX = Regex(
        "(?i)\\b(test|tests|build|gradle|gradlew|lint|compile|assemble|verify|pytest|tsc|detekt|ktlint|jest|check)\\b",
    )

    /** Boilerplate runtime status lines ("Starting Main Agent", "AI responding") are not steps. */
    fun isNoise(activity: AgentActivityUiModel): Boolean {
        if (activity.kind != AgentActivityKind.THINKING) return false
        val label = activity.label.trim().lowercase()
        return label.isEmpty() || label.startsWith("starting") || label in NOISE_LABELS
    }

    fun visibleActivities(activities: List<AgentActivityUiModel>): List<AgentActivityUiModel> =
        activities.filter { !isNoise(it) }

    fun stageOf(activity: AgentActivityUiModel): AgentStage = when (activity.kind) {
        AgentActivityKind.THINKING -> AgentStage.THINKING
        AgentActivityKind.FILE_READ, AgentActivityKind.SEARCH -> AgentStage.READING
        AgentActivityKind.FILE_WRITE -> AgentStage.WORKING
        AgentActivityKind.TERMINAL ->
            if (VERIFY_REGEX.containsMatchIn(activity.label)) AgentStage.VERIFYING else AgentStage.WORKING
        AgentActivityKind.SUB_AGENT -> stageOfRole(activity.role)
        AgentActivityKind.WAITING -> AgentStage.THINKING
        AgentActivityKind.ERROR -> AgentStage.WORKING
        AgentActivityKind.TOOL ->
            if (activity.toolName.orEmpty().lowercase().startsWith("git_")) AgentStage.READING else AgentStage.WORKING
    }

    private fun stageOfRole(role: String?): AgentStage {
        val r = role.orEmpty().lowercase()
        return when {
            r.contains("plan") -> AgentStage.PLANNING
            r.contains("explor") || r.contains("research") -> AgentStage.READING
            r.contains("review") || r.contains("test") -> AgentStage.VERIFYING
            else -> AgentStage.WORKING
        }
    }

    fun progress(
        activities: List<AgentActivityUiModel>,
        planSteps: List<PlanStepUiModel>,
        outcome: AgentTurnOutcome,
    ): StageProgress {
        val real = visibleActivities(activities)
        val reached = sortedSetOf<AgentStage>()
        reached.add(AgentStage.THINKING)
        if (planSteps.size > 1) reached.add(AgentStage.PLANNING)
        real.forEach { reached.add(stageOf(it)) }

        val active = real.lastOrNull { it.status == ActivityItemStatus.ACTIVE }
        val current = when (outcome) {
            AgentTurnOutcome.SUCCESS -> AgentStage.DONE
            AgentTurnOutcome.RUNNING -> when {
                active != null -> stageOf(active)
                real.isEmpty() && planSteps.size > 1 &&
                    planSteps.any { it.status == PlanStepStatus.ACTIVE } -> AgentStage.PLANNING
                else -> AgentStage.THINKING
            }
            else -> real.lastOrNull()?.let { stageOf(it) } ?: AgentStage.THINKING
        }
        reached.add(current)

        return StageProgress(
            current = current,
            reached = reached.toList(),
            action = if (outcome == AgentTurnOutcome.RUNNING) active?.let { describe(it) } else null,
            detail = if (outcome == AgentTurnOutcome.RUNNING) detailFor(current, real) else null,
        )
    }

    private fun detailFor(stage: AgentStage, real: List<AgentActivityUiModel>): String? = when (stage) {
        AgentStage.READING -> {
            val files = real.count { it.kind == AgentActivityKind.FILE_READ }
            val searches = real.count { it.kind == AgentActivityKind.SEARCH }
            when {
                files > 0 -> count(files, "file")
                searches > 0 -> count(searches, "search", "searches")
                else -> null
            }
        }
        AgentStage.WORKING -> {
            val edits = real.count { it.kind == AgentActivityKind.FILE_WRITE }
            val commands = real.count { it.kind == AgentActivityKind.TERMINAL }
            when {
                edits > 0 -> "${count(edits, "file")} changed"
                commands > 0 -> count(commands, "command")
                else -> null
            }
        }
        AgentStage.VERIFYING -> "changes"
        else -> null
    }

    /** One-line sentence for what the agent is doing right now. */
    fun describe(activity: AgentActivityUiModel): String {
        val subject = activity.detail?.takeIf { it.isNotBlank() }
        return when (activity.kind) {
            AgentActivityKind.FILE_READ -> subject?.let { "Reading $it" } ?: "Reading file"
            AgentActivityKind.SEARCH -> subject?.let { "Searching $it" } ?: "Searching files"
            AgentActivityKind.FILE_WRITE -> subject?.let { "Editing $it" } ?: "Editing files"
            AgentActivityKind.TERMINAL -> subject?.let { "Running $it" } ?: "Running command"
            AgentActivityKind.SUB_AGENT -> "${roleName(activity.role)} · ${activity.label}"
            else -> activity.label
        }
    }

    /** `Read 4 files · 2 edits · ran 1 command` for a finished turn. */
    fun summary(real: List<AgentActivityUiModel>): String {
        val reads = real.count { it.kind == AgentActivityKind.FILE_READ }
        val searches = real.count { it.kind == AgentActivityKind.SEARCH }
        val edits = real.count { it.kind == AgentActivityKind.FILE_WRITE }
        val commands = real.count { it.kind == AgentActivityKind.TERMINAL }
        val parts = mutableListOf<String>()
        if (reads > 0) parts += "Read ${count(reads, "file")}"
        if (searches > 0) parts += count(searches, "search", "searches")
        if (edits > 0) parts += count(edits, "edit")
        if (commands > 0) parts += "ran ${count(commands, "command")}"
        return parts.joinToString(" · ")
    }

    fun roleName(role: String?): String {
        val clean = role.orEmpty().trim().lowercase().replace('_', ' ')
        return if (clean.isEmpty()) "Agent" else clean.replaceFirstChar { it.uppercase() }
    }

    /** A turn that has ended can never keep a spinning row. */
    fun settle(
        activity: AgentActivityUiModel,
        outcome: AgentTurnOutcome,
        waiting: Boolean,
    ): AgentActivityUiModel {
        if (activity.status != ActivityItemStatus.ACTIVE) return activity
        return when {
            waiting -> activity.copy(status = ActivityItemStatus.PENDING)
            outcome == AgentTurnOutcome.RUNNING -> activity
            outcome == AgentTurnOutcome.SUCCESS -> activity.copy(status = ActivityItemStatus.DONE)
            outcome == AgentTurnOutcome.FAILED -> activity.copy(status = ActivityItemStatus.FAILED)
            else -> activity.copy(status = ActivityItemStatus.PENDING)
        }
    }

    fun settlePlan(
        steps: List<PlanStepUiModel>,
        outcome: AgentTurnOutcome,
        waiting: Boolean,
    ): List<PlanStepUiModel> = steps.map { step ->
        if (step.status != PlanStepStatus.ACTIVE) {
            step
        } else {
            when {
                waiting -> step.copy(status = PlanStepStatus.PENDING)
                outcome == AgentTurnOutcome.RUNNING -> step
                outcome == AgentTurnOutcome.SUCCESS -> step.copy(status = PlanStepStatus.DONE)
                outcome == AgentTurnOutcome.FAILED -> step.copy(status = PlanStepStatus.FAILED)
                else -> step.copy(status = PlanStepStatus.PENDING)
            }
        }
    }

    private fun count(n: Int, one: String, many: String = one + "s"): String =
        "$n ${if (n == 1) one else many}"
}
