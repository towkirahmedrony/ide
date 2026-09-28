package com.agentx.app.codeintel

import com.agentx.app.core.ForgeError
import com.agentx.app.core.ForgeResult
import kotlinx.coroutines.Dispatchers

/**
 * Fixtures for the code intelligence tests.
 *
 * The tests build trees in memory instead of shipping native grammars onto the
 * JVM test classpath: they exercise detection, extraction, caching, error paths
 * and the integration with the editor, the Context Engine and the tools — all of
 * which are parser-independent by design. Grammar-specific behaviour (whether a
 * real Kotlin file is parsed exactly like this) is verified on device, where the
 * tree-sitter gammars live.
 */

/** One node of a fixture tree, before positions are assigned. */
internal class NodeSpec(
    val type: String,
    val text: String,
    /** Grammar field name this node has in its parent. */
    val field: String?,
    val isError: Boolean,
    val children: List<NodeSpec>,
)

internal fun spec(
    type: String,
    text: String = "",
    field: String? = null,
    isError: Boolean = false,
    children: List<NodeSpec> = emptyList(),
): NodeSpec = NodeSpec(type, text, field, isError, children)

private class Layout(val line: Int, val endLine: Int, val text: String, val endColumn: Int)

/**
 * Builds an in-memory tree from [root].
 *
 * Positions are assigned deterministically: every leaf gets its own line, and a
 * parent spans its first to its last leaf, so a parent always contains its
 * children — the property the outline and symbol-at-cursor logic depend on.
 */
internal fun fixtureTree(language: CodeLanguage, root: NodeSpec): SyntaxTree {
    var nextLine = 1

    fun build(current: NodeSpec): Pair<SyntaxNode, Layout> {
        if (current.children.isEmpty()) {
            val line = nextLine++
            val node = InMemorySyntaxNode(
                type = current.type,
                text = current.text,
                isError = current.isError,
                start = SourcePosition(line, 1),
                end = SourcePosition(line, 1 + current.text.length),
            )
            return node to Layout(line, line, current.text, 1 + current.text.length)
        }

        val built = current.children.map { child -> build(child) }
        val first = built.first().second
        val last = built.last().second
        val text = current.text.ifEmpty { built.joinToString(" ") { entry -> entry.second.text } }
        val node = InMemorySyntaxNode(
            type = current.type,
            text = text,
            isError = current.isError,
            start = SourcePosition(first.line, 1),
            end = SourcePosition(last.endLine, last.endColumn),
            children = built.map { entry -> entry.first },
            fields = current.children
                .mapIndexedNotNull { index, child -> child.field?.let { name -> name to index } }
                .toMap(),
        )
        return node to Layout(first.line, last.endLine, text, last.endColumn)
    }

    val (rootNode, _) = build(root)
    return InMemorySyntaxTree(language = language, root = rootNode)
}

/** Backend that answers from a map. Counts parses so caching can be asserted. */
internal class FakeSyntaxParserProvider(
    private val factories: Map<CodeLanguage, (ParseRequest) -> SyntaxParseResult>,
) : SyntaxParserProvider {

    private val calls = mutableListOf<String>()

    val parseCount: Int get() = synchronized(calls) { calls.size }

    val parsedPaths: List<String> get() = synchronized(calls) { calls.toList() }

    override fun supportedLanguages(): Set<CodeLanguage> = factories.keys

    override fun parserFor(language: CodeLanguage): SyntaxParser? {
        val factory = factories[language] ?: return null
        val target = language
        return object : SyntaxParser {
            override val language: CodeLanguage get() = target

            override fun parse(request: ParseRequest): SyntaxParseResult {
                synchronized(calls) { calls += request.path }
                return factory(request)
            }
        }
    }

    companion object {
        /** Always returns [tree] for the given language. */
        fun returning(language: CodeLanguage, tree: SyntaxTree): FakeSyntaxParserProvider =
            FakeSyntaxParserProvider(mapOf(language to { _: ParseRequest -> SyntaxParseResult.Success(tree) }))

        /** Returns a different tree per language. */
        fun returning(trees: Map<CodeLanguage, SyntaxTree>): FakeSyntaxParserProvider =
            FakeSyntaxParserProvider(
                trees.mapValues { (_, tree) -> { _: ParseRequest -> SyntaxParseResult.Success(tree) } },
            )

        /** A backend whose parser throws, as a broken native library would. */
        fun failing(language: CodeLanguage): FakeSyntaxParserProvider = FakeSyntaxParserProvider(
            mapOf(language to { _: ParseRequest -> throw IllegalStateException("native parser unavailable") }),
        )

        /** A backend that reports the language as unsupported at parse time. */
        fun unsupportedAtParseTime(language: CodeLanguage): FakeSyntaxParserProvider = FakeSyntaxParserProvider(
            mapOf(language to { _: ParseRequest -> SyntaxParseResult.Unsupported(language) }),
        )
    }
}

/** Unwraps a successful result, or fails the test with the error it carried. */
internal fun <T> ForgeResult<T, ForgeError>.valueOrFail(): T = when (this) {
    is ForgeResult.Success -> value
    is ForgeResult.Failure -> throw AssertionError("expected success but was ${error.code}: ${error.message}")
}

/** Unwraps a failed result, or fails the test when the call succeeded. */
internal fun <T> ForgeResult<T, ForgeError>.errorOrFail(): ForgeError = when (this) {
    is ForgeResult.Failure -> error
    is ForgeResult.Success -> throw AssertionError("expected a failure but got a value")
}

/** Engine wired for tests: no dispatch hop, so assertions stay deterministic. */
internal fun testEngine(
    provider: SyntaxParserProvider = NoSyntaxParsers,
    limits: CodeIntelligenceLimits = CodeIntelligenceLimits.DEFAULT,
): DefaultCodeIntelligence = DefaultCodeIntelligence(
    parsers = provider,
    limits = limits,
    dispatcher = Dispatchers.Unconfined,
)

/** A Kotlin-tree fixture shaped like the tree-sitter Kotlin grammar's output. */
internal fun kotlinFixture(): SyntaxTree = fixtureTree(
    CodeLanguage.KOTLIN,
    spec(
        "source_file",
        children = listOf(
            spec(
                "package_header",
                "package com.demo",
                children = listOf(spec("identifier", "com.demo")),
            ),
            spec(
                "import_header",
                "import kotlin.math.max",
                children = listOf(
                    spec("identifier", "kotlin.math"),
                    spec("simple_identifier", "max"),
                ),
            ),
            spec(
                "class_declaration",
                "class UserRepository(private val client: String) {",
                children = listOf(
                    spec("type_identifier", "UserRepository", field = "name"),
                    spec(
                        "class_body",
                        children = listOf(
                            spec(
                                "property_declaration",
                                "private val client: String",
                                children = listOf(
                                    spec("simple_identifier", "client"),
                                    spec("type_identifier", "String"),
                                ),
                            ),
                            spec(
                                "function_declaration",
                                "fun getUser(): String {",
                                children = listOf(
                                    spec("simple_identifier", "getUser", field = "name"),
                                    spec("function_body", children = listOf(spec("simple_identifier", "return"))),
                                ),
                            ),
                            spec(
                                "function_declaration",
                                "fun updateUser(): Unit {",
                                children = listOf(
                                    spec("simple_identifier", "updateUser", field = "name"),
                                    spec("function_body", children = listOf(spec("simple_identifier", "getUser"))),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    ),
)

/** A TypeScript-tree fixture: interface, function and a const declaration. */
internal fun typeScriptFixture(): SyntaxTree = fixtureTree(
    CodeLanguage.TYPESCRIPT,
    spec(
        "program",
        children = listOf(
            spec("import_statement", "import { helper } from './helper'"),
            spec(
                "interface_declaration",
                "interface User {",
                children = listOf(
                    spec("type_identifier", "User", field = "name"),
                    spec("object_type", children = listOf(spec("property_signature", "name: string"))),
                ),
            ),
            spec(
                "function_declaration",
                "function greet(name: string): string {",
                children = listOf(
                    spec("identifier", "greet", field = "name"),
                    spec("formal_parameters", children = listOf(spec("required_parameter", "name: string"))),
                    spec("statement_block", children = listOf(spec("return_statement", "return name"))),
                ),
            ),
            spec(
                "lexical_declaration",
                "const limit = 10",
                children = listOf(
                    spec(
                        "variable_declarator",
                        "limit = 10",
                        children = listOf(
                            spec("identifier", "limit", field = "name"),
                            spec("number", "10"),
                        ),
                    ),
                ),
            ),
        ),
    ),
)

/** A Python-tree fixture: import, class with a method, and a function. */
internal fun pythonFixture(): SyntaxTree = fixtureTree(
    CodeLanguage.PYTHON,
    spec(
        "module",
        children = listOf(
            spec("import_statement", "import os"),
            spec(
                "class_definition",
                "class Repo:",
                children = listOf(
                    spec("identifier", "Repo", field = "name"),
                    spec(
                        "block",
                        children = listOf(
                            spec(
                                "function_definition",
                                "def get(self):",
                                children = listOf(
                                    spec("identifier", "get", field = "name"),
                                    spec("parameters", children = listOf(spec("identifier", "self"))),
                                    spec("block", children = listOf(spec("pass_statement", "pass"))),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            spec(
                "function_definition",
                "def main():",
                children = listOf(
                    spec("identifier", "main", field = "name"),
                    spec("parameters"),
                    spec("block", children = listOf(spec("pass_statement", "pass"))),
                ),
            ),
        ),
    ),
)

/** A tree whose second statement failed to parse, as while typing. */
internal fun malformedKotlinFixture(): SyntaxTree = fixtureTree(
    CodeLanguage.KOTLIN,
    spec(
        "source_file",
        children = listOf(
            spec(
                "class_declaration",
                "class Broken(",
                children = listOf(spec("type_identifier", "Broken", field = "name")),
            ),
            spec("ERROR", "}}}", isError = true),
        ),
    ),
)
