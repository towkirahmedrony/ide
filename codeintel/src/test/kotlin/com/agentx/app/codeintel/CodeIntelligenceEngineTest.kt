package com.agentx.app.codeintel

import com.agentx.app.core.ForgeErrorCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.cancelAndJoin
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodeIntelligenceEngineTest {

    private fun kotlinEngine() = testEngine(FakeSyntaxParserProvider.returning(CodeLanguage.KOTLIN, kotlinFixture()))

    private fun kotlinFile() = SourceFile("src/UserRepository.kt", "class UserRepository")

    // --- parsing and caching ------------------------------------------------

    @Test
    fun `unchanged content is analysed once`() = runTest {
        val provider = FakeSyntaxParserProvider.returning(CodeLanguage.KOTLIN, kotlinFixture())
        val engine = testEngine(provider)
        val file = kotlinFile()

        engine.parseFile(file).valueOrFail()
        engine.parseFile(file).valueOrFail()
        engine.getSymbols(file).valueOrFail()
        engine.getOutline(file).valueOrFail()

        assertEquals(1, provider.parseCount)
    }

    @Test
    fun `an edit invalidates the cached analysis`() = runTest {
        val provider = FakeSyntaxParserProvider.returning(CodeLanguage.KOTLIN, kotlinFixture())
        val engine = testEngine(provider)

        engine.parseFile(SourceFile("src/Main.kt", "fun main() {}")).valueOrFail()
        engine.parseFile(SourceFile("src/Main.kt", "fun main() { println() }")).valueOrFail()

        assertEquals(2, provider.parseCount)
    }

    @Test
    fun `the same text in another file is analysed separately`() = runTest {
        val provider = FakeSyntaxParserProvider.returning(CodeLanguage.KOTLIN, kotlinFixture())
        val engine = testEngine(provider)

        engine.parseFile(kotlinFile()).valueOrFail()
        engine.parseFile(SourceFile("src/Other.kt", "class UserRepository")).valueOrFail()

        assertEquals(2, provider.parseCount)
        assertEquals(listOf("src/UserRepository.kt", "src/Other.kt"), provider.parsedPaths)
    }

    @Test
    fun `a blank file is not parsed and reports no structure`() = runTest {
        val provider = FakeSyntaxParserProvider.returning(CodeLanguage.KOTLIN, kotlinFixture())
        val engine = testEngine(provider)

        val parsed = engine.parseFile(SourceFile("src/Empty.kt", "   \n")).valueOrFail()

        assertTrue(parsed.isEmpty)
        assertNull(parsed.tree)
        assertTrue(parsed.symbols.isEmpty())
        assertEquals(0, provider.parseCount)
    }

    @Test
    fun `the syntax tree is available for callers that need it`() = runTest {
        val engine = kotlinEngine()

        val tree = engine.getSyntaxTree(kotlinFile()).valueOrFail()

        assertEquals(CodeLanguage.KOTLIN, tree.language)
        assertEquals("source_file", tree.root.type)
        assertEquals(3, tree.root.namedChildren().count())
        assertTrue(tree.root.descendants().any { it.type == "function_declaration" })
    }

    @Test
    fun `a blank file yields an empty tree rather than a failure`() = runTest {
        val engine = kotlinEngine()

        val tree = engine.getSyntaxTree(SourceFile("src/Empty.kt", "")).valueOrFail()

        assertEquals(0, tree.root.childCount)
        assertTrue(!tree.hasErrors)
    }

    // --- failures are typed, never crashes ----------------------------------

    @Test
    fun `an unknown language fails with a typed error`() = runTest {
        val failure = testEngine().parseFile(SourceFile("assets/dump.bin", "binary-ish")).errorOrFail()

        assertEquals(ForgeErrorCode.CODE_INTEL_UNSUPPORTED_LANGUAGE, failure.code)
        assertTrue(failure.message.orEmpty().contains("dump.bin"))
    }

    @Test
    fun `a recognised language without a parser is reported as such`() = runTest {
        val failure = testEngine().parseFile(SourceFile("src/Main.kt", "fun main() {}")).errorOrFail()

        assertEquals(ForgeErrorCode.CODE_INTEL_PARSER_UNAVAILABLE, failure.code)
        assertEquals("Kotlin", failure.details["language"])
    }

    @Test
    fun `a backend that reports unsupported at parse time is surfaced`() = runTest {
        val engine = testEngine(FakeSyntaxParserProvider.unsupportedAtParseTime(CodeLanguage.KOTLIN))

        val failure = engine.parseFile(SourceFile("src/Main.kt", "fun main() {}")).errorOrFail()

        assertEquals(ForgeErrorCode.CODE_INTEL_PARSER_UNAVAILABLE, failure.code)
        assertEquals("unsupported-by-parser", failure.details["reason"])
    }

    @Test
    fun `a parser that throws becomes a typed failure instead of crashing`() = runTest {
        val engine = testEngine(FakeSyntaxParserProvider.failing(CodeLanguage.KOTLIN))

        val failure = engine.parseFile(SourceFile("src/Main.kt", "fun main() {}")).errorOrFail()

        assertEquals(ForgeErrorCode.CODE_INTEL_PARSE_FAILED, failure.code)
        assertTrue(failure.message.orEmpty().contains("could not be parsed"), failure.message.orEmpty())
        assertTrue(failure.cause is IllegalStateException)
    }

    @Test
    fun `a file above the size limit is refused before parsing`() = runTest {
        val provider = FakeSyntaxParserProvider.returning(CodeLanguage.KOTLIN, kotlinFixture())
        val engine = testEngine(provider, limits = CodeIntelligenceLimits(maxFileChars = 32))

        val failure = engine.parseFile(SourceFile("src/Big.kt", "x".repeat(64))).errorOrFail()

        assertEquals(ForgeErrorCode.CODE_INTEL_FILE_TOO_LARGE, failure.code)
        assertEquals(0, provider.parseCount)
    }

    @Test
    fun `extraction limits truncate the outline instead of growing without bound`() = runTest {
        val engine = testEngine(
            FakeSyntaxParserProvider.returning(CodeLanguage.KOTLIN, kotlinFixture()),
            limits = CodeIntelligenceLimits(maxSymbols = 2),
        )

        val parsed = engine.parseFile(kotlinFile()).valueOrFail()

        assertEquals(2, parsed.symbols.size)
        assertTrue(parsed.outline.truncated)
    }

    // --- lookups ------------------------------------------------------------

    @Test
    fun `definitions can be found by simple and qualified name`() = runTest {
        val engine = kotlinEngine()
        val file = kotlinFile()

        assertEquals(listOf("getUser"), engine.findDefinitions(file, "getUser").valueOrFail().map { it.name })
        assertEquals(
            listOf("getUser"),
            engine.findDefinitions(file, "UserRepository.getUser").valueOrFail().map { it.name },
        )
        assertTrue(engine.findDefinitions(file, "missing").valueOrFail().isEmpty())
        assertTrue(engine.findDefinitions(file, "   ").valueOrFail().isEmpty())
    }

    @Test
    fun `references mark the declaration and include every usage`() = runTest {
        val engine = kotlinEngine()

        val references = engine.findReferences(kotlinFile(), "getUser").valueOrFail()

        assertEquals(2, references.size, references.toString())
        assertEquals(1, references.count { it.isDefinition })
        assertEquals(7, references.first().range.start.line)
        assertEquals(10, references.last().range.start.line)
        assertTrue(engine.findReferences(kotlinFile(), "nope").valueOrFail().isEmpty())
    }

    @Test
    fun `symbol at a position ignores invalid positions`() = runTest {
        val engine = kotlinEngine()
        val file = kotlinFile()

        assertNull(engine.symbolAt(file, 0, 0).valueOrFail())
        assertNull(engine.symbolAt(file, 400, 1).valueOrFail())
        assertEquals("getUser", engine.symbolAt(file, 7, 5).valueOrFail()?.name)
    }

    @Test
    fun `parsed languages and detection come from the backend`() = runTest {
        val engine = kotlinEngine()

        assertEquals(setOf(CodeLanguage.KOTLIN), engine.parsedLanguages())
        assertTrue(engine.canParse("src/Main.kt"))
        assertTrue(!engine.canParse("src/App.tsx"))
        assertEquals(CodeLanguage.TSX, engine.detectLanguage("src/App.tsx"))
    }

    // --- cancellation -------------------------------------------------------

    @Test
    fun `cancelling the caller abandons the parse and poisons no cache`() = runBlocking {
        val started = AtomicInteger()
        val tree = kotlinFixture()
        val provider = FakeSyntaxParserProvider(
            mapOf(
                CodeLanguage.KOTLIN to { request: ParseRequest ->
                    started.incrementAndGet()
                    val deadline = System.currentTimeMillis() + 5_000
                    while (!request.isCancelled() && System.currentTimeMillis() < deadline) {
                        Thread.sleep(2)
                    }
                    if (request.isCancelled()) {
                        SyntaxParseResult.Failed("cancelled")
                    } else {
                        SyntaxParseResult.Success(tree)
                    }
                },
            ),
        )
        val engine = DefaultCodeIntelligence(parsers = provider, dispatcher = Dispatchers.Default)
        val file = kotlinFile()

        val job = launch(Dispatchers.Default) { engine.parseFile(file) }
        withTimeout(2_000) {
            while (started.get() == 0) delay(5)
        }
        job.cancelAndJoin()

        assertTrue(job.isCancelled)

        // The abandoned attempt was not cached: the next request parses again and
        // succeeds, so a cancelled edit never leaves an empty outline behind.
        val parsed = engine.parseFile(file).valueOrFail()
        assertTrue(parsed.symbols.isNotEmpty())
        assertEquals(2, provider.parseCount)
    }
}
