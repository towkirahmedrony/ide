package com.agentx.app.codeintel

/**
 * One node of a syntax tree, in a parser-independent shape.
 *
 * This is the whole surface the code intelligence layer is allowed to see of a
 * parser's output: no tree-sitter type appears in this module. Another backend
 * (a different grammar engine, or an LSP document symbol provider later) only
 * has to produce these nodes.
 *
 * Nodes are immutable views. [start] and [end] are 1-based and inclusive, and
 * [startByte]/[endByte] are the offsets the text slice came from.
 */
interface SyntaxNode {
    /** Grammar-specific node type, e.g. `class_declaration`. */
    val type: String

    /** True for a node the grammar considers part of the language (not punctuation). */
    val isNamed: Boolean

    /** True for an error node: the source could not be parsed here. */
    val isError: Boolean

    /** True for a node the grammar filled in to recover from a syntax error. */
    val isMissing: Boolean

    val startByte: Int
    val endByte: Int
    val start: SourcePosition
    val end: SourcePosition

    /** Exact source text of this node. */
    val text: String

    val childCount: Int

    fun child(index: Int): SyntaxNode?

    /** Child in the grammar's named field, e.g. `name`. Null when absent. */
    fun childByFieldName(name: String): SyntaxNode?
}

/** A parsed file's tree. */
interface SyntaxTree {
    val language: CodeLanguage
    val root: SyntaxNode

    /** True when any node in the tree is an error node. */
    val hasErrors: Boolean
}

/** Span of this node as a [SourceRange], including its byte offsets. */
fun SyntaxNode.span(): SourceRange = SourceRange(
    start = start,
    end = end,
    startByte = startByte,
    endByte = endByte,
)

/** Named children only: the ones a grammar uses for structure. */
fun SyntaxNode.namedChildren(): Sequence<SyntaxNode> =
    (0 until childCount).asSequence().mapNotNull { child(it) }.filter { it.isNamed }

/** Every named node below this one, pre-order, excluding this node. */
fun SyntaxNode.descendants(): Sequence<SyntaxNode> = sequence {
    namedChildren().forEach { child ->
        yield(child)
        yieldAll(child.descendants())
    }
}

/**
 * True when this subtree contains an error node. Used by parsers that do not
 * report the flag themselves.
 */
fun SyntaxNode.containsError(): Boolean =
    isError || descendants().any { it.isError }

/** In-memory tree, used by tests, by fallbacks and by non-native backends. */
class InMemorySyntaxTree(
    override val language: CodeLanguage,
    override val root: SyntaxNode,
    override val hasErrors: Boolean = root.containsError(),
) : SyntaxTree

/**
 * In-memory node.
 *
 * [fields] maps a grammar field name to the index of the child it points at, so
 * a synthetic tree behaves exactly like a parsed one for name resolution.
 */
class InMemorySyntaxNode(
    override val type: String,
    override val start: SourcePosition = SourcePosition(1, 1),
    override val end: SourcePosition = SourcePosition(1, 1),
    override val isNamed: Boolean = true,
    override val isError: Boolean = false,
    override val isMissing: Boolean = false,
    override val text: String = "",
    private val children: List<SyntaxNode> = emptyList(),
    private val fields: Map<String, Int> = emptyMap(),
) : SyntaxNode {

    override val startByte: Int = 0
    override val endByte: Int = text.length
    override val childCount: Int get() = children.size

    override fun child(index: Int): SyntaxNode? = children.getOrNull(index)

    override fun childByFieldName(name: String): SyntaxNode? = fields[name]?.let { child(it) }
}

/** Trees that exist without a parser. */
object SyntaxTrees {

    /**
     * A tree for a file that was not parsed: empty content, or a language this
     * build has no grammar for. It is honest about being empty — one root node
     * with no children and no errors — so callers can render "no structure"
     * without special-casing nulls twice.
     */
    fun empty(language: CodeLanguage, path: String): SyntaxTree = InMemorySyntaxTree(
        language = language,
        root = InMemorySyntaxNode(type = "source_file", text = ""),
        hasErrors = false,
    )

    /** Builds a tree from a compact textual shape, for tests and fixtures. */
    fun of(language: CodeLanguage, root: SyntaxNode): SyntaxTree = InMemorySyntaxTree(language, root)

    /** Convenience for a named leaf with text, the usual building block. */
    fun leaf(
        type: String,
        text: String,
        start: SourcePosition,
        end: SourcePosition = SourcePosition(start.line, start.column + text.length),
    ): InMemorySyntaxNode = InMemorySyntaxNode(type = type, text = text, start = start, end = end)
}
