package com.agentx.app.ui.ide.state

import com.agentx.app.ui.ide.model.ChatMessageKind
import com.agentx.app.ui.ide.model.ChatMessageUiModel
import com.agentx.app.ui.ide.model.MessageState
import com.agentx.app.ui.ide.model.PlanStepStatus
import com.agentx.app.ui.ide.model.PlanStepUiModel

data class TodoSnapshot(val steps: List<PlanStepUiModel>, val running: Boolean)

/**
 * Picks the checklist to pin above the composer: the newest assistant turn whose
 * plan has at least [MIN_STEPS] steps (the runtime's own one-step "Plan the task"
 * is not a checklist). A finished list from an older turn is dropped so stale,
 * completed work never lingers.
 */
object AgentTodos {
    const val MIN_STEPS = 2

    fun current(messages: List<ChatMessageUiModel>): TodoSnapshot? {
        val assistants = messages.filter { it.kind == ChatMessageKind.ASSISTANT }
        val index = assistants.indexOfLast { it.planSteps.size >= MIN_STEPS }
        if (index < 0) return null
        val source = assistants[index]
        val steps = source.planSteps
        val finished = steps.all { it.status == PlanStepStatus.DONE }
        if (finished && index != assistants.lastIndex) return null
        return TodoSnapshot(steps = steps, running = source.state == MessageState.STREAMING)
    }
}
