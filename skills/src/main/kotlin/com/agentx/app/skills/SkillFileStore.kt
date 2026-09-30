package com.agentx.app.skills

/**
 * Filesystem-backed storage for user-imported skills, in the project's
 * `skills/<id>/SKILL.md` format. This is the canonical format; imports are
 * written here and read back as skill definitions.
 */
interface SkillFileStore {
    suspend fun list(): List<SkillDefinition>

    suspend fun write(skill: SkillDefinition)

    suspend fun delete(id: String): Boolean
}

/**
 * Combines a small configuration store (enabled flags, role assignments) with a
 * filesystem store for imported skill bodies.
 *
 * When [files] is null, imported skills are simply not persisted, which is the
 * right behaviour for a preview or a host without a skills directory.
 */
class CompositeSkillStore(
    private val config: SkillStore,
    private val files: SkillFileStore? = null,
) : SkillStore {

    override suspend fun enabledOverrides(): Map<String, Boolean> = config.enabledOverrides()

    override suspend fun setEnabled(id: String, enabled: Boolean) = config.setEnabled(id, enabled)

    override suspend fun assignments(): Map<String, Set<String>> = config.assignments()

    override suspend fun setAssignment(id: String, roles: Set<String>) = config.setAssignment(id, roles)

    override suspend fun imported(): List<SkillDefinition> = files?.list().orEmpty()

    override suspend fun saveImported(skill: SkillDefinition) {
        files?.write(skill)
        config.saveImported(skill)
    }

    override suspend fun removeImported(id: String): Boolean {
        config.removeImported(id)
        return files?.delete(id) ?: false
    }

    override suspend fun reset() = config.reset()
}
