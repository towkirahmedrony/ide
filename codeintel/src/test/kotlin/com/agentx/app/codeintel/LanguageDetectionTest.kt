package com.agentx.app.codeintel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LanguageDetectionTest {

    @Test
    fun `detects the languages this IDE recognises`() {
        val cases = mapOf(
            "Main.kt" to CodeLanguage.KOTLIN,
            "build.gradle.kts" to CodeLanguage.KOTLIN,
            "User.java" to CodeLanguage.JAVA,
            "script.py" to CodeLanguage.PYTHON,
            "app.js" to CodeLanguage.JAVASCRIPT,
            "view.jsx" to CodeLanguage.JSX,
            "types.ts" to CodeLanguage.TYPESCRIPT,
            "component.tsx" to CodeLanguage.TSX,
            "data.json" to CodeLanguage.JSON,
            "layout.xml" to CodeLanguage.XML,
            "index.html" to CodeLanguage.HTML,
            "styles.css" to CodeLanguage.CSS,
            "README.md" to CodeLanguage.MARKDOWN,
            "schema.sql" to CodeLanguage.SQL,
            "run.sh" to CodeLanguage.SHELL,
        )

        cases.forEach { (path, expected) ->
            assertEquals(expected, LanguageDetector.detect(path), "path=$path")
        }
    }

    @Test
    fun `an unknown extension is unknown and detection never throws`() {
        assertEquals(CodeLanguage.UNKNOWN, LanguageDetector.detect("archive.zip"))
        assertEquals(CodeLanguage.UNKNOWN, LanguageDetector.detect("binary"))
        assertEquals(CodeLanguage.UNKNOWN, LanguageDetector.detect(".env"))
        assertEquals(CodeLanguage.UNKNOWN, LanguageDetector.detect("trailing."))
        assertEquals(CodeLanguage.UNKNOWN, LanguageDetector.detect(""))
        assertEquals(CodeLanguage.UNKNOWN, LanguageDetector.detect(null))
    }

    @Test
    fun `detection is case insensitive and separator agnostic`() {
        assertEquals(CodeLanguage.KOTLIN, LanguageDetector.detect("SRC\\Main.KT"))
        assertEquals(CodeLanguage.TYPESCRIPT, LanguageDetector.detect("src/lib/index.TS"))
        assertEquals("Main.kt", LanguageDetector.fileNameOf("src/main/Main.kt"))
        assertEquals("Main.kt", LanguageDetector.fileNameOf("src\\main\\Main.kt"))
        assertNull(LanguageDetector.fileNameOf("   "))
    }

    @Test
    fun `a recognised language is not the same as a parseable one`() {
        // Detection is pure metadata; whether a grammar exists is answered by the
        // engine's parsed languages, which must stay empty without a backend.
        assertTrue(LanguageDetector.detect("component.tsx").isKnown)

        val engine = testEngine()
        assertTrue(engine.parsedLanguages().isEmpty())
        assertFalse(engine.canParse("Main.kt"))
        assertEquals(CodeLanguage.KOTLIN, engine.detectLanguage("Main.kt"))
    }

    @Test
    fun `extension matching accepts a leading dot`() {
        assertTrue(CodeLanguage.KOTLIN.matchesExtension(".kt"))
        assertTrue(CodeLanguage.KOTLIN.matchesExtension("KT"))
        assertFalse(CodeLanguage.KOTLIN.matchesExtension("java"))
        assertTrue(CodeLanguage.knownExtensions.contains("kt"))
        assertTrue(CodeLanguage.knownExtensions.contains("tsx"))
    }
}
