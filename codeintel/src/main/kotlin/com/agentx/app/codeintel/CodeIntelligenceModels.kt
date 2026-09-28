package com.agentx.app.codeintel

/**
 * A file to analyse: where it is and what it currently contains.
 *
 * The caller reads the text through the Workspace Runtime, so this module never
 * touches storage and never needs a workspace. That also lets the editor analyse
 * an unsaved draft, which is the case that matters while typing.
 */
data class SourceFile(
    val path: String,
    val content: String,
    /** Overrides detection, e.g. when the caller already knows the language. */
    val language: CodeLanguage? = null,
)

/** 1-based line and column, as the editor displays them. */
data class SourcePosition(val line: Int, val column: Int) : Comparable<SourcePosition> {

    override fun compareTo(other: SourcePosition): Int =
        if (line != other.line) line - other.line else column - other.column

    override fun toString(): String = "$line:$column"
}

/**
 * A half-open span of source: [start] inclusive, [end] inclusive of the last
 * character of the node, so a symbol's range covers exactly its text.
 *
 * Byte offsets are carried alongside because a tree-sitter node is defined in
 * bytes; they make text slicing exact without re-deriving it from lines.
 */
data class SourceRange(
    val start: SourcePosition,
    val end: SourcePosition,
    val startByte: Int = 0,
    val endByte: Int = 0,
) {
    val lineCount: Int get() = end.line - start.line + 1

    val isSingleLine: Boolean get() = start.line == end.line

    fun contains(position: SourcePosition): Boolean = position >= start && position <= end

    /** True when [other] is inside this range (or equal to it). */
    fun contains(other: SourceRange): Boolean = other.start >= start && other.end <= end

    override fun toString(): String = "$start–$end"
}

/**
 * What a symbol is. These are the categories the supported grammars can
 * identify reliably; nothing is invented for a construct a grammar does not
 * expose.
 */
enum class SymbolKind(val displayName: String) {
    CLASS("class"),
    INTERFACE("interface"),
    OBJECT("object"),
    ENUM("enum"),
    FUNCTION("function"),
    METHOD("method"),
    CONSTRUCTOR("constructor"),
    PROPERTY("property"),
    VARIABLE("variable"),
    TYPE("type"),
    IMPORT("import"),
    NAMESPACE("namespace"),
    ;

    /** True when symbols can be nested inside this kind. */
    val isContainer: Boolean
        get() = this == CLASS || this == INTERFACE || this == OBJECT || this == ENUM

    /** True for callables, which the outline renders with parentheses. */
    val isCallable: Boolean
        get() = this == FUNCTION || this == METHOD || this == CONSTRUCTOR
}

/** One structural element of a file. */
data class CodeSymbol(
    val name: String,
    val kind: SymbolKind,
    val language: CodeLanguage,
    /** Workspace-relative path of the file this symbol lives in. */
    val path: String,
    /** Span of the whole declaration. */
    val range: SourceRange,
    /** Span of just the name, when the grammar exposes it. */
    val nameRange: SourceRange? = null,
    /** Name of the enclosing symbol, when there is one. */
    val parent: String? = null,
    /** First line of the declaration, whitespace collapsed. Never a secret. */
    val signature: String? = null,
) {
    /** `UserRepository.getUser`, or just the name at top level. */
    val qualifiedName: String
        get() = if (parent.isNullOrBlank()) name else "$parent.$name"

    /** Outline label: `function getUser()`, `property client`, `class User`. */
    val label: String
        get() = buildString {
            append(kind.displayName)
            append(' ')
            append(name)
            if (kind.isCallable) append("()")
        }

    override fun toString(): String = "$label ($path @${range.start})"
}

/** One node of a file outline, with its nested symbols. */
data class OutlineNode(
    val symbol: CodeSymbol,
    val children: List<OutlineNode> = emptyList(),
) {
    /** Depth-first walk including this node. */
    fun depthFirst(): Sequence<OutlineNode> = sequence {
        yield(this@OutlineNode)
        children.forEach { yieldAll(it.depthFirst()) }
    }

    internal fun render(builder: StringBuilder, prefix: String, isLast: Boolean, isRoot: Boolean) {
        builder.append(if (isRoot) symbol.label else (if (isLast) "└── " else "├── "))
        builder.append(symbol.label)
        builder.append('\n')
        val childPrefix = if (isRoot) "" else prefix + (if (isLast) "    " else "│   ")
        children.forEachIndexed { index, child ->
            child.render(builder, childPrefix, index == children.lastIndex, false)
        }
    }
}

/**
 * The structure of one file, as the editor and the agent consume it.
 *
 * [roots] are top-level symbols; everything else hangs off them. [symbolCount]
 * is the number of symbols in the outline, and [truncated] says whether the
 * extraction hit its limit (large generated files).
 */
data class FileOutline(
    val path: String,
    val language: CodeLanguage,
    val roots: List<OutlineNode>,
    val symbolCount: Int,
    val truncated: Boolean = false,
    val hasSyntaxErrors: Boolean = false,
) {
    val isEmpty: Boolean get() = symbolCount == 0

    /** Every symbol, in source order. */
    fun symbols(): List<CodeSymbol> = roots.flatMap { it.depthFirst() }.map { it.symbol }.sortedBy { it.range.start }

    /** Innermost symbol containing [position], for "symbol at cursor". */
    fun symbolAt(position: SourcePosition): CodeSymbol? = roots
        .flatMap { it.depthFirst() }
        .map { it.symbol }
        .filter { it.range.contains(position) }
        .minWithOrNull(
            compareBy({ it.range.lineCount }, { it.range.endByte - it.range.startByte }),
        )

    fun symbolsOf(kind: SymbolKind): List<CodeSymbol> = symbols().filter { it.kind == kind }

    /** Renders the outline the way the editor shows it. */
    fun render(): String {
        if (isEmpty) return "${LanguageDetector.fileNameOf(path) ?: path} — no symbols found"
        val builder = StringBuilder()
        builder.append(LanguageDetector.fileNameOf(path) ?: path).append(" · ").append(language.displayName).append('\n')
        roots.forEach { it.render(builder, prefix = "", isLast = false, isRoot = true) }
        if (truncated) builder.append("…[outline truncated]\n")
        return builder.toString().trimEnd()
    }

    override fun toString(): String = "FileOutline($path, ${language.displayName}, $symbolCount symbols)"
}

/** A definition of [name] in the analysed file was found here. */
data class CodeReference(
    val name: String,
    val range: SourceRange,
    /** True for the declaration itself, false for a usage. */
    val isDefinition: Boolean = false,
) {
    override fun toString(): String = "${if (isDefinition) "definition" else "reference"} $name @${range.start}"
}

/**
 * The result of analysing one file.
 *
 * [tree] is null for an empty file and for a parse that could not run; callers
 * must treat a null tree as "no structure available", never as "no code".
 */
data class ParsedFile(
    val path: String,
    val language: CodeLanguage,
    val tree: SyntaxTree?,
    val symbols: List<CodeSymbol>,
    val outline: FileOutline,
    /** True when the grammar reported error nodes: normal while typing. */
    val hasSyntaxErrors: Boolean,
    /** True for a blank file, which is not an error. */
    val isEmpty: Boolean,
) {
    val symbolCount: Int get() = symbols.size

    override fun toString(): String =
        "ParsedFile($path, ${language.displayName}, symbols=${symbols.size}, errors=$hasSyntaxErrors)"
}
