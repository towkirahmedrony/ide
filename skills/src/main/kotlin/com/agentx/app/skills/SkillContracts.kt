package com.agentx.app.skills

import com.agentx.app.core.architecture.LayerDescriptor
import com.agentx.app.core.architecture.LayerStatus

/** Metadata describing an installable skill. */
data class SkillManifest(
    val id: String,
    val name: String,
    val description: String,
    val version: String? = null,
    val toolIds: List<String> = emptyList(),
)

/**
 * A skill bundles prompts, tools, and instructions. The readonly contract is
 * kept for compatibility; [SkillDefinition] is the concrete, serializable form
 * the registry and Settings work with.
 */
interface Skill {
    val manifest: SkillManifest

    suspend fun activate()
}

/** Where a skill came from. Drives whether the user may delete it. */
enum class SkillSource {
    /** Shipped with the app; cannot be removed, only disabled or reset. */
    BUILTIN,

    /** Discovered in the workspace's skills directory. */
    WORKSPACE,

    /** Imported by the user from a file or pasted SKILL.md. */
    IMPORTED,
}

/** Coarse instruction priority. Higher wins when a budget cannot fit everything. */
object SkillPriority {
    const val LOW = 0
    const val NORMAL = 1
    const val HIGH = 2

    fun nameOf(priority: Int): String = when {
        priority >= HIGH -> "high"
        priority <= LOW -> "low"
        else -> "normal"
    }

    fun parse(raw: String?): Int = when (raw?.trim()?.lowercase()) {
        "high", "critical" -> HIGH
        "low" -> LOW
        else -> NORMAL
    }
}

/**
 * A fully resolved skill: metadata plus its instruction body.
 *
 * Everything here is data. A skill is an instruction set, never executable
 * code, and it is never evaluated.
 */
data class SkillDefinition(
    val id: String,
    val name: String,
    val description: String,
    val instructions: String,
    val version: String? = null,
    val source: SkillSource,
    /** Location the skill was read from, when it came from a filesystem. */
    val path: String? = null,
    /**
     * Agent roles this skill targets. Empty means the skill is global and may be
     * offered to every agent (the Settings assignment can narrow or widen this).
     */
    val roles: Set<String> = emptySet(),
    val priority: Int = SkillPriority.NORMAL,
    /** Enabled out of the box. User overrides always win. */
    val defaultEnabled: Boolean = false,
    /** Validation problems; [valid] is false when this is non-empty. */
    val problems: List<String> = emptyList(),
) {
    val valid: Boolean get() = problems.isEmpty()

    val isGlobal: Boolean get() = roles.isEmpty()

    init {
        require(id.isNotBlank()) { "Skill id must not be blank" }
        require(name.isNotBlank()) { "Skill name must not be blank" }
    }
}

/**
 * Central, read-side view of the installed skills. Agent Core consults this and
 * never scans the filesystem itself.
 */
interface SkillRegistry {
    /** Every installed skill (valid or not), built-ins first. */
    fun installed(): List<SkillDefinition>

    fun find(id: String): SkillDefinition?

    /** Installed, valid and currently enabled skills, highest priority first. */
    fun enabled(): List<SkillDefinition>

    /**
     * Skills that should be offered to [role]: enabled, valid, and either
     * global or explicitly assigned to that role.
     */
    fun resolveForAgent(role: String): List<SkillDefinition>
}

val SKILLS_LAYER = LayerDescriptor(
    id = "skills",
    title = "Skills",
    summary = "Packages reusable prompts, tools, and instructions for agents to load.",
    status = LayerStatus.ACTIVE,
)
