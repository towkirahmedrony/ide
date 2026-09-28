package com.agentx.app.tools

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
import com.agentx.app.workspace.WorkspaceFileSystem
import com.agentx.app.workspace.memory.InMemoryWorkspaceFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The structural tools go through the real registry, router, permission policy
 * and executor — the same path the agent uses.
 */
class CodeIntelligenceToolsTest {

    private val context = ToolExecutionContext(
        workspaceId = "w1",
        grantedPermissions = setOf(ToolPermissionLevel.READ_ONLY),
    )

    private val files: WorkspaceFileSystem = InMemoryWorkspaceFileSystem(
        mapOf(
            "src/UserRepository.kt" to "class UserRepository { }",
            "src/app.ts" to "export const x = 1",
        ),
    )

    private fun <T> runSuspend(block: suspend () -> T): T = runBlocking { block() }

    private fun router(
        tree: SyntaxTree = kotlinTree(),
        workspace: WorkspaceFileSystem? = files,
    ): ToolRouter {
        val registry = DefaultToolRegistry()
        val intelligence = DefaultCodeIntelligence(
            parsers = SingleLanguageParserProvider(tree),
            dispatcher = Dispatchers.Unconfined,
        )
        BuiltinTools
            .codeIntelligence(WorkspaceFileSystemResolver { workspace }, intelligence)
            .forEach(registry::register)
        return DefaultToolRouter(registry = registry)
    }

    private fun invoke(
        toolName: String,
        arguments: Map<String, JsonValue> = emptyMap(),
        tree: SyntaxTree = kotlinTree(),
        workspace: WorkspaceFileSystem? = files,
    ): ToolResult = runSuspend {
        router(tree = tree, workspace = workspace).invoke(toolName, ToolInput(arguments), context)
    }

    private fun path(value: String): Map<String, JsonValue> = mapOf("path" to Json.of(value))

    // --- registration -------------------------------------------------------

    @Test
    fun `the structural tools are read-only filesystem tools`() {
        val definitions = BuiltinTools.codeIntelligence(
            WorkspaceFileSystemResolver { null },
            DefaultCodeIntelligence(dispatcher = Dispatchers.Unconfined),
        ).map { it.definition }

        assertEquals(
            listOf("get_file_symbols", "get_file_outline", "find_definition", "find_references"),
            definitions.map { it.name },
        )
        definitions.forEach { definition ->
            assertEquals(ToolPermissionDecision.ALLOW, definition.permission, definition.name)
            assertTrue(ToolCapability.READ_ONLY in definition.capabilities, definition.name)
            assertEquals(setOf(ToolPermissionLevel.READ_ONLY), definition.requiredPermissions)
            assertEquals("none", definition.metadata["sideEffects"])
        }
    }

    // --- symbols and outline ------------------------------------------------

    @Test
    fun `get_file_symbols returns the declarations of a file`() {
        val output = assertIs<ToolResult.Success>(
            invoke("get_file_symbols", path("src/UserRepository.kt")),
        ).output

        assertEquals("Kotlin", output.content.stringOrNull("language"))
        assertEquals(4.0, output.content.numberOrNull("count"))
        assertEquals(4, output.content["symbols"]?.arrayOrNull()?.size)
        assertTrue(output.displayText.orEmpty().contains("class UserRepository"))
        assertTrue(output.displayText.orEmpty().contains("method UserRepository.getUser()"))
    }

    @Test
    fun `get_file_symbols can filter by kind`() {
        val output = assertIs<ToolResult.Success>(
            invoke(
                "get_file_symbols",
                path("src/UserRepository.kt") + ("kind" to Json.of("method")),
            ),
        ).output

        assertEquals(2.0, output.content.numberOrNull("count"))
        assertEquals(
            listOf("getUser", "updateUser"),
            output.content["symbols"]?.arrayOrNull().orEmpty()
                .mapNotNull { it.objectOrNull()?.stringOrNull("name") },
        )
    }

    @Test
    fun `an unknown symbol kind is rejected`() {
        val result = invoke(
            "get_file_symbols",
            path("src/UserRepository.kt") + ("kind" to Json.of("sausage")),
        )

        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, assertIs<ToolResult.Failure>(result).error.code)
    }

    @Test
    fun `get_file_outline renders the nested outline`() {
        val output = assertIs<ToolResult.Success>(
            invoke("get_file_outline", path("src/UserRepository.kt")),
        ).output

        assertTrue(output.content.stringOrNull("outline").orEmpty().contains("class UserRepository"))
        assertEquals(4.0, output.content.numberOrNull("symbolCount"))
    }

    // --- lookups ------------------------------------------------------------

    @Test
    fun `find_definition finds a declaration by name`() {
        val output = assertIs<ToolResult.Success>(
            invoke("find_definition", path("src/UserRepository.kt") + ("name" to Json.of("getUser"))),
        ).output

        assertEquals(1.0, output.content.numberOrNull("count"))
        assertTrue(output.displayText.orEmpty().contains("getUser"))
    }

    @Test
    fun `find_definition reports an empty result instead of failing`() {
        val output = assertIs<ToolResult.Success>(
            invoke("find_definition", path("src/UserRepository.kt") + ("name" to Json.of("missing"))),
        ).output

        assertEquals(0.0, output.content.numberOrNull("count"))
        assertTrue(output.displayText.orEmpty().contains("No declaration"))
    }

    @Test
    fun `find_references counts usages and marks the declaration`() {
        val output = assertIs<ToolResult.Success>(
            invoke("find_references", path("src/UserRepository.kt") + ("name" to Json.of("getUser"))),
        ).output

        assertEquals(2.0, output.content.numberOrNull("count"))
        assertEquals(1.0, output.content.numberOrNull("declarations"))
    }

    @Test
    fun `a blank name is rejected`() {
        val result = invoke("find_references", path("src/UserRepository.kt") + ("name" to Json.of("   ")))

        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, assertIs<ToolResult.Failure>(result).error.code)
    }

    // --- validation and failure paths ---------------------------------------

    @Test
    fun `a language without a parser fails with a clear message`() {
        val result = invoke("get_file_symbols", path("src/app.ts"))

        val error = assertIs<ToolResult.Failure>(result).error
        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, error.code)
        assertTrue(error.message.orEmpty().contains("TypeScript"), error.message.orEmpty())
    }

    @Test
    fun `a missing path argument is rejected by the router`() {
        val result = invoke("get_file_symbols")

        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, assertIs<ToolResult.Failure>(result).error.code)
    }

    @Test
    fun `a file that does not exist fails without inventing symbols`() {
        assertIs<ToolResult.Failure>(invoke("get_file_symbols", path("src/Nope.kt")))
    }

    @Test
    fun `no open workspace fails closed`() {
        val result = invoke("get_file_symbols", path("src/UserRepository.kt"), workspace = null)

        assertEquals(ToolErrorCode.WORKSPACE_UNAVAILABLE, assertIs<ToolResult.Failure>(result).error.code)
    }

    // --- redaction ----------------------------------------------------------

    @Test
    fun `signatures are redacted before they leave a tool`() {
        val tree = kotlinTree(header = "fun connect(apiKey = \"sk-live-secret\"): Client {")

        val result = invoke("get_file_symbols", path("src/UserRepository.kt"), tree = tree)

        val text = assertIs<ToolResult.Success>(result).output.toString()
        assertTrue(!text.contains("sk-live-secret"), text)
        // The signature is still reported, just without the value.
        assertTrue(text.contains("apiKey=[REDACTED]"), text)
    }
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

/** class UserRepository with a property and two methods; the second calls the first. */
private fun kotlinTree(header: String = "fun getUser(): String {"): SyntaxTree {
    val classBody = body(
        type = "class_body",
        text = "body",
        firstLine = 2,
        lastLine = 5,
        children = listOf(
            body(
                type = "property_declaration",
                text = "private val client: String",
                firstLine = 2,
                lastLine = 2,
                children = listOf(leaf("simple_identifier", "client", 2), leaf("type_identifier", "String", 2)),
            ),
            body(
                type = "function_declaration",
                text = header,
                firstLine = 3,
                lastLine = 4,
                children = listOf(leaf("simple_identifier", "getUser", 3)),
                fields = mapOf("name" to 0),
            ),
            body(
                type = "function_declaration",
                text = "fun updateUser(): Unit {",
                firstLine = 5,
                lastLine = 5,
                children = listOf(leaf("simple_identifier", "updateUser", 5), leaf("simple_identifier", "getUser", 5)),
                fields = mapOf("name" to 0),
            ),
        ),
    )
    val declaration = body(
        type = "class_declaration",
        text = "class UserRepository {",
        firstLine = 1,
        lastLine = 5,
        children = listOf(leaf("type_identifier", "UserRepository", 1), classBody),
        fields = mapOf("name" to 0),
    )
    return InMemorySyntaxTree(
        language = CodeLanguage.KOTLIN,
        root = body("source_file", "source", 1, 5, listOf(declaration)),
    )
}
