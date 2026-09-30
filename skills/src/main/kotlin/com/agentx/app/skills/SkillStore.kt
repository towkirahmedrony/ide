package com.agentx.app.skills

/**
 * Persistence for the user's skill choices and imported skill metadata.
 *
 * Only configuration is stored here: enabled overrides, agent-role
 * assignments and imported skill definitions. Skill instruction bodies are
 * data, never secrets, and never executable.
 */
interface SkillStore {
    /** Explicit enabled overrides, keyed by skill id. */
    suspend fun enabledOverrides(): Map<String, Boolean>

    suspend fun setEnabled(id: String, enabled: Boolean)

    /** Explicit role assignments, keyed by skill id. Empty set means global. */
    suspend fun assignments(): Map<String, Set<String>>

    suspend fun setAssignment(id: String, roles: Set<String>)

    suspend fun imported(): List<SkillDefinition>

    suspend fun saveImported(skill: SkillDefinition)

    suspend fun removeImported(id: String): Boolean

    /** Clears enabled overrides and assignments. Imported skills are kept. */
    suspend fun reset()
}

/** In-memory [SkillStore] for tests, previews and non-Android hosts. */
class InMemorySkillStore : SkillStore {

    private val enabled = linkedMapOf<String, Boolean>()
    private val assigned = linkedMapOf<String, Set<String>>()
    private val importedSkills = linkedMapOf<String, SkillDefinition>()

    override suspend fun enabledOverrides(): Map<String, Boolean> = enabled.toMap()

    override suspend fun setEnabled(id: String, enabled: Boolean) {
        this.enabled[id] = enabled
    }

    override suspend fun assignments(): Map<String, Set<String>> = assigned.toMap()

    override suspend fun setAssignment(id: String, roles: Set<String>) {
        assigned[id] = roles
    }

    override suspend fun imported(): List<SkillDefinition> = importedSkills.values.toList()

    override suspend fun saveImported(skill: SkillDefinition) {
        importedSkills[skill.id] = skill
    }

    override suspend fun removeImported(id: String): Boolean = importedSkills.remove(id) != null

    override suspend fun reset() {
        enabled.clear()
        assigned.clear()
    }
}
