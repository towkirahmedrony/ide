package com.agentx.app.codeintel.android

import com.agentx.app.codeintel.CodeLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The grammar parsers need native libraries, so they are exercised on device.
 * What *can* be verified on the JVM is the part that must never fail: offset
 * mapping, and the backend degrading to "no parser" instead of crashing when a
 * grammar cannot be loaded (the wrong ABI, a stripped build, a broken library).
 */
class TreeSitterBackendTest {

    @Test
    fun `offsets map to one-based positions`() {
        val source = "class A\n  fun b()\n\nval c = 1\n"
        val map = TreeSitterSourceMap(source)

        assertEquals(1 to 1, map.positionOf(0).let { it.line to it.column })
        assertEquals(1 to 8, map.positionOf(7).let { it.line to it.column })
        assertEquals(2 to 1, map.positionOf(8).let { it.line to it.column })
        assertEquals(2 to 3, map.positionOf(10).let { it.line to it.column })
        assertEquals(4 to 1, map.positionOf(19).let { it.line to it.column })
        assertEquals(4 to 2, map.positionOf(20).let { it.line to it.column })
    }

    @Test
    fun `offsets outside the text are clamped, never thrown`() {
        val map = TreeSitterSourceMap("class A\n")

        assertEquals(1, map.positionOf(-5).line)
        assertEquals(1, map.positionOf(-5).column)
        assertEquals(2, map.positionOf(999).line)
    }

    @Test
    fun `line starts handle an empty file`() {
        assertTrue(TreeSitterSourceMap.lineStartsOf("").contentEquals(intArrayOf(0)))
        assertTrue(TreeSitterSourceMap.lineStartsOf("a\nb\n").contentEquals(intArrayOf(0, 2, 4)))
    }

    @Test
    fun `a grammar that cannot be loaded is reported as unsupported`() {
        val provider = TreeSitterParserProvider(
            loaders = mapOf(CodeLanguage.KOTLIN to { throw UnsatisfiedLinkError("libtree-sitter-kotlin.so") }),
        )

        assertTrue(provider.supportedLanguages().isEmpty())
        assertNull(provider.parserFor(CodeLanguage.KOTLIN))
        assertTrue(provider.unavailableReason(CodeLanguage.KOTLIN).orEmpty().contains("libtree-sitter-kotlin"))
    }

    @Test
    fun `a language with no shipped grammar has no parser`() {
        val provider = TreeSitterParserProvider(loaders = emptyMap())

        assertTrue(provider.supportedLanguages().isEmpty())
        assertNull(provider.parserFor(CodeLanguage.TYPESCRIPT))
        assertNull(provider.unavailableReason(CodeLanguage.TYPESCRIPT))
    }

    @Test
    fun `a load failure is remembered and not retried`() {
        var attempts = 0
        val provider = TreeSitterParserProvider(
            loaders = mapOf(
                CodeLanguage.PYTHON to {
                    attempts++
                    throw IllegalStateException("cannot load")
                },
            ),
        )

        repeat(5) { provider.parserFor(CodeLanguage.PYTHON) }

        assertEquals(1, attempts)
    }

    @Test
    fun `the shipped grammars are the ones this build packages`() {
        val shipped = TreeSitterGrammars.loaders().keys

        assertEquals(
            setOf(
                CodeLanguage.KOTLIN,
                CodeLanguage.JAVA,
                CodeLanguage.PYTHON,
                CodeLanguage.JSON,
                CodeLanguage.XML,
            ),
            shipped,
        )
    }

    @Test
    fun `a language with no entry in the loader map is not offered`() {
        val provider = TreeSitterParserProvider(loaders = mapOf(CodeLanguage.KOTLIN to { error("unused") }))

        assertNull(provider.parserFor(CodeLanguage.JAVASCRIPT))
    }
}
