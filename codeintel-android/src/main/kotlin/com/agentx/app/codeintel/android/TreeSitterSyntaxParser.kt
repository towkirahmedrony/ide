package com.agentx.app.codeintel.android

import com.agentx.app.codeintel.CodeLanguage
import com.agentx.app.codeintel.ParseRequest
import com.agentx.app.codeintel.SourcePosition
import com.agentx.app.codeintel.SyntaxNode
import com.agentx.app.codeintel.SyntaxParseResult
import com.agentx.app.codeintel.SyntaxParser
import com.agentx.app.codeintel.SyntaxTree
import com.agentx.app.codeintel.containsError
import com.itsaky.androidide.treesitter.TSLanguage
import com.itsaky.androidide.treesitter.TSNode
import com.itsaky.androidide.treesitter.TSParser
import com.itsaky.androidide.treesitter.TSTree

/**
 * Tree-sitter parser for one language.
 *
 * The native library is used as a *step*, never as the model the rest of the
 * app holds:
 *
 * 1. a fresh [TSParser] gets the language and a hard time budget;
 * 2. the source is parsed into a native tree;
 * 3. every named node (plus grammar field names) is copied into the pure
 *    [TreeSitterNode] graph of `:codeintel`;
 * 4. the native tree and parser are closed before this method returns.
 *
 * Step 4 is what makes the rest of the IDE safe. Nothing outside this class
 * touches native memory, so no symbol, outline or cached result can outlive a
 * closed handle, and there is no native memory to leak when a tree is dropped
 * by the cache or by a recomposition.
 *
 * Copies keep no text: a node stores its source string plus its offsets and
 * slices on demand, so a file with tens of thousands of nodes still costs one
 * copy of the source.
 *
 * Failure is always a [SyntaxParseResult], never a thrown error. Malformed
 * source is *not* a failure: tree-sitter recovers and the resulting tree
 * contains error nodes, which the engine reports as a partial result.
 */
class TreeSitterSyntaxParser(
    override val language: CodeLanguage,
    private val grammar: TSLanguage,
    /** Hard budget for one parse; the native parser halts when it is exceeded. */
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    /** Upper bound on copied nodes, so a pathological file cannot exhaust memory. */
    private val maxNodes: Int = DEFAULT_MAX_NODES,
) : SyntaxParser {

    override fun parse(request: ParseRequest): SyntaxParseResult {
        // Cancellation is honoured before any work happens, and again by the
        // engine once this call returns: a parse already inside the native
        // library is bounded by the timeout instead of being interrupted.
        if (request.isCancelled()) return SyntaxParseResult.Failed(REASON_CANCELLED)

        val parser = TSParser.create()
        var tree: TSTree? = null
        try {
            parser.setLanguage(grammar)
            if (timeoutMillis > 0L) parser.setTimeout(timeoutMillis * 1000L)

            val parsed = parser.parseString(request.content)
                ?: return SyntaxParseResult.Failed(REASON_TIMEOUT)

            // Owned from here on, so the native tree is closed even when the
            // copy below fails.
            tree = parsed
            val sourceMap = TreeSitterSourceMap(request.content)
            val root = Materializer(request.content, sourceMap, maxNodes).materialize(parsed.rootNode)
            return SyntaxParseResult.Success(
                TreeSitterSyntaxTree(
                    language = language,
                    root = root,
                    hasErrors = root.containsError(),
                ),
            )
        } catch (error: ParseBudgetExceeded) {
            return SyntaxParseResult.Failed(error.message ?: REASON_TOO_MANY_NODES)
        } catch (error: Throwable) {
            return SyntaxParseResult.Failed(error.message ?: error::class.java.simpleName.orEmpty(), error)
        } finally {
            tree?.close()
            parser.close()
        }
    }

    companion object {
        /** One file may take this long to parse before the native parser gives up. */
        const val DEFAULT_TIMEOUT_MILLIS: Long = 4_000L

        /** Copied nodes per file. Well above any hand-written source file. */
        const val DEFAULT_MAX_NODES: Int = 200_000

        private const val REASON_CANCELLED = "Parsing was cancelled"
        private const val REASON_TIMEOUT = "Parsing stopped before it finished (the file exceeds the parse budget)"
        private const val REASON_TOO_MANY_NODES = "The file has too many syntax nodes to analyse on device"
    }
}

/** Thrown internally to abandon a walk that exceeds the node budget. */
private class ParseBudgetExceeded(message: String) : RuntimeException(message)

/**
 * Copies a native tree into [TreeSitterNode]s.
 *
 * Only *named* nodes are copied: anonymous nodes are punctuation and keywords,
 * which carry no structure and are not part of the `:codeintel` node model.
 * Grammar field names are kept for the named children that have them, because
 * name resolution (`name`, `left`, …) depends on them.
 */
private class Materializer(
    private val source: String,
    private val sourceMap: TreeSitterSourceMap,
    private val maxNodes: Int,
) {

    private var copied = 0

    fun materialize(root: TSNode): SyntaxNode = build(root)

    private fun build(node: TSNode): SyntaxNode {
        copied++
        if (copied > maxNodes) {
            throw ParseBudgetExceeded(
                "The file has more than $maxNodes syntax nodes, so it was left unanalysed",
            )
        }

        val childCount = node.childCount
        var children: MutableList<SyntaxNode>? = null
        var fields: MutableMap<String, Int>? = null

        for (index in 0 until childCount) {
            val child = node.child(index) ?: continue
            if (child.isNull || !child.isNamed) continue
            val target = children ?: ArrayList<SyntaxNode>(4).also { children = it }
            node.getFieldNameForChild(index)?.let { field ->
                (fields ?: HashMap<String, Int>(4).also { fields = it })[field] = target.size
            }
            target += build(child)
        }

        val startByte = node.startByte
        val endByte = node.endByte
        return TreeSitterNode(
            source = source,
            type = node.type,
            isNamed = node.isNamed,
            isError = node.isError,
            isMissing = node.isMissing,
            startByte = startByte,
            endByte = endByte,
            start = sourceMap.positionOf(startByte),
            end = sourceMap.positionOf(endByte),
            children = children ?: emptyList(),
            fields = fields ?: emptyMap(),
        )
    }
}

/** A parsed file whose nodes are plain Kotlin objects. */
private class TreeSitterSyntaxTree(
    override val language: CodeLanguage,
    override val root: SyntaxNode,
    override val hasErrors: Boolean,
) : SyntaxTree

/**
 * One copied node.
 *
 * Offsets are indices into [source]. The Android tree-sitter runtime parses
 * UTF-16 strings (`parseString`), so tree-sitter's byte offsets are the
 * 16-bit units a Kotlin `String` is made of, which makes slicing exact without
 * re-encoding anything.
 */
private class TreeSitterNode(
    private val source: String,
    override val type: String,
    override val isNamed: Boolean,
    override val isError: Boolean,
    override val isMissing: Boolean,
    override val startByte: Int,
    override val endByte: Int,
    override val start: SourcePosition,
    override val end: SourcePosition,
    private val children: List<SyntaxNode>,
    private val fields: Map<String, Int>,
) : SyntaxNode {

    override val childCount: Int get() = children.size

    override fun child(index: Int): SyntaxNode? = children.getOrNull(index)

    override fun childByFieldName(name: String): SyntaxNode? = fields[name]?.let { index -> children.getOrNull(index) }

    override val text: String
        get() {
            val from = startByte.coerceIn(0, source.length)
            val to = endByte.coerceIn(from, source.length)
            return source.substring(from, to)
        }

    override fun toString(): String = "$type @$start"
}

/**
 * Offset-to-position mapping for one source text.
 *
 * Line starts are computed once per file, so resolving a position is a binary
 * search instead of a scan — this runs for every node of every parse.
 */
internal class TreeSitterSourceMap(private val source: String) {

    private val lineStarts: IntArray = lineStartsOf(source)

    fun positionOf(offset: Int): SourcePosition {
        val clamped = offset.coerceIn(0, source.length)
        var low = 0
        var high = lineStarts.size - 1
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (lineStarts[middle] <= clamped) low = middle else high = middle - 1
        }
        return SourcePosition(line = low + 1, column = clamped - lineStarts[low] + 1)
    }

    companion object {
        /** Index of the first character of every line; always starts with 0. */
        fun lineStartsOf(text: String): IntArray {
            val starts = ArrayList<Int>(text.count { it == '\n' } + 1)
            starts += 0
            text.forEachIndexed { index, char ->
                if (char == '\n') starts += index + 1
            }
            val result = IntArray(starts.size)
            starts.forEachIndexed { index, value -> result[index] = value }
            return result
        }
    }
}
