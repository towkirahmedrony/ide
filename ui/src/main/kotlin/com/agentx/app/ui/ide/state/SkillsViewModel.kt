package com.agentx.app.ui.ide.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentx.app.skills.SkillDefinition
import com.agentx.app.skills.SkillImportResult
import com.agentx.app.skills.SkillManager
import com.agentx.app.skills.SkillSource
import kotlinx.coroutines.launch

/** Presentation view of one installed skill. */
data class SkillSummary(
    val id: String,
    val name: String,
    val description: String,
    val instructions: String,
    val version: String?,
    val source: SkillSource,
    val enabled: Boolean,
    val roles: Set<String>,
    val valid: Boolean,
    val problems: List<String>,
) {
    val isGlobal: Boolean get() = roles.isEmpty()
    val removable: Boolean get() = source == SkillSource.IMPORTED
}

class SkillsViewModel(
    private val skills: SkillManager,
) : ViewModel() {

    var installed by mutableStateOf<List<SkillSummary>>(emptyList())
        private set

    var loading by mutableStateOf(true)
        private set

    var message by mutableStateOf<String?>(null)
        private set

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            loading = true
            reload()
            loading = false
        }
    }

    fun reloadFromDisk() {
        viewModelScope.launch {
            skills.refresh()
            reload()
            message = "Reloaded skills from disk."
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            skills.setEnabled(id, enabled)
            reload()
        }
    }

    /** Toggles one role assignment; an empty assignment means "all agents". */
    fun toggleRole(id: String, role: String) {
        viewModelScope.launch {
            val current = skills.rolesOf(id).toMutableSet()
            if (role in current) current -= role else current += role
            skills.setRoles(id, current)
            reload()
        }
    }

    fun setGlobal(id: String, global: Boolean) {
        viewModelScope.launch {
            if (global) skills.setRoles(id, emptySet())
            reload()
        }
    }

    fun import(raw: String, fallbackId: String?) {
        viewModelScope.launch {
            when (val result = skills.import(raw, fallbackId)) {
                is SkillImportResult.Imported -> {
                    skills.refresh()
                    reload()
                    message = "Imported '${result.skill.name}'."
                }

                is SkillImportResult.Rejected -> {
                    message = "Import rejected: ${result.reasons.joinToString("; ")}"
                }
            }
        }
    }

    fun remove(id: String) {
        viewModelScope.launch {
            val removed = skills.remove(id)
            reload()
            message = if (removed) "Removed the imported skill." else "This skill cannot be removed."
        }
    }

    fun resetState() {
        viewModelScope.launch {
            skills.resetState()
            reload()
            message = "Reset skill enablement and assignments."
        }
    }

    fun skill(id: String): SkillSummary? = installed.firstOrNull { it.id == id }

    fun dismissMessage() {
        message = null
    }

    private suspend fun reload() {
        installed = skills.installed().map { it.toSummary() }
    }

    private fun SkillDefinition.toSummary(): SkillSummary = SkillSummary(
        id = id,
        name = name,
        description = description,
        instructions = instructions,
        version = version,
        source = source,
        enabled = skills.isEnabled(id),
        roles = skills.rolesOf(id),
        valid = valid,
        problems = problems,
    )
}
