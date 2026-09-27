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

/** A skill bundles prompts, tools, and instructions. Implemented later. */
interface Skill {
    val manifest: SkillManifest

    suspend fun activate()
}

interface SkillRegistry {
    fun register(skill: Skill)

    fun manifests(): List<SkillManifest>

    fun find(id: String): Skill?
}

val SKILLS_LAYER = LayerDescriptor(
    id = "skills",
    title = "Skills",
    summary = "Packages reusable prompts, tools, and instructions for agents to load.",
    status = LayerStatus.CONTRACT_ONLY,
)
