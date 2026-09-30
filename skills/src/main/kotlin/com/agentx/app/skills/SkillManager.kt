package com.agentx.app.skills

/** Result of a manager refresh. */
data class SkillRefresh(
    val installed: List<SkillDefinition>,
    val invalid: List<SkillDefinition> = emptyList(),
)

/** Outcome of importing a skill. */
sealed interface SkillImportResult {
    data class Imported(val skill: SkillDefinition) : SkillImportResult

    data class Rejected(val reasons: List<String>) : SkillImportResult
}

/**
 * Central management of installed skills.
 *
 * Responsibilities (the only place that touches skill discovery):
 * - discover skills from built-in defaults and document sources,
 * - parse metadata and load `SKILL.md` bodies,
 * - track enabled/disabled state and per-agent assignments,
 * - validate skills and reject malformed or duplicate imports,
 * - resolve which skills apply to an agent role.
 *
 * Agent Core consults this interface and never scans the filesystem itself.
 */
interface SkillManager : SkillRegistry {

    /** Reloads built-ins, sources and stored state. Safe to call repeatedly. */
    suspend fun refresh(): SkillRefresh

    fun isEnabled(id: String): Boolean

    suspend fun setEnabled(id: String, enabled: Boolean)

    /** Roles a skill is assigned to; empty means global (all agents). */
    fun rolesOf(id: String): Set<String>

    suspend fun setRoles(id: String, roles: Set<String>)

    /** Validates [raw] SKILL.md content and stores it as an imported skill. */
    suspend fun import(raw: String, fallbackId: String? = null, path: String? = null): SkillImportResult

    /** Removes a user-imported skill. Built-ins/workspace skills cannot be removed. */
    suspend fun remove(id: String): Boolean

    /** Clears enabled overrides and assignments, restoring shipped defaults. */
    suspend fun resetState()

    /** Removes imported skills as well as their state. */
    suspend fun clearImported()
}

/**
 * Default [SkillManager].
 *
 * Reads are served from an in-memory snapshot so the agent loop never blocks on
 * storage; [refresh] (called at boot and after every Settings change) rebuilds
 * it. State that the user did not explicitly set falls back to the skill's own
 * defaults, so a broken or empty store can never silently disable everything.
 */
class DefaultSkillManager(
    private val builtins: List<SkillDefinition> = BuiltinSkills.all(),
    private val sources: List<SkillSource> = emptyList(),
    private val store: SkillStore = InMemorySkillStore(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) : SkillManager {

    private data class State(
        val skills: Map<String, SkillDefinition> = emptyMap(),
        val enabled: Map<String, Boolean> = emptyMap(),
        val assignments: Map<String, Set<String>> = emptyMap(),
    )

    @Volatile
    private var state = State()

    override suspend fun refresh(): SkillRefresh {
        val discovered = linkedMapOf<String, SkillDefinition>()
        // Built-ins win on id collisions so an imported skill can never shadow a
        // shipped one; a duplicate import is rejected rather than merged.
        builtins.forEach { skill -> discovered[skill.id] = skill.copy(source = SkillSource.BUILTIN) }
        sources.forEach { source ->
            runCatching { source.discover() }.getOrNull().orEmpty().forEach { skill ->
                if (skill.id !in discovered) {
                    discovered[skill.id] = skill.copy(source = SkillSource.WORKSPACE)
                }
            }
        }
        store.imported().forEach { skill ->
            if (skill.id !in discovered) {
                discovered[skill.id] = skill.copy(source = SkillSource.IMPORTED)
            }
        }

        val snapshot = State(
            skills = discovered,
            enabled = store.enabledOverrides(),
            assignments = store.assignments(),
        )
        state = snapshot
        val installed = sorted(discovered.values.toList())
        return SkillRefresh(
            installed = installed.filter { it.valid },
            invalid = installed.filterNot { it.valid },
        )
    }

    override fun installed(): List<SkillDefinition> = sorted(state.skills.values.toList())

    override fun find(id: String): SkillDefinition? = state.skills[id]

    override fun isEnabled(id: String): Boolean {
        state.enabled[id]?.let { return it }
        return state.skills[id]?.defaultEnabled == true
    }

    override suspend fun setEnabled(id: String, enabled: Boolean) {
        if (id !in state.skills) return
        store.setEnabled(id, enabled)
        state = state.copy(enabled = state.enabled + (id to enabled))
    }

    override fun rolesOf(id: String): Set<String> =
        state.assignments[id] ?: state.skills[id]?.roles.orEmpty()

    override suspend fun setRoles(id: String, roles: Set<String>) {
        if (id !in state.skills) return
        val normalized = roles.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()
        store.setAssignment(id, normalized)
        state = state.copy(assignments = state.assignments + (id to normalized))
    }

    override fun enabled(): List<SkillDefinition> =
        sorted(state.skills.values.filter { it.valid && isEnabled(it.id) })

    override fun resolveForAgent(role: String): List<SkillDefinition> {
        val normalized = role.trim().uppercase()
        return enabled().filter { skill ->
            val roles = rolesOf(skill.id)
            roles.isEmpty() || normalized in roles
        }
    }

    override suspend fun import(raw: String, fallbackId: String?, path: String?): SkillImportResult {
        val document = SkillDocument(path = path ?: "imported/$FILE_PLACEHOLDER", content = raw)
        val parsed = SkillMarkdown.parse(document, fallbackId ?: path?.substringBeforeLast('/', ""))
        if (parsed is SkillParseResult.Invalid) return SkillImportResult.Rejected(parsed.problems)
        val skill = (parsed as SkillParseResult.Valid).skill
        val id = skill.id.lowercase()

        val reasons = mutableListOf<String>()
        if (!SkillMarkdown.isSafeId(id)) reasons += "skill id is not a safe identifier"
        if (id in state.skills) reasons += "a skill with id '$id' is already installed"
        if (reasons.isNotEmpty()) return SkillImportResult.Rejected(reasons)

        val imported = skill.copy(source = SkillSource.IMPORTED, id = id)
        store.saveImported(imported)
        state = state.copy(skills = state.skills + (id to imported))
        return SkillImportResult.Imported(imported)
    }

    override suspend fun remove(id: String): Boolean {
        val skill = state.skills[id] ?: return false
        if (skill.source != SkillSource.IMPORTED) return false
        val removed = store.removeImported(id)
        if (removed) {
            state = state.copy(
                skills = state.skills - id,
                enabled = state.enabled - id,
                assignments = state.assignments - id,
            )
        }
        return removed
    }

    override suspend fun resetState() {
        store.reset()
        state = state.copy(enabled = emptyMap(), assignments = emptyMap())
    }

    override suspend fun clearImported() {
        state.skills.values.filter { it.source == SkillSource.IMPORTED }.forEach { store.removeImported(it.id) }
        state = state.copy(
            skills = state.skills.filterValues { it.source != SkillSource.IMPORTED },
            enabled = state.enabled.filterKeys { it in state.skills && state.skills[it]?.source != SkillSource.IMPORTED },
            assignments = state.assignments.filterKeys { state.skills[it]?.source != SkillSource.IMPORTED },
        )
    }

    /** Highest priority first, then by id, so selection is deterministic. */
    private fun sorted(skills: List<SkillDefinition>): List<SkillDefinition> =
        skills.sortedWith(compareByDescending<SkillDefinition> { it.priority }.thenBy { it.id })

    private companion object {
        const val FILE_PLACEHOLDER = "SKILL.md"
    }
}
