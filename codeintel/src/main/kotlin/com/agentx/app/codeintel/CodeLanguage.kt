package com.agentx.app.codeintel

/**
 * A language this IDE recognises.
 *
 * Detection and parsing are deliberately separate concerns: [extensions] drives
 * [LanguageDetector], while structural understanding comes from a
 * [SyntaxParserProvider]. A language can therefore be recognised — highlighted,
 * counted, listed as a project file — without the app pretending it can build a
 * syntax tree for it. Ask the engine ([CodeIntelligence.parsedLanguages]) which
 * languages a running build can really parse.
 */
enum class CodeLanguage(
    val displayName: String,
    /** Lower-case extensions, without the dot. */
    val extensions: Set<String>,
) {
    KOTLIN("Kotlin", setOf("kt", "kts")),
    JAVA("Java", setOf("java")),
    PYTHON("Python", setOf("py", "pyi", "pyw")),
    JAVASCRIPT("JavaScript", setOf("js", "mjs", "cjs")),
    JSX("JSX", setOf("jsx")),
    TYPESCRIPT("TypeScript", setOf("ts", "mts", "cts")),
    TSX("TSX", setOf("tsx")),
    JSON("JSON", setOf("json", "jsonc")),
    XML("XML", setOf("xml", "xsd", "svg", "plist")),
    HTML("HTML", setOf("html", "htm")),
    CSS("CSS", setOf("css")),
    MARKDOWN("Markdown", setOf("md", "markdown")),
    SQL("SQL", setOf("sql")),
    SHELL("Shell", setOf("sh", "bash", "zsh", "ksh")),

    /**
     * A file whose language this build does not know. Detection never fails: an
     * unknown extension reports this instead of throwing.
     */
    UNKNOWN("Unknown", emptySet()),
    ;

    /** False only for [UNKNOWN]. */
    val isKnown: Boolean get() = this != UNKNOWN

    /** True when [extension] (with or without a dot) names this language. */
    fun matchesExtension(extension: String): Boolean =
        extension.lowercase().removePrefix(".") in extensions

    companion object {
        /** Every recognised extension, without the dot. */
        val knownExtensions: Set<String> = entries.flatMapTo(linkedSetOf()) { it.extensions }
    }
}

/**
 * Detects a language from a file path.
 *
 * Extension-only on purpose: the IDE analyses files the user opened, so the
 * filename it already has is the whole input. Nothing here inspects content, so
 * detection is instant, allocation-light and safe to call from any thread.
 */
object LanguageDetector {

    fun detect(path: String?): CodeLanguage {
        val extension = extensionOf(path) ?: return CodeLanguage.UNKNOWN
        return byExtension[extension] ?: CodeLanguage.UNKNOWN
    }

    /** The lower-case extension of [path], or null when it has none. */
    fun extensionOf(path: String?): String? {
        val name = fileNameOf(path) ?: return null
        val index = name.lastIndexOf('.')
        if (index <= 0 || index == name.length - 1) return null
        return name.substring(index + 1).lowercase()
    }

    /** Final path segment, handling both separators. */
    fun fileNameOf(path: String?): String? = path
        ?.trim()
        ?.replace('\\', '/')
        ?.substringAfterLast('/')
        ?.takeIf { it.isNotEmpty() }

    /** True when [path] is a file this IDE can acknowledge at all. */
    fun isKnown(path: String?): Boolean = detect(path).isKnown

    private val byExtension: Map<String, CodeLanguage> = CodeLanguage.entries
        .filter { it != CodeLanguage.UNKNOWN }
        .flatMap { language -> language.extensions.map { extension -> extension to language } }
        .toMap()
}
