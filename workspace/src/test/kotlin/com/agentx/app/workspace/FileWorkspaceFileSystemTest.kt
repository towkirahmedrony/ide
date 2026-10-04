package com.agentx.app.workspace

import com.agentx.app.core.errorOrNull
import com.agentx.app.core.valueOrNull
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 3: the browser/editor filesystem must be the real project directory.
 *
 * Every assertion reads the file back through [java.io.File], i.e. the same bytes a shell in
 * `/workspace` and `git status` see — never an in-memory copy.
 */
class FileWorkspaceFileSystemTest {

    private lateinit var root: File

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("agentx-project").toFile()
        File(root, "src/main").mkdirs()
        File(root, "src/main/Main.kt").writeText("fun main() {}\n")
        File(root, "README.md").writeText("# Project\n")
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun fileSystem() = FileWorkspaceFileSystem(root)

    @Test
    fun `lists the real project with directories first`() = runBlocking {
        val names = fileSystem().list(WorkspacePath.ROOT).valueOrNull()?.map { it.name }
        assertEquals(listOf("src", "README.md"), names)
    }

    @Test
    fun `read and write touch the actual file`() = runBlocking {
        val fs = fileSystem()

        assertEquals("fun main() {}\n", fs.readFile("src/main/Main.kt").valueOrNull())

        assertTrue(fs.writeFile("src/main/Main.kt", "fun main() = Unit\n").valueOrNull() == Unit)
        // Read it back the way Git and the shell would.
        assertEquals("fun main() = Unit\n", File(root, "src/main/Main.kt").readText())
    }

    @Test
    fun `create rename and delete operate on the real project`() = runBlocking {
        val fs = fileSystem()

        assertNotNull(fs.createFile("src/main/New.kt").valueOrNull())
        assertTrue(File(root, "src/main/New.kt").isFile)

        assertNotNull(fs.createDirectory("assets").valueOrNull())
        assertTrue(File(root, "assets").isDirectory)

        assertNotNull(fs.rename("README.md", "readme.md").valueOrNull())
        assertFalse(File(root, "README.md").exists())
        assertTrue(File(root, "readme.md").isFile)

        assertTrue(fs.delete("src").valueOrNull() == Unit)
        assertFalse(File(root, "src").exists())
    }

    @Test
    fun `rejects absolute paths and traversal`() = runBlocking {
        val fs = fileSystem()

        assertEquals(WorkspaceErrorCode.ABSOLUTE_PATH, fs.readFile("/etc/hosts").errorOrNull()?.code)
        assertEquals(WorkspaceErrorCode.PATH_TRAVERSAL, fs.readFile("../outside.txt").errorOrNull()?.code)

        // A create that tries to escape must not touch the parent directory either.
        val sibling = File(root.parentFile, "escaped.txt")
        assertEquals(WorkspaceErrorCode.PATH_TRAVERSAL, fs.createFile("../escaped.txt").errorOrNull()?.code)
        assertFalse(sibling.exists())
    }

    @Test
    fun `a file created outside the filesystem is immediately visible`() = runBlocking {
        val fs = fileSystem()

        // What `touch from-terminal.txt` does.
        File(root, "from-terminal.txt").writeText("hello\n")

        assertTrue(fs.exists("from-terminal.txt"))
        assertEquals("hello\n", fs.readFile("from-terminal.txt").valueOrNull())
        assertTrue(fs.list(WorkspacePath.ROOT).valueOrNull()?.any { it.name == "from-terminal.txt" } == true)
    }

    @Test
    fun `a file deleted outside disappears`() = runBlocking {
        val fs = fileSystem()
        assertTrue(fs.exists("README.md"))

        // What `rm README.md` does.
        File(root, "README.md").delete()

        assertFalse(fs.exists("README.md"))
        assertEquals(WorkspaceErrorCode.NOT_FOUND, fs.readFile("README.md").errorOrNull()?.code)
    }
}
