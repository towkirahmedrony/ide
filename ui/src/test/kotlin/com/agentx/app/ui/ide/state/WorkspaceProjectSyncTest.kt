package com.agentx.app.ui.ide.state

import com.agentx.app.core.failure
import com.agentx.app.core.success
import com.agentx.app.ui.ide.model.DirectoryLoadState
import com.agentx.app.workspace.DefaultWorkspace
import com.agentx.app.workspace.DefaultWorkspaceManager
import com.agentx.app.workspace.FileWorkspaceFileSystem
import com.agentx.app.workspace.Workspace
import com.agentx.app.workspace.WorkspaceBackend
import com.agentx.app.workspace.WorkspaceError
import com.agentx.app.workspace.WorkspaceErrorCode
import com.agentx.app.workspace.WorkspaceId
import com.agentx.app.workspace.WorkspaceMetadata
import com.agentx.app.workspace.WorkspacePath
import com.agentx.app.workspace.WorkspaceResult
import com.agentx.app.workspace.memory.InMemoryWorkspaceMetadataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 3: the file browser, the editor and the real project directory are one source of truth.
 *
 * These build the workspace runtime over a real temporary directory, so every claim is verified
 * against the bytes a shell in `/workspace` and `git status` would see.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceProjectSyncTest {

    private val workspaceId = "test-workspace"

    private lateinit var projectDir: File

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        projectDir = Files.createTempDirectory("agentx-sync").toFile()
        File(projectDir, "src").mkdirs()
        File(projectDir, "src/Main.kt").writeText("fun main() {}\n")
        File(projectDir, "README.md").writeText("# Project\n")
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        projectDir.deleteRecursively()
    }

    private class FixedWorkspaceBackend(
        private val workspaces: Map<String, Workspace>,
    ) : WorkspaceBackend {
        override suspend fun open(handle: String): WorkspaceResult<Workspace> =
            workspaces[handle]?.let { success(it) }
                ?: failure(WorkspaceError(WorkspaceErrorCode.WORKSPACE_NOT_FOUND, "No such workspace."))
    }

    private fun workspace(id: String, directory: File, name: String = "project"): Workspace =
        DefaultWorkspace(
            metadata = WorkspaceMetadata(
                id = WorkspaceId(id),
                name = name,
                displayLocation = directory.path,
                persisted = true,
            ),
            fileSystem = FileWorkspaceFileSystem(directory),
        )

    private fun viewModelFor(workspace: Workspace, handle: String = workspace.metadata.displayLocation): WorkspaceViewModel {
        val manager = DefaultWorkspaceManager(
            backend = FixedWorkspaceBackend(mapOf(handle to workspace)),
            store = InMemoryWorkspaceMetadataStore(),
        )
        runBlocking { manager.open(handle) }
        return WorkspaceViewModel(workspace.metadata.id.value, manager)
    }

    private fun childrenOf(viewModel: WorkspaceViewModel): List<String> =
        viewModel.filesState.root.children.map { it.name }

    // --- browser + editor over the real project ----------------------------

    @Test
    fun `the file browser reads the active project's real files`() {
        val viewModel = viewModelFor(workspace(workspaceId, projectDir))

        assertEquals(DirectoryLoadState.LOADED, viewModel.filesState.rootState)
        assertEquals(listOf("src", "README.md"), childrenOf(viewModel))
    }

    @Test
    fun `opening a file loads it and saving writes the real project file`() {
        val viewModel = viewModelFor(workspace(workspaceId, projectDir))

        viewModel.openFile("src/Main.kt")
        assertEquals("fun main() {}\n", viewModel.editorState.file?.content)

        viewModel.editDraft("fun main() = println(\"hi\")\n")
        assertTrue(viewModel.editorState.isDirty)
        viewModel.save()

        val onDisk = File(projectDir, "src/Main.kt").readText()
        assertEquals("fun main() = println(\"hi\")\n", onDisk)
        assertEquals(onDisk, viewModel.editorState.file?.content)
        assertFalse(viewModel.editorState.isDirty)
    }

    @Test
    fun `git sees the editor's modifications`() {
        val viewModel = viewModelFor(workspace(workspaceId, projectDir))

        viewModel.openFile("README.md")
        viewModel.editDraft("# Project\n\nchanged by the editor\n")
        viewModel.save()

        // Git reads the working tree through java.io.File; this is that same read.
        val workingTree = File(projectDir, "README.md").readText()
        assertTrue(workingTree.contains("changed by the editor"))
    }

    @Test
    fun `a file created in the terminal appears after reload`() {
        val viewModel = viewModelFor(workspace(workspaceId, projectDir))
        assertFalse(childrenOf(viewModel).contains("from-terminal.txt"))

        File(projectDir, "from-terminal.txt").writeText("hello\n")
        viewModel.reload()

        assertTrue(childrenOf(viewModel).contains("from-terminal.txt"))
    }

    @Test
    fun `a file deleted in the terminal disappears after reload`() {
        val viewModel = viewModelFor(workspace(workspaceId, projectDir))
        assertTrue(childrenOf(viewModel).contains("README.md"))

        File(projectDir, "README.md").delete()
        viewModel.reload()

        assertFalse(childrenOf(viewModel).contains("README.md"))
    }

    @Test
    fun `create rename and delete from the browser change the real project`() {
        val viewModel = viewModelFor(workspace(workspaceId, projectDir))

        viewModel.createFile(WorkspacePath.ROOT, "New.txt")
        assertTrue(File(projectDir, "New.txt").isFile)
        assertTrue(childrenOf(viewModel).contains("New.txt"))

        viewModel.rename("New.txt", "Renamed.txt")
        assertTrue(File(projectDir, "Renamed.txt").isFile)
        assertFalse(File(projectDir, "New.txt").exists())

        viewModel.delete("Renamed.txt")
        assertFalse(File(projectDir, "Renamed.txt").exists())
        assertFalse(childrenOf(viewModel).contains("Renamed.txt"))
    }

    // --- safety -------------------------------------------------------------

    @Test
    fun `path traversal outside the project root is rejected`() {
        val viewModel = viewModelFor(workspace(workspaceId, projectDir))

        viewModel.openFile("/etc/hosts")
        assertNull(viewModel.editorState.file)
        assertNotNull(viewModel.editorState.statusMessage)

        viewModel.createFile(WorkspacePath.ROOT, "../escaped.txt")
        assertFalse(File(projectDir.parentFile, "escaped.txt").exists())
        assertTrue(viewModel.filesState.messageIsError)
    }

    @Test
    fun `a save does not silently overwrite newer content on disk`() {
        val viewModel = viewModelFor(workspace(workspaceId, projectDir))
        viewModel.openFile("README.md")
        viewModel.editDraft("# Project\n\nmy edit\n")

        // Something else wrote the file after it was opened.
        File(projectDir, "README.md").writeText("# Project\n\nsomeone else\n")

        viewModel.save()

        assertTrue(viewModel.editorState.saveConflict, "the save must stop and ask")
        assertEquals("# Project\n\nmy edit\n", viewModel.editorState.draft, "the draft is kept")
        assertEquals("# Project\n\nsomeone else\n", File(projectDir, "README.md").readText())

        // The user explicitly chooses to overwrite.
        viewModel.save(overwrite = true)
        assertFalse(viewModel.editorState.saveConflict)
        assertEquals("# Project\n\nmy edit\n", File(projectDir, "README.md").readText())
    }

    @Test
    fun `an external change while editing keeps the unsaved draft`() {
        val viewModel = viewModelFor(workspace(workspaceId, projectDir))
        viewModel.openFile("README.md")
        viewModel.editDraft("# Project\n\nmy edit\n")

        File(projectDir, "README.md").writeText("# Project\n\nsomeone else\n")
        viewModel.reload()

        assertTrue(viewModel.editorState.externallyModified)
        assertEquals("# Project\n\nmy edit\n", viewModel.editorState.draft, "unsaved edits are never discarded")
    }

    // --- project lifecycle --------------------------------------------------

    @Test
    fun `switching projects does not leave stale files visible`() {
        val firstDir = Files.createTempDirectory("agentx-first").toFile()
        val secondDir = Files.createTempDirectory("agentx-second").toFile()
        firstDir.resolve("only-in-first.txt").writeText("a")
        secondDir.resolve("only-in-second.txt").writeText("b")

        val first = workspace("workspace-a", firstDir, name = "first")
        val second = workspace("workspace-b", secondDir, name = "second")
        val manager = DefaultWorkspaceManager(
            backend = FixedWorkspaceBackend(
                mapOf(first.metadata.displayLocation to first, second.metadata.displayLocation to second),
            ),
            store = InMemoryWorkspaceMetadataStore(),
        )

        runBlocking { manager.open(first.metadata.displayLocation) }
        val firstViewModel = WorkspaceViewModel(first.metadata.id.value, manager)
        assertTrue(childrenOf(firstViewModel).contains("only-in-first.txt"))

        runBlocking { manager.open(second.metadata.displayLocation) }
        val secondViewModel = WorkspaceViewModel(second.metadata.id.value, manager)

        assertTrue(childrenOf(secondViewModel).contains("only-in-second.txt"))
        assertFalse(childrenOf(secondViewModel).contains("only-in-first.txt"))

        firstDir.deleteRecursively()
        secondDir.deleteRecursively()
    }

    @Test
    fun `a no-project state reports an error and never touches the filesystem`() {
        val manager = DefaultWorkspaceManager(
            backend = FixedWorkspaceBackend(emptyMap()),
            store = InMemoryWorkspaceMetadataStore(),
        )
        val viewModel = WorkspaceViewModel("no-such-workspace", manager)

        assertFalse(viewModel.filesState.loading)
        assertNotNull(viewModel.filesState.error)

        // With no session, every entry point is a no-op instead of reaching for a path.
        viewModel.openFile("README.md")
        viewModel.createFile(WorkspacePath.ROOT, "x.txt")
        viewModel.delete("README.md")
        viewModel.reload()

        assertNull(viewModel.editorState.file)
        assertNull(viewModel.editorState.statusMessage)
    }
}
