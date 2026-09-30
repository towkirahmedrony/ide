package com.agentx.app.agent.prompt

import com.agentx.app.tools.SecretRedactor

/**
 * The values available to a prompt template.
 *
 * Only explicitly supplied, non-secret values can be substituted; the resolver
 * never reaches into the environment or storage on its own.
 */
data class PromptVariables(val values: Map<String, String> = emptyMap()) {

    fun value(name: String): String? = values[name]

    fun asMap(): Map<String, String> = values

    companion object {
        val EMPTY = PromptVariables()

        fun of(vararg pairs: Pair<String, String>): PromptVariables =
            PromptVariables(pairs.toMap())
    }
}

/** One documented template variable. */
data class SupportedPromptVariable(
    val name: String,
    val description: String,
)

/** Outcome of resolving a template. */
data class PromptResolution(
    val text: String,
    /** Referenced but not supplied; left verbatim in [text]. */
    val unknownVariables: Set<String> = emptySet(),
    /** Dropped because the name looks like a secret. */
    val rejectedVariables: Set<String> = emptySet(),
)

/**
 * The single place where prompt/template strings are expanded.
 *
 * Rules:
 * - `{{name}}` tokens are replaced only from [PromptVariables];
 * - an unresolved token is left in place (never crashes, never becomes empty by
 *   accident) and reported in [PromptResolution.unknownVariables];
 * - a value whose *name* looks like a secret (api key, token, password, …) is
 *   never substituted, and a substituted value is redacted defensively;
 * - no arbitrary repeated replacement happens anywhere else in the agent.
 */
object PromptTemplate {

    /** `{{name}}` with optional surrounding whitespace. */
    private val TOKEN = Regex("""\{\{\s*([A-Za-z0-9_]+)\s*}}""")

    /** Variables the Settings UI documents, resolved by the app when available. */
    val SUPPORTED: List<SupportedPromptVariable> = listOf(
        SupportedPromptVariable("workspace", "Identifier of the open workspace, when one is open."),
        SupportedPromptVariable("project_name", "Display name of the open project, when known."),
        SupportedPromptVariable("current_file", "Workspace-relative path of the file open in the editor."),
        SupportedPromptVariable("language", "Language of the current file, when it can be detected."),
    )

    val supportedNames: Set<String> = SUPPORTED.map { it.name }.toSet()

    fun resolve(template: String, variables: PromptVariables): PromptResolution {
        if (template.isEmpty()) return PromptResolution(template)
        val unknown = linkedSetOf<String>()
        val rejected = linkedSetOf<String>()
        val resolved = TOKEN.replace(template) { match ->
            val name = match.groupValues[1]
            if (SecretRedactor.looksSecret(name)) {
                rejected += name
                match.value
            } else {
                val value = variables.value(name)
                if (value == null) {
                    unknown += name
                    match.value
                } else {
                    SecretRedactor.redactText(value)
                }
            }
        }
        return PromptResolution(resolved, unknown, rejected)
    }
}
