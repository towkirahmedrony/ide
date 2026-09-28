package com.agentx.app.codeintel

/** A resolved symbol name plus the node it came from, so its span is known. */
internal data class ResolvedName(val text: String, val node: SyntaxNode?)

/**
 * How one grammar maps onto [SymbolKind]s.
 *
 * A profile is data, not code: it names the grammar's node types and how to find
 * a declaration's name in them. Adding a language is therefore a profile plus a
 * parser — the extractor, the outline, the tools and the context integration are
 * untouched. Nothing is inferred from source text that a grammar does not
 * expose, so a profile can only report symbols the parser really identified.
 */
data class LanguageProfile(
    val language: CodeLanguage,
    /** Grammar node type to symbol kind. */
    val nodeKinds: Map<String, SymbolKind>,
    /** Grammar fields that hold a declaration's name, in order of preference. */
    val nameFields: List<String> = listOf("name"),
    /**
     * Wrapper node types to look through for a name, e.g. a JS declaration whose
     * name lives on its `variable_declarator` child.
     */
    val nameThrough: List<String> = emptyList(),
    /** Explicit name lookup for grammars that nest it (XML). */
    val nameResolver: ((SyntaxNode) -> SyntaxNode?)? = null,
    /**
     * Keywords that refine a kind, checked in the declaration's own header.
     * Kotlin declares classes, interfaces and enums with the same node type.
     */
    val keywordKinds: Map<String, SymbolKind> = emptyMap(),
    /** Node types that never become symbols (comments). */
    val ignored: Set<String> = DEFAULT_IGNORED,
    /** True when a callable inside a container is a METHOD rather than a FUNCTION. */
    val methodsInsideContainers: Boolean = true,
    /** Depth searched for an identifier-like name node. */
    val identifierDepth: Int = 3,
) {

    internal fun resolveName(node: SyntaxNode): ResolvedName? {
        nameResolver?.invoke(node)?.let { candidate -> candidate.asName()?.let { return it } }

        for (field in nameFields) {
            node.childByFieldName(field)?.asName()?.let { return it }
        }

        for (wrapper in nameThrough) {
            node.descendants().take(MAX_WRAPPER_SCAN).firstOrNull { it.type == wrapper }?.let { wrapperNode ->
                for (field in nameFields) {
                    wrapperNode.childByFieldName(field)?.asName()?.let { return it }
                }
                wrapperNode.firstIdentifier(identifierDepth)?.asName()?.let { return it }
            }
        }

        return node.firstIdentifier(identifierDepth)?.asName()
    }

    private fun SyntaxNode.asName(): ResolvedName? {
        val cleaned = cleanName(text)
        return if (cleaned.isEmpty()) null else ResolvedName(cleaned, this)
    }

    private fun SyntaxNode.firstIdentifier(maxDepth: Int): SyntaxNode? =
        SyntaxNodeNames.firstIdentifier(this, maxDepth)

    companion object {
        /** Bounds the pre-order scan for a wrapper node that carries the name. */
        private const val MAX_WRAPPER_SCAN = 12

        val DEFAULT_IGNORED: Set<String> = setOf(
            "comment",
            "line_comment",
            "block_comment",
            "documentation_comment",
        )

        /** Quotes and decoration that must not become part of a symbol name. */
        internal fun cleanName(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.length < 2) return trimmed
            val first = trimmed.first()
            val last = trimmed.last()
            val quoted = (first == '"' && last == '"') ||
                (first == '\'' && last == '\'') ||
                (first == '`' && last == '`')
            return if (quoted) trimmed.substring(1, trimmed.length - 1).trim() else trimmed
        }
    }
}

/** Identifier-like grammar node types shared by the grammars this build ships. */
internal object SyntaxNodeNames {

    private val IDENTIFIER_TYPES = Regex(
        "^(simple_identifier|identifier|type_identifier|property_identifier|field_identifier|" +
            "constant|word|Name|attribute_name|namespace_identifier)$",
    )

    /**
     * Breadth-first search for the first identifier-like node, so the shallowest
     * (and for declarations, left-most) name wins. Depth-bounded: a pathological
     * tree cannot turn this into a full scan.
     */
    fun firstIdentifier(node: SyntaxNode, maxDepth: Int): SyntaxNode? {
        var level = node.namedChildren().toList()
        var depth = 0
        while (level.isNotEmpty() && depth <= maxDepth) {
            level.firstOrNull { isIdentifierLike(it) }?.let { return it }
            level = level.flatMap { it.namedChildren().toList() }
            depth++
        }
        return null
    }

    fun isIdentifierLike(node: SyntaxNode): Boolean = IDENTIFIER_TYPES.matches(node.type)
}

/**
 * The grammars this build understands, described as data.
 *
 * Node type names come from the grammar each language is parsed with; a type
 * that a grammar does not produce is simply never matched, so a wrong entry
 * degrades to "no symbol" instead of inventing one.
 */
object LanguageProfiles {

    fun forLanguage(language: CodeLanguage): LanguageProfile? = when (language) {
        CodeLanguage.KOTLIN -> KOTLIN
        CodeLanguage.JAVA -> JAVA
        CodeLanguage.PYTHON -> PYTHON
        CodeLanguage.JAVASCRIPT, CodeLanguage.JSX -> JAVASCRIPT
        CodeLanguage.TYPESCRIPT, CodeLanguage.TSX -> TYPESCRIPT
        CodeLanguage.JSON -> JSON
        CodeLanguage.XML -> XML
        else -> null
    }

    val KOTLIN: LanguageProfile = LanguageProfile(
        language = CodeLanguage.KOTLIN,
        nodeKinds = mapOf(
            "class_declaration" to SymbolKind.CLASS,
            "object_declaration" to SymbolKind.OBJECT,
            "function_declaration" to SymbolKind.FUNCTION,
            "property_declaration" to SymbolKind.PROPERTY,
            "type_alias" to SymbolKind.TYPE,
            "import_header" to SymbolKind.IMPORT,
            "package_header" to SymbolKind.NAMESPACE,
        ),
        // Interfaces and enums are declared with `class_declaration` in this
        // grammar, so the header keyword decides the kind.
        keywordKinds = mapOf(
            "interface" to SymbolKind.INTERFACE,
            "enum" to SymbolKind.ENUM,
            "annotation" to SymbolKind.CLASS,
        ),
        nameThrough = listOf("variable_declaration"),
    )

    val JAVA: LanguageProfile = LanguageProfile(
        language = CodeLanguage.JAVA,
        nodeKinds = mapOf(
            "class_declaration" to SymbolKind.CLASS,
            "record_declaration" to SymbolKind.CLASS,
            "interface_declaration" to SymbolKind.INTERFACE,
            "annotation_type_declaration" to SymbolKind.INTERFACE,
            "enum_declaration" to SymbolKind.ENUM,
            "method_declaration" to SymbolKind.METHOD,
            "constructor_declaration" to SymbolKind.CONSTRUCTOR,
            "field_declaration" to SymbolKind.PROPERTY,
            "import_declaration" to SymbolKind.IMPORT,
            "package_declaration" to SymbolKind.NAMESPACE,
        ),
        nameThrough = listOf("variable_declarator"),
    )

    val PYTHON: LanguageProfile = LanguageProfile(
        language = CodeLanguage.PYTHON,
        nodeKinds = mapOf(
            "class_definition" to SymbolKind.CLASS,
            "function_definition" to SymbolKind.FUNCTION,
            "import_statement" to SymbolKind.IMPORT,
            "import_from_statement" to SymbolKind.IMPORT,
            "assignment" to SymbolKind.VARIABLE,
        ),
        nameFields = listOf("name", "left"),
    )

    val JAVASCRIPT: LanguageProfile = LanguageProfile(
        language = CodeLanguage.JAVASCRIPT,
        nodeKinds = mapOf(
            "class_declaration" to SymbolKind.CLASS,
            "function_declaration" to SymbolKind.FUNCTION,
            "generator_function_declaration" to SymbolKind.FUNCTION,
            "method_definition" to SymbolKind.METHOD,
            "public_field_definition" to SymbolKind.PROPERTY,
            "lexical_declaration" to SymbolKind.VARIABLE,
            "variable_declaration" to SymbolKind.VARIABLE,
            "import_statement" to SymbolKind.IMPORT,
        ),
        nameThrough = listOf("variable_declarator"),
    )

    val TYPESCRIPT: LanguageProfile = LanguageProfile(
        language = CodeLanguage.TYPESCRIPT,
        nodeKinds = mapOf(
            "class_declaration" to SymbolKind.CLASS,
            "abstract_class_declaration" to SymbolKind.CLASS,
            "interface_declaration" to SymbolKind.INTERFACE,
            "enum_declaration" to SymbolKind.ENUM,
            "type_alias_declaration" to SymbolKind.TYPE,
            "function_declaration" to SymbolKind.FUNCTION,
            "generator_function_declaration" to SymbolKind.FUNCTION,
            "method_definition" to SymbolKind.METHOD,
            "public_field_definition" to SymbolKind.PROPERTY,
            "lexical_declaration" to SymbolKind.VARIABLE,
            "variable_declaration" to SymbolKind.VARIABLE,
            "import_statement" to SymbolKind.IMPORT,
            "internal_module" to SymbolKind.NAMESPACE,
        ),
        nameThrough = listOf("variable_declarator"),
    )

    val JSON: LanguageProfile = LanguageProfile(
        language = CodeLanguage.JSON,
        nodeKinds = mapOf("pair" to SymbolKind.PROPERTY),
        nameFields = listOf("key", "name"),
    )

    val XML: LanguageProfile = LanguageProfile(
        language = CodeLanguage.XML,
        nodeKinds = mapOf("element" to SymbolKind.OBJECT),
        // An element's name lives in its start tag: (element (STag (Name))).
        nameResolver = { element ->
            element.namedChildren()
                .firstOrNull { it.type == "STag" }
                ?.namedChildren()
                ?.firstOrNull { it.type == "Name" }
        },
    )
}
