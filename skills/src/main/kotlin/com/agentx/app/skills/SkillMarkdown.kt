package com.agentx.app.skills

import java.util.Locale

/**
 * A single skill instruction file, as read from some document source. The
 * content is treated as text/instructions only and is never executed.
 */
data class SkillDocument(
    /** Location for diagnostics and provenance (workspace-relative or a URI). */
    val path: String,
    val content: String,
)

/** Supplies SKILL.md documents from a filesystem, workspace or import store. */
fun interface SkillDocumentSource {
    suspend fun documents(): List<SkillDocument>
}

/** A source of already-parsed skills. */
fun interface SkillDiscoverySource {
    suspend fun discover(): List<SkillDefinition>
}

/** Outcome of parsing one SKILL.md document. */
sealed interface SkillParseResult {
    data class Valid(val skill: SkillDefinition) : SkillParseResult

    data class Invalid(val path: String, val problems: List<String>) : SkillParseResult
}

/**
 * Deterministic parser for the `SKILL.md` format.
 *
 * ```markdown
 * ---
 * id: debugging            # optional; derived from the name or folder otherwise
 * name: Debugging
 * description: Root-cause a failing test or crash.
 * version: 1.0
 * roles: coder, debugger   # optional; empty means "all agents"
 * priority: high           # low | normal | high
 * enabled: true            # optional default-on flag
 * ---
 *
 * ## Instructions
 * ...
 * ```
 *
 * Only a small, dependency-free YAML-ish front matter is supported on purpose:
 * skill files are data, so the parser is strict and rejects anything it does
 * not understand instead of guessing.
 */
object SkillMarkdown {

    const val FILE_NAME: String = "SKILL.md"

    private val ID_PATTERN = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")

    /** Roles this build recognises; a skill naming an unknown role is invalid. */
    val KNOWN_ROLES: Set<String> = setOf(
        "MAIN",
        "EXPLORER",
        "RESEARCHER",
        "CODER",
        "DEBUGGER",
        "REVIEWER",
        "TESTER",
    )

    /**
     * Parses [document]. [fallbackId] is used when no `id` is present (normally
     * the containing folder name).
     */
    fun parse(document: SkillDocument, fallbackId: String? = null): SkillParseResult {
        val normalized = document.content.replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.split('\n')
        if (lines.firstOrNull()?.trim() != "---") {
            return SkillParseResult.Invalid(
                document.path,
                listOf("SKILL.md must start with a '---' front matter block"),
            )
        }
        val endIndex = lines.drop(1).indexOfFirst { it.trim() == "---" }
        if (endIndex < 0) {
            return SkillParseResult.Invalid(document.path, listOf("front matter is not closed with '---'"))
        }
        val frontMatter = lines.subList(1, endIndex + 1)
        val body = lines.drop(endIndex + 2).joinToString("\n").trim()

        val fields = linkedMapOf<String, String>()
        frontMatter.forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val separator = line.indexOf(':')
            if (separator <= 0) {
                return SkillParseResult.Invalid(
                    document.path,
                    listOf("front matter line ${index + 2} is not a 'key: value' pair"),
                )
            }
            val key = line.substring(0, separator).trim().lowercase(Locale.ROOT)
            val value = line.substring(separator + 1).trim().trim('"', '\'')
            fields[key] = value
        }

        val problems = mutableListOf<String>()
        val name = fields["name"]?.trim().orEmpty()
        if (name.isBlank()) problems += "front matter is missing a 'name'"
        if (body.isBlank()) problems += "SKILL.md has no instruction body"

        val rawId = fields["id"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: fallbackId?.trim()?.takeIf { it.isNotEmpty() }
            ?: name.takeIf { it.isNotBlank() }?.let { slug(it) }
            ?: ""
        val id = rawId.lowercase(Locale.ROOT)
        if (id.isBlank()) {
            problems += "skill id could not be derived; add an 'id: ...' field"
        } else if (!ID_PATTERN.matches(id)) {
            problems += "skill id '$id' is invalid; use lowercase letters, digits, '.', '_' or '-'"
        } else if (id.contains("..")) {
            problems += "skill id must not contain path traversal"
        }

        val roles = parseRoles(fields["roles"], problems)

        val version = fields["version"]?.trim()?.takeIf { it.isNotEmpty() }
        val priority = SkillPriority.parse(fields["priority"])
        val defaultEnabled = fields["enabled"]?.trim()?.lowercase(Locale.ROOT) in setOf("true", "yes", "on", "1")

        if (problems.isNotEmpty()) return SkillParseResult.Invalid(document.path, problems)

        return SkillParseResult.Valid(
            SkillDefinition(
                id = id,
                name = name,
                description = fields["description"]?.trim().orEmpty(),
                instructions = body,
                version = version,
                source = SkillSource.WORKSPACE,
                path = document.path,
                roles = roles,
                priority = priority,
                defaultEnabled = defaultEnabled,
            ),
        )
    }

    private fun parseRoles(raw: String?, problems: MutableList<String>): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        val roles = raw.split(',', ' ', '|', ';')
            .map { it.trim().uppercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .toSet()
        val unknown = roles - KNOWN_ROLES
        if (unknown.isNotEmpty()) {
            problems += "unknown agent role(s): ${unknown.sorted().joinToString(", ")}"
        }
        return roles - unknown
    }

    /** Lowercase, hyphenated identifier derived from a display name. */
    fun slug(value: String): String = value
        .lowercase(Locale.ROOT)
        .map { if (it.isLetterOrDigit()) it else '-' }
        .joinToString("")
        .split('-')
        .filter { it.isNotEmpty() }
        .joinToString("-")

    /**
     * Rejects ids that could escape a skills directory or address an absolute
     * location. Used by import, before anything is written.
     */
    fun isSafeId(id: String): Boolean =
        id.isNotBlank() && ID_PATTERN.matches(id.lowercase(Locale.ROOT)) && !id.contains("..")

    /** Serializes [skill] back to the SKILL.md format, used when importing. */
    fun encode(skill: SkillDefinition): String = buildString {
        append("---\n")
        append("id: ").append(skill.id).append('\n')
        append("name: ").append(singleLine(skill.name)).append('\n')
        if (skill.description.isNotBlank()) {
            append("description: ").append(singleLine(skill.description)).append('\n')
        }
        skill.version?.takeIf { it.isNotBlank() }?.let { append("version: ").append(singleLine(it)).append('\n') }
        if (skill.roles.isNotEmpty()) {
            append("roles: ").append(skill.roles.map { it.lowercase(Locale.ROOT) }.sorted().joinToString(", ")).append('\n')
        }
        append("priority: ").append(SkillPriority.nameOf(skill.priority)).append('\n')
        if (skill.defaultEnabled) append("enabled: true\n")
        append("---\n\n")
        append(skill.instructions.trim()).append('\n')
    }

    private fun singleLine(value: String): String = value.replace('\n', ' ').replace('\r', ' ').trim()
}

/** Parses every document [source] yields, keeping valid ones and reporting the rest. */
class MarkdownSkillSource(
    private val documents: SkillDocumentSource,
    private val fallbackId: (SkillDocument) -> String? = { null },
) : SkillDiscoverySource {

    override suspend fun discover(): List<SkillDefinition> {
        val discovered = mutableListOf<SkillDefinition>()
        documents.documents().forEach { document ->
            when (val parsed = SkillMarkdown.parse(document, fallbackId(document))) {
                is SkillParseResult.Valid -> discovered += parsed.skill
                is SkillParseResult.Invalid -> Unit
            }
        }
        return discovered
    }
}
