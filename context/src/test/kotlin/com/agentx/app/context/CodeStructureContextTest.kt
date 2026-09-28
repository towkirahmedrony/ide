package com.agentx.app.context

import com.agentx.app.codeintel.CodeIntelligence
import com.agentx.app.codeintel.CodeLanguage
import com.agentx.app.codeintel.DefaultCodeIntelligence
import com.agentx.app.codeintel.InMemorySyntaxNode
import com.agentx.app.codeintel.InMemorySyntaxTree
import com.agentx.app.codeintel.ParseRequest
import com.agentx.app.codeintel.SourcePosition
import com.agentx.app.codeintel.SyntaxNode
import com.agentx.app.codeintel.SyntaxParseResult
import com.agentx.app.codeintel.SyntaxParser
import com.agentx.app.codeintel.SyntaxParserProvider
import com.agentx.app.codeintel.SyntaxTree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The provider is tested against the real engine and the real extractor, with an
 * in-memory tree standing in for a grammar. Nothing here needs a native parser
 * or a device.
 */
class CodeStructureContextTest {

    @Test
    fun `the selected file contributes its structure`() = runTest {
        val files = TestWorkspaceFileSystem(mapOf("src/Main.kt" to "class UserRepository { }"))
        val workspace = TestWorkspaceContextProvider(WorkspaceSnapshot(name = "demo"), files)
        val engine = contextEngine(workspace, CodeStructureContextProvider(kotlinIntelligence(), workspace))

        val result = engine.buildContext(
            ContextRequest(task = "explain the user repository", selectedFile = "src/Main.kt"),
        )

        val structure = result.itemsOf(ContextSource.CODE_STRUCTURE).single()
        assertEquals("structure:src/Main.kt", structure.id)
        assertEquals("Kotlin", structure.metadata.attribute("language"))
        assertEquals("3", structure.metadata.attribute("symbols"))
        assertEquals(ContextPriority.NORMAL, structure.priority)
        assertEquals(ContextRelevance.CODE_STRUCTURE, structure.relevance)
        assertTrue(structure.content.contains("class UserRepository"), structure.content)
        assertTrue(structure.content.contains("method getUser()"), structure.content)
        assertTrue(structure.content.contains("line 3"), structure.content)
        // Structure is additive: the file text is still there for the model.
        assertTrue(result.itemsOf(ContextSource.FILE).any { it.path == "src/Main.kt" })
    }

    @Test
    fun `only the files the request points at are described, capped`() = runTest {
        val files = TestWorkspaceFileSystem(
            mapOf("a.kt" to "class A", "b.kt" to "class B", "c.kt" to "class C"),
        )
        val workspace = TestWorkspaceContextProvider(WorkspaceSnapshot(name = "demo"), files)
        val engine = contextEngine(workspace, CodeStructureContextProvider(kotlinIntelligence(), workspace))

        val result = engine.buildContext(
            ContextRequest(
                task = "compare them",
                selectedFile = "a.kt",
                mentionedFiles = listOf("b.kt", "c.kt"),
            ),
        )

        assertEquals(
            listOf("structure:a.kt", "structure:b.kt"),
            result.itemsOf(ContextSource.CODE_STRUCTURE).map { it.id },
        )
    }

    @Test
    fun `a language without a parser adds no structure and does not fail`() = runTest {
        val files = TestWorkspaceFileSystem(mapOf("src/app.ts" to "export const x = 1"))
        val workspace = TestWorkspaceContextProvider(WorkspaceSnapshot(name = "demo"), files)
        val engine = contextEngine(workspace, CodeStructureContextProvider(kotlinIntelligence(), workspace))

        val result = engine.buildContext(ContextRequest(task = "review", selectedFile = "src/app.ts"))

        assertTrue(result.itemsOf(ContextSource.CODE_STRUCTURE).isEmpty())
    }

    @Test
    fun `a protected path is never read for structure`() = runTest {
        val files = TestWorkspaceFileSystem(mapOf(".env" to "API_KEY=secret"))
        val workspace = TestWorkspaceContextProvider(WorkspaceSnapshot(name = "demo"), files)
        val engine = contextEngine(workspace, CodeStructureContextProvider(kotlinIntelligence(), workspace))

        val result = engine.buildContext(ContextRequest(task = "what is configured", selectedFile = ".env"))

        assertTrue(result.itemsOf(ContextSource.CODE_STRUCTURE).isEmpty())
        assertTrue(files.readPaths.isEmpty())
    }

    @Test
    fun `no workspace means no structure, and no exception`() = runTest {
        val workspace = TestWorkspaceContextProvider(WorkspaceSnapshot(name = "demo"), null)
        val engine = contextEngine(workspace, CodeStructureContextProvider(kotlinIntelligence(), workspace))

        val result = engine.buildContext(ContextRequest(task = "hello", selectedFile = "src/Main.kt"))

        assertTrue(result.itemsOf(ContextSource.CODE_STRUCTURE).isEmpty())
    }

    @Test
    fun `structure is bounded and says when it was cut`() = runTest {
        val files = TestWorkspaceFileSystem(mapOf("src/Big.kt" to "class Big { }"))
        val workspace = TestWorkspaceContextProvider(WorkspaceSnapshot(name = "demo"), files)
        val provider = CodeStructureContextProvider(
            codeIntelligence = kotlinIntelligence(bigKotlinTree(40)),
            workspace = workspace,
            limits = CodeStructureContextProvider.Limits(
                maxFiles = 1,
                maxSymbolsPerFile = 3,
                maxCharsPerFile = 60,
            ),
        )
        val engine = contextEngine(workspace, provider)

        val result = engine.buildContext(ContextRequest(task = "summarise", selectedFile = "src/Big.kt"))

        val structure = result.itemsOf(ContextSource.CODE_STRUCTURE).single()
        assertTrue(structure.content.length <= 60, "content=${structure.content.length}")
        assertTrue(structure.truncated, structure.content)
        assertEquals("41", structure.metadata.attribute("symbols"))
    }

    // --- helpers ------------------------------------------------------------

    private fun contextEngine(workspace: WorkspaceContextProvider, provider: ContextProvider): DefaultContextEngine =
        DefaultContextEngine(
            workspace = workspace,
            providers = listOf(provider),
            dispatcher = Dispatchers.Unconfined,
            clock = { FIXED_NOW },
        )

    private fun kotlinIntelligence(tree: SyntaxTree = kotlinTree()): CodeIntelligence =
        DefaultCodeIntelligence(parsers = SingleLanguageParserProvider(tree), dispatcher = Dispatchers.Unconfined)
}

/** Provider that answers for Kotlin only. */
private class SingleLanguageParserProvider(private val tree: SyntaxTree) : SyntaxParserProvider {

    override fun supportedLanguages(): Set<CodeLanguage> = setOf(CodeLanguage.KOTLIN)

    override fun parserFor(language: CodeLanguage): SyntaxParser? {
        if (language != CodeLanguage.KOTLIN) return null
        return object : SyntaxParser {
            override val language: CodeLanguage get() = CodeLanguage.KOTLIN

            override fun parse(request: ParseRequest): SyntaxParseResult =
                SyntaxParseResult.Success(tree)
        }
    }
}

private fun leaf(type: String, text: String, line: Int): SyntaxNode = InMemorySyntaxNode(
    type = type,
    text = text,
    start = SourcePosition(line, 1),
    end = SourcePosition(line, 1 + text.length),
)

private fun body(
    type: String,
    text: String,
    firstLine: Int,
    lastLine: Int,
    children: List<SyntaxNode>,
    fields: Map<String, Int> = emptyMap(),
): SyntaxNode = InMemorySyntaxNode(
    type = type,
    text = text,
    start = SourcePosition(firstLine, 1),
    end = SourcePosition(lastLine, 1),
    children = children,
    fields = fields,
)

/** class UserRepository with a property and one method. */
private fun kotlinTree(): SyntaxTree {
    val classBody = body(
        type = "class_body",
        text = "body",
        firstLine = 2,
        lastLine = 4,
        children = listOf(
            body(
                type = "property_declaration",
                text = "private val client: String",
                firstLine = 2,
                lastLine = 2,
                children = listOf(
                    leaf("simple_identifier", "client", 2),
                    leaf("type_identifier", "String", 2),
                ),
            ),
            body(
                type = "function_declaration",
                text = "fun getUser(): String {",
                firstLine = 3,
                lastLine = 4,
                children = listOf(leaf("simple_identifier", "getUser", 3)),
                fields = mapOf("name" to 0),
            ),
        ),
    )
    val declaration = body(
        type = "class_declaration",
        text = "class UserRepository {",
        firstLine = 1,
        lastLine = 4,
        children = listOf(leaf("type_identifier", "UserRepository", 1), classBody),
        fields = mapOf("name" to 0),
    )
    return InMemorySyntaxTree(
        language = CodeLanguage.KOTLIN,
        root = body(
            type = "source_file",
            text = "source",
            firstLine = 1,
            lastLine = 4,
            children = listOf(declaration),
        ),
    )
}

/** A class with [functions] methods, to prove the outline stays bounded. */
private fun bigKotlinTree(functions: Int): SyntaxTree {
    val members = (1..functions).map { index ->
        body(
            type = "function_declaration",
            text = "fun method$index(): Unit {",
            firstLine = index + 1,
            lastLine = index + 1,
            children = listOf(leaf("simple_identifier", "method$index", index + 1)),
            fields = mapOf("name" to 0),
        )
    }
    val declaration = body(
        type = "class_declaration",
        text = "class Big {",
        firstLine = 1,
        lastLine = functions + 1,
        children = listOf(leaf("type_identifier", "Big", 1), body("class_body", "body", 2, functions + 1, members)),
        fields = mapOf("name" to 0),
    )
    return InMemorySyntaxTree(
        language = CodeLanguage.KOTLIN,
        root = body("source_file", "source", 1, functions + 1, listOf(declaration)),
    )
}
