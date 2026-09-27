package dev.forge.ide.workspace

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for the Files → Editor hand-over: exactly the selected file is read,
 * text is opened, and binary/media files get an explicit message.
 */
class WorkspaceFileOpenerTest {

    private fun fileSystem() = FakeWorkspaceFileSystem(
        contents = mapOf(
            "app/src/Main.kt" to "fun main() { println(\"hi\") }\n",
            "gradle/libs.versions.toml" to "[versions]\nagp = \"9.4.1\"\n",
            "logo.png" to "\u0089PNG\u0000\u0000binary",
            "notes" to "plain text without an extension",
            "corrupt" to "\u0000\u0001\u0002\u0003\u0004",
        ),
    )

    // 8. File selection reads the selected file only.
    @Test
    fun `opening a source file returns its content`() = runBlocking {
        val fileSystem = fileSystem()
        val opener = WorkspaceFileOpener(fileSystem)

        val opened = opener.open("app/src/Main.kt")

        val text = assertIs<WorkspaceFileOpener.Opened.Text>(opened)
        assertEquals("app/src/Main.kt", text.path)
        assertEquals("Main.kt", text.name)
        assertEquals("fun main() { println(\"hi\") }\n", text.content)
        assertEquals(listOf("app/src/Main.kt"), fileSystem.readCalls)
    }

    @Test
    fun `config files with uncommon extensions still open as text`() = runBlocking {
        val opener = WorkspaceFileOpener(fileSystem())

        val toml = assertIs<WorkspaceFileOpener.Opened.Text>(opener.open("gradle/libs.versions.toml"))
        assertEquals("libs.versions.toml", toml.name)

        val notes = assertIs<WorkspaceFileOpener.Opened.Text>(opener.open("notes"))
        assertEquals("notes", notes.name)
    }

    // 11. Binary/media files are refused without being read.
    @Test
    fun `a binary file is refused without reading it`() = runBlocking {
        val fileSystem = fileSystem()
        val opener = WorkspaceFileOpener(fileSystem)

        val opened = opener.open("logo.png")

        val unsupported = assertIs<WorkspaceFileOpener.Opened.Unsupported>(opened)
        assertEquals(WorkspaceErrorCode.UNSUPPORTED_FILE_TYPE, unsupported.error.code)
        assertTrue(unsupported.error.userMessage.isNotBlank())
        assertTrue(fileSystem.readCalls.isEmpty(), "a known binary file must not be read")
    }

    @Test
    fun `binary content without a telling extension is detected`() = runBlocking {
        val opener = WorkspaceFileOpener(fileSystem())

        val opened = opener.open("corrupt")

        val unsupported = assertIs<WorkspaceFileOpener.Opened.Unsupported>(opened)
        assertEquals(WorkspaceErrorCode.UNSUPPORTED_FILE_TYPE, unsupported.error.code)
    }

    @Test
    fun `a file that cannot be read reports the underlying error`() = runBlocking {
        val fileSystem = FakeWorkspaceFileSystem(
            failures = mapOf(
                "locked.kt" to WorkspaceError(WorkspaceErrorCode.PERMISSION_DENIED, "denied", "locked.kt"),
            ),
        )
        val opener = WorkspaceFileOpener(fileSystem)

        val opened = opener.open("locked.kt")

        val failed = assertIs<WorkspaceFileOpener.Opened.Failed>(opened)
        assertEquals(WorkspaceErrorCode.PERMISSION_DENIED, failed.error.code)
    }

    @Test
    fun `a missing file reports not found`() = runBlocking {
        val opener = WorkspaceFileOpener(fileSystem())

        val opened = opener.open("app/src/Missing.kt")

        val failed = assertIs<WorkspaceFileOpener.Opened.Failed>(opened)
        assertEquals(WorkspaceErrorCode.NOT_FOUND, failed.error.code)
    }

    @Test
    fun `paths outside the workspace are rejected without touching storage`() = runBlocking {
        val fileSystem = fileSystem()
        val opener = WorkspaceFileOpener(fileSystem)

        val absolute = assertIs<WorkspaceFileOpener.Opened.Failed>(opener.open("/etc/passwd"))
        assertEquals(WorkspaceErrorCode.ABSOLUTE_PATH, absolute.error.code)

        val traversal = assertIs<WorkspaceFileOpener.Opened.Failed>(opener.open("../../secrets"))
        assertEquals(WorkspaceErrorCode.PATH_TRAVERSAL, traversal.error.code)

        assertTrue(fileSystem.readCalls.isEmpty())
    }

    @Test
    fun `text classification keeps project files and refuses known binaries`() {
        listOf(
            "Main.kt", "build.gradle.kts", "gradle.properties", "values.xml", "package.json",
            "README.md", "notes.txt", "config.yaml", "settings.yml", "Cargo.toml",
            "app.js", "app.ts", "view.tsx", "component.jsx", "styles.css", "index.html", "query.sql",
            ".gitignore", "LICENSE", "Makefile", "Dockerfile",
        ).forEach { assertTrue(WorkspaceTextFiles.isLikelyText(it), "should be text: $it") }

        listOf("logo.png", "photo.JPG", "archive.zip", "app.apk", "lib.so", "song.mp3", "movie.mp4", "data.db")
            .forEach { assertTrue(!WorkspaceTextFiles.isLikelyText(it), "should be binary: $it") }
    }

    @Test
    fun `content sniffing only flags genuinely non-text bytes`() {
        assertTrue(!WorkspaceTextFiles.looksBinary(""))
        assertTrue(!WorkspaceTextFiles.looksBinary("fun main() {}\n"))
        assertTrue(!WorkspaceTextFiles.looksBinary("emoji 🚀 and accents éàü stay text"))
        assertTrue(WorkspaceTextFiles.looksBinary("header\u0000payload"))
    }
}
