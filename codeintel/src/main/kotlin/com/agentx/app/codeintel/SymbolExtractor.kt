package com.agentx.app.codeintel

/** Extraction output before it becomes a [ParsedFile]. */
internal data class ExtractedStructure(
    val symbols: List<CodeSymbol>,
    val roots: List<OutlineNode>,
    val truncated: Boolean,
)

/**
 * Walks a syntax tree and turns it into symbols and an outline.
 *
 * The walk is grammar-agnostic: it asks [LanguageProfile] which node types are
 * declarations and how to find their names. Nodes that are not declarations are
 * transparent, so a symbol's children are the symbols inside it regardless of
 * how many intermediate nodes (bodies, blocks, statement lists) the grammar
 * puts in between.
 *
 * Everything is bounded: symbol count, nesting depth and signature length. A
 * generated or pathological file therefore produces a truncated outline instead
 * of an unbounded result.
 */
internal class SymbolExtractor(private val limits: CodeIntelligenceLimits) {

    fun extract(tree: SyntaxTree, path: String): ExtractedStructure {
        val profile = LanguageProfiles.forLanguage(tree.language)
            ?: return ExtractedStructure(emptyList(), emptyList(), truncated = false)

        val roots = mutableListOf<OutlineBuilder>()
        val flat = mutableListOf<CodeSymbol>()
        var truncated = false

        fun visit(node: SyntaxNode, parent: CodeSymbol?, branch: MutableList<OutlineBuilder>, depth: Int) {
            if (truncated) return
            if (flat.size >= limits.maxSymbols || depth > limits.maxDepth) {
                truncated = true
                return
            }
            if (!node.isNamed || node.type in profile.ignored) return

            var container = parent
            var target = branch
            profile.nodeKinds[node.type]?.let { baseKind ->
                val symbol = toSymbol(node, baseKind, profile, tree.language, path, parent)
                if (symbol != null) {
                    val builder = OutlineBuilder(symbol)
                    target += builder
                    flat += symbol
                    container = symbol
                    target = builder.children
                }
            }

            node.namedChildren().forEach { child -> visit(child, container, target, depth + 1) }
        }

        visit(tree.root, null, roots, depth = 0)
        return ExtractedStructure(
            symbols = flat,
            roots = roots.map { it.build() },
            truncated = truncated,
        )
    }

    private fun toSymbol(
        node: SyntaxNode,
        baseKind: SymbolKind,
        profile: LanguageProfile,
        language: CodeLanguage,
        path: String,
        parent: CodeSymbol?,
    ): CodeSymbol? {
        val kind = refineKind(node, baseKind, profile, parent)
        val name = resolveText(node, kind, profile) ?: return null
        if (name.isBlank()) return null

        val nameRange = if (kind == SymbolKind.IMPORT || kind == SymbolKind.NAMESPACE) {
            null
        } else {
            profile.resolveName(node)?.node?.let { nameNode -> nameNode.span() }
        }

        return CodeSymbol(
            name = name,
            kind = kind,
            language = language,
            path = path,
            range = node.span(),
            nameRange = nameRange,
            parent = parent?.name,
            signature = signatureOf(node, kind),
        )
    }

    /**
     * Imports and namespaces read better as their own text than as an
     * identifier: `import com.agentx.app.Foo`, not `com`.
     */
    private fun resolveText(node: SyntaxNode, kind: SymbolKind, profile: LanguageProfile): String? =
        when (kind) {
            SymbolKind.IMPORT, SymbolKind.NAMESPACE ->
                node.text.lineSequence().firstOrNull()?.let { LanguageProfile.cleanName(it) }
            else -> profile.resolveName(node)?.text
        }

    /**
     * Refines a kind with what the declaration itself says: a Kotlin
     * `class_declaration` is an interface or an enum when its header says so, and
     * a callable inside a container is a method.
     */
    private fun refineKind(
        node: SyntaxNode,
        baseKind: SymbolKind,
        profile: LanguageProfile,
        parent: CodeSymbol?,
    ): SymbolKind {
        val declared = if (profile.keywordKinds.isEmpty()) {
            baseKind
        } else {
            val words = headerWords(node)
            profile.keywordKinds.entries.firstOrNull { it.key in words }?.value ?: baseKind
        }
        val insideContainer = parent?.kind?.isContainer == true
        return if (
            declared == SymbolKind.FUNCTION &&
            profile.methodsInsideContainers &&
            insideContainer
        ) {
            SymbolKind.METHOD
        } else {
            declared
        }
    }

    /** Distinct words of a declaration's header (everything before its body). */
    private fun headerWords(node: SyntaxNode): Set<String> =
        node.text.take(HEADER_SCAN_CHARS)
            .substringBefore('{')
            .split(HEADER_SEPARATOR)
            .filter { it.isNotEmpty() }
            .toSet()

    private fun signatureOf(node: SyntaxNode, kind: SymbolKind): String? {
        val meaningful = kind.isContainer || kind.isCallable || kind == SymbolKind.TYPE
        if (!meaningful) return null
        val header = node.text.take(HEADER_SCAN_CHARS)
            .lineSequence()
            .firstOrNull()
            ?.substringBefore('{')
            ?.trim()
            .orEmpty()
        if (header.isEmpty()) return null
        return header.replace(WHITESPACE, " ").take(limits.maxSignatureChars).takeIf { it.isNotEmpty() }
    }

    private companion object {
        const val HEADER_SCAN_CHARS = 400

        val HEADER_SEPARATOR = Regex("[^A-Za-z0-9_]+")
        val WHITESPACE = Regex("\\s+")
    }
}

/** Mutable outline node, converted once the walk is done. */
internal class OutlineBuilder(val symbol: CodeSymbol) {
    val children: MutableList<OutlineBuilder> = mutableListOf()

    fun build(): OutlineNode = OutlineNode(symbol = symbol, children = children.map { it.build() })
}
