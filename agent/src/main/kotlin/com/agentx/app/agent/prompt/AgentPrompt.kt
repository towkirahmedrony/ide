package com.agentx.app.agent.prompt

import com.agentx.app.agent.domain.AgentRole

/**
 * The configurable system prompt for one agent role.
 *
 * [isCustom] is derived, not stored: it is true when the user has overridden the
 * shipped default. The default itself is never stored here — it lives in
 * [DefaultAgentPrompts] and is always available as a fallback.
 */
data class AgentPromptConfig(
    val role: AgentRole,
    val prompt: String,
    val enabled: Boolean = true,
    val description: String? = null,
    val version: String? = null,
    val updatedAtMillis: Long = 0L,
    val isCustom: Boolean = false,
)

/** Which layer produced a resolved prompt. */
enum class AgentPromptSource {
    /** The shipped default. */
    DEFAULT,

    /** A user-customized prompt. */
    CUSTOM,
}

/** A prompt after resolution: variables substituted, fallback applied. */
data class ResolvedPrompt(
    val role: AgentRole,
    val text: String,
    val source: AgentPromptSource,
    /** Template variables referenced but not supplied; left untouched in [text]. */
    val unknownVariables: Set<String> = emptySet(),
    /** Variable names dropped because their name looks like a secret. */
    val rejectedVariables: Set<String> = emptySet(),
)
