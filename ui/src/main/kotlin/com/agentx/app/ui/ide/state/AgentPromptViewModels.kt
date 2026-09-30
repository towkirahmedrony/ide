package com.agentx.app.ui.ide.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentx.app.agent.catalog.AgentCatalog
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.prompt.DefaultAgentPrompts
import com.agentx.app.agent.prompt.PromptManager
import kotlinx.coroutines.launch

/** One row in the Settings → Agents list. */
data class AgentPromptSummary(
    val role: AgentRole,
    val name: String,
    val description: String,
    val isCustom: Boolean,
    val enabled: Boolean,
)

class AgentPromptsViewModel(
    private val prompts: PromptManager,
) : ViewModel() {

    var agents by mutableStateOf<List<AgentPromptSummary>>(emptyList())
        private set

    var loading by mutableStateOf(true)
        private set

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            loading = true
            agents = AgentRole.entries.map { role ->
                val config = prompts.config(role)
                AgentPromptSummary(
                    role = role,
                    name = AgentCatalog.definition(role).name,
                    description = config.description.orEmpty().ifBlank { DefaultAgentPrompts.describe(role) },
                    isCustom = config.isCustom,
                    enabled = config.enabled,
                )
            }
            loading = false
        }
    }

}

/** Editor state for one agent role's prompt. */
data class AgentPromptEditorState(
    val role: AgentRole,
    val name: String,
    val prompt: String = "",
    val originalPrompt: String = "",
    val isCustom: Boolean = false,
    val enabled: Boolean = true,
    val loading: Boolean = true,
    val saving: Boolean = false,
    val message: String? = null,
) {
    val dirty: Boolean get() = prompt != originalPrompt
}

class AgentPromptEditorViewModel(
    private val prompts: PromptManager,
    private val role: AgentRole,
) : ViewModel() {

    var state by mutableStateOf(
        AgentPromptEditorState(role = role, name = AgentCatalog.definition(role).name),
    )
        private set

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val config = prompts.config(role)
            val text = if (config.isCustom) config.prompt else prompts.defaultText(role)
            state = state.copy(
                prompt = text,
                originalPrompt = text,
                isCustom = config.isCustom,
                enabled = config.enabled,
                loading = false,
            )
        }
    }

    fun edit(value: String) {
        state = state.copy(prompt = value, message = null)
    }

    fun setEnabled(value: Boolean) {
        state = state.copy(enabled = value, message = null)
    }

    fun save() {
        if (state.saving) return
        val text = state.prompt
        if (text.isBlank()) {
            state = state.copy(message = "A prompt cannot be empty. Reset to default instead.")
            return
        }
        state = state.copy(saving = true, message = null)
        viewModelScope.launch {
            prompts.save(role, text, state.enabled)
            val config = prompts.config(role)
            state = state.copy(
                originalPrompt = text,
                isCustom = true,
                enabled = config.enabled,
                saving = false,
                message = "Saved. The ${state.name} will use this prompt.",
            )
        }
    }

    fun reset() {
        viewModelScope.launch {
            prompts.reset(role)
            val text = prompts.defaultText(role)
            state = state.copy(
                prompt = text,
                originalPrompt = text,
                isCustom = false,
                enabled = true,
                message = "Reset to the built-in default.",
            )
        }
    }

    fun discard() {
        viewModelScope.launch {
            val config = prompts.config(role)
            val text = if (config.isCustom) config.prompt else prompts.defaultText(role)
            state = state.copy(prompt = text, originalPrompt = text, isCustom = config.isCustom, message = null)
        }
    }

    fun dismissMessage() {
        state = state.copy(message = null)
    }
}
