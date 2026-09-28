package com.agentx.app.codeintel

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Extraction is tested against in-memory trees shaped like the grammars' output.
 * The node types come from the grammars the `:codeintel-android` module ships;
 * what is verified here is the mapping itself: kinds, names, nesting, ranges and
 * the outline.
 */
class SymbolExtractionTest {

    @Test
    fun `kotlin declarations become symbols with nesting and kinds`() = runTest {
        val engine = testEngine(FakeSyntaxParserProvider.returning(CodeLanguage.KOTLIN, kotlinFixture()))

        val parsed = engine.parseFile(SourceFile("src/UserRepository.kt", "class UserRepository")).valueOrFail()
        val symbols = parsed.symbols

        assertEquals(
            listOf(
                "package com.demo" to SymbolKind.NAMESPACE,
                "import kotlin.math.max" to SymbolKind.IMPORT,
                "UserRepository" to SymbolKind.CLASS,
                "client" to SymbolKind.PROPERTY,
                "getUser" to SymbolKind.METHOD,
                "updateUser" to SymbolKind.METHOD,
            ),
            symbols.map { it.name to it.kind },
        )

        val repository = symbols.first { it.name == "UserRepository" }
        assertEquals(CodeLanguage.KOTLIN, repository.language)
        assertEquals("src/UserRepository.kt", repository.path)
        assertNull(repository.parent)
        assertEquals("class UserRepository(private val client: String)", repository.signature)

        val getter = symbols.first { it.name == "getUser" }
        assertEquals("UserRepository", getter.parent)
        assertEquals("UserRepository.getUser", getter.qualifiedName)
        assertEquals("method getUser()", getter.label)

        // A callable inside a container is a method; the property's kind is not
        // turned into a function just because it is declared in a class.
        assertEquals(SymbolKind.PROPERTY, symbols.first { it.name == "client" }.kind)
    }

    @Test
    fun `symbol ranges point at the declaration and its name`() = runTest {
        val engine = testEngine(FakeSyntaxParserProvider.returning(CodeLanguage.KOTLIN, kotlinFixture()))
        val parsed = engine.parseFile(SourceFile("src/UserRepository.kt", "class UserRepository")).valueOrFail()

        val getter = parsed.symbols.first { it.name == "getUser" }
        assertEquals(7, getter.range.start.line)
        assertEquals(8, getter.range.end.line)
        assertEquals(2, getter.range.lineCount)
        assertEquals(7, getter.nameRange?.start?.line)

        val classSymbol = parsed.symbols.first { it.name == "UserRepository" }
        assertTrue(classSymbol.range.contains(getter.range), "class range must contain its method")

        // Imports and namespaces have no single name node.
        assertNull(parsed.symbols.first { it.kind == SymbolKind.IMPORT }.nameRange)
    }

    @Test
    fun `the outline nests symbols under the declaration that contains them`() = runTest {
        val engine = testEngine(FakeSyntaxParserProvider.returning(CodeLanguage.KOTLIN, kotlinFixture()))
        val parsed = engine.parseFile(SourceFile("src/UserRepository.kt", "class UserRepository")).valueOrFail()
        val outline = parsed.outline

        assertEquals(6, outline.symbolCount) // package, import, class and its three members
        assertEquals(listOf("UserRepository"), outline.roots.map { it.symbol.name }.drop(2))
        assertEquals(
            listOf("client", "getUser", "updateUser"),
            outline.roots.first { it.symbol.kind == SymbolKind.CLASS }.children.map { it.symbol.name },
        )

        val rendered = outline.render()
        assertTrue(rendered.contains("class UserRepository"), rendered)
        assertTrue(rendered.contains("method getUser()"), rendered)
        assertTrue(rendered.contains("property client"), rendered)
    }

    @Test
    fun `symbol at cursor returns the innermost declaration`() = runTest {
        val engine = testEngine(FakeSyntaxParserProvider.returning(CodeLanguage.KOTLIN, kotlinFixture()))
        val file = SourceFile("src/UserRepository.kt", "class UserRepository")
        val parsed = engine.parseFile(file).valueOrFail()

        assertEquals("client", parsed.outline.symbolAt(SourcePosition(5, 3))?.name)
        assertEquals("getUser", parsed.outline.symbolAt(SourcePosition(7, 10))?.name)
        assertEquals("updateUser", parsed.outline.symbolAt(SourcePosition(10, 12))?.name)
        // Outside every declaration there is no symbol, not a wrong one.
        assertNull(parsed.outline.symbolAt(SourcePosition(400, 1)))
    }

    @Test
    fun `typescript declarations are extracted`() = runTest {
        val engine = testEngine(FakeSyntaxParserProvider.returning(CodeLanguage.TYPESCRIPT, typeScriptFixture()))

        val parsed = engine.parseFile(SourceFile("src/types.ts", "const limit = 10")).valueOrFail()

        assertEquals(
            listOf(
                "import { helper } from './helper'" to SymbolKind.IMPORT,
                "User" to SymbolKind.INTERFACE,
                "greet" to SymbolKind.FUNCTION,
                "limit" to SymbolKind.VARIABLE,
            ),
            parsed.symbols.map { it.name to it.kind },
        )
        // The name lives on the declarator, not on the declaration.
        assertEquals("limit", parsed.symbols.last { it.name == "limit" }.name)
        assertEquals(CodeLanguage.TYPESCRIPT, parsed.outline.language)
    }

    @Test
    fun `python declarations are extracted`() = runTest {
        val engine = testEngine(FakeSyntaxParserProvider.returning(CodeLanguage.PYTHON, pythonFixture()))

        val parsed = engine.parseFile(SourceFile("tools/run.py", "def main():")).valueOrFail()

        assertEquals(
            listOf(
                "import os" to SymbolKind.IMPORT,
                "Repo" to SymbolKind.CLASS,
                "get" to SymbolKind.METHOD,
                "main" to SymbolKind.FUNCTION,
            ),
            parsed.symbols.map { it.name to it.kind },
        )
        assertEquals("Repo", parsed.symbols.first { it.name == "get" }.parent)
    }

    @Test
    fun `malformed source still produces the declarations it did parse`() = runTest {
        val engine = testEngine(FakeSyntaxParserProvider.returning(CodeLanguage.KOTLIN, malformedKotlinFixture()))

        val parsed = engine.parseFile(SourceFile("src/Broken.kt", "class Broken(")).valueOrFail()

        assertTrue(parsed.hasSyntaxErrors)
        assertTrue(parsed.outline.hasSyntaxErrors)
        assertEquals(listOf("Broken"), parsed.symbols.map { it.name })
    }

    @Test
    fun `a grammar without a symbol profile yields no symbols without failing`() = runTest {
        val html = fixtureTree(
            CodeLanguage.HTML,
            spec("document", children = listOf(spec("element", "<div>"))),
        )
        val engine = testEngine(FakeSyntaxParserProvider.returning(CodeLanguage.HTML, html))

        val parsed = engine.parseFile(SourceFile("index.html", "<div>")).valueOrFail()

        assertTrue(parsed.symbols.isEmpty())
        assertTrue(parsed.outline.isEmpty)
        assertNotNull(parsed.tree) // the tree is still available for future query support
        assertEquals(CodeLanguage.HTML, parsed.language)
    }
}
