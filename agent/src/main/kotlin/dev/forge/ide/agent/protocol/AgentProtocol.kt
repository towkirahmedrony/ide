package dev.forge.ide.agent.protocol

import dev.forge.ide.agent.domain.AgentRole
import dev.forge.ide.agent.domain.PermissionLevel

internal object AgentProtocol {
    const val DELEGATE_TOOL = "delegate_to_agent"
    const val FINISH_TOOL = "finish_task"

    const val ARG_ROLE = "role"
    const val ARG_TASK = "task"
    const val ARG_OBJECTIVE = "objective"
    const val ARG_CONTEXT = "context"
    const val ARG_MAX_STEPS = "maxSteps"
    const val ARG_PERMISSION = "permissionLevel"
    const val ARG_SUMMARY = "summary"
    const val ARG_FINDINGS = "findings"
    const val ARG_FILES_INSPECTED = "filesInspected"
    const val ARG_FILES_CHANGED = "filesChanged"

    fun parseRole(raw: String?): AgentRole? {
        if (raw.isNullOrBlank()) return null
        return runCatching { AgentRole.valueOf(raw.trim().uppercase()) }.getOrNull()
    }

    fun parsePermission(raw: String?): PermissionLevel? {
        if (raw.isNullOrBlank()) return null
        return runCatching { PermissionLevel.valueOf(raw.trim().uppercase()) }.getOrNull()
    }

    fun splitList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split('\n', ';', '|')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
}
