package com.agentx.app.ui.ide.state

import com.agentx.app.codeintel.CodeLanguage
import com.agentx.app.codeintel.DefaultCodeIntelligence
import com.agentx.app.codeintel.InMemorySyntaxNode
import com.agentx.app.codeintel.InMemorySyntaxTree
import com.agentx.app.codeintel.ParseRequest
import com.agentx.app.codeintel.SourcePosition
import com.agentx.app.codeintel.SyntaxParseResult
import com.agentx.app.codeintel.SyntaxParser
import com.agentx.app.codeintel.SyntaxParserProvider
import com.agentx.app.codeintel.SyntaxTree
import com.agentx.app.core.failure
import com.agentx.app.core.success
import com.agentx.app.ui.ide.model.DirectoryLoadState
import com.agentx.app.ui.ide.model.OpenFile
import com.agentx.app.workspace.DefaultWorkspace
import com.agentx.app.workspace.DefaultWorkspaceManager
import com.agentx.app.workspace.Workspace
import com.agentx.app.workspace.WorkspaceBackend
import com.agentx.app.workspace.WorkspaceError
import com.agentx.app.workspace.WorkspaceErrorCode
import com.agentx.app.workspace.WorkspaceFileSystem
import com.agentx.app.workspace.WorkspaceId
import com.agentx.app.workspace.WorkspaceMetadata
import com.agentx.app.workspace.WorkspaceNode
import com.agentx.app.workspace.WorkspacePath
import com.agentx.app.workspace.WorkspaceResult
import com.agentx.app.workspace.memory.InMemoryWorkspaceFileSystem
import com.agentx.app.workspace.memory.InMemoryWorkspaceMetadataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for the Files page spinner.
 *
 * These exercise the state holder with the real workspace runtime on top of an
 * in-memory filesystem, so they assert what a user sees: opening a workspace
 * always leaves the loading state, and only the folders that are opened are
 * ever read.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceViewModelTest {

    private val workspaceId = "test-workspace"

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val projectFiles = mapOf(
        "app/src/main/Main.kt" to "fun main() { println(\"hi\") }\n",
        "app/build.gradle.kts" to "plugins { }",
        "gradle/libs.versions.toml" to "[versions]",
        "README.md" to "# Project",
        "logo.png" to "binary",
    )

    private class RecordingFileSystem(
        private val delegate: WorkspaceFileSystem,
    ) : WorkspaceFileSystem by delegate {

        val listCalls = mutableListOf<String>()
        var failures: Map<String, WorkspaceError> = emptyMap()

        override suspend fun list(path: String): WorkspaceResult<List<WorkspaceNode>> {
            listCalls += path
            failures[path]?.let { return failure(it) }
            return delegate.list(path)
        }

        fun listCount(path: String): Int = listCalls.count { it == path }
    }

    private class FixedWorkspaceBackend(
        private val workspace: Workspace?,
        private val error: WorkspaceError? = null,
    ) : WorkspaceBackend {
        override suspend fun open(handle: String): WorkspaceResult<Workspace> =
            workspace?.let { success(it) } ?: failure(error ?: WorkspaceError(WorkspaceErrorCode.UNKNOWN, "no workspace"))
    }

    private fun viewModel(
        files: Map<String, String> = projectFiles,
        emptyDirectories: List<String> = emptyList(),
    ): Pair<WorkspaceViewModel, RecordingFileSystem> {
        val memory = InMemoryWorkspaceFileSystem(files)
        runBlocking {
            emptyDirectories.forEach { memory.createDirectory(it) }
        }
        val fileSystem = RecordingFileSystem(memory)
        val workspace = DefaultWorkspace(
            metadata = WorkspaceMetadata(
                id = WorkspaceId(workspaceId),
                name = "project",
                displayLocation = "content://test/project",
                persisted = true,
            ),
            fileSystem = fileSystem,
        )
        val manager = DefaultWorkspaceManager(
            backend = FixedWorkspaceBackend(workspace),
            store = InMemoryWorkspaceMetadataStore(),
        )
        runBlocking { manager.open(workspaceId) }
        return WorkspaceViewModel(workspaceId, manager) to fileSystem
    }

    @Test
    fun `opening a workspace leaves the spinner and shows the root folder`() {
        val (viewModel, fileSystem) = viewModel()

        val state = viewModel.filesState

        assertTrue(!state.loading, "the Files page must stop loading")
        assertNull(state.error)
        assertEquals(DirectoryLoadState.LOADED, state.rootState)
        assertEquals(listOf("app", "gradle", "logo.png", "README.md"), state.root.children.map { it.name })
        assertEquals("project", state.root.name)
        assertEquals(listOf(WorkspacePath.ROOT), fileSystem.listCalls)
    }

    @Test
    fun `a workspace that cannot be opened ends in an error instead of loading forever`() {
        val manager = DefaultWorkspaceManager(
            backend = FixedWorkspaceBackend(
                workspace = null,
                error = WorkspaceError(WorkspaceErrorCode.PERMISSION_DENIED, "denied"),
            ),
            store = InMemoryWorkspaceMetadataStore(),
        )

        val viewModel = WorkspaceViewModel(workspaceId, manager)

        assertTrue(!viewModel.filesState.loading)
        assertNotNull(viewModel.filesState.error)
    }

    @Test
    fun `expanding a folder reads only that folder`() {
        val (viewModel, fileSystem) = viewModel()

        viewModel.toggleDirectory("app")
        viewModel.toggleDirectory("app/src")
        viewModel.toggleDirectory("app/src/main")

        assertEquals(listOf("src", "build.gradle.kts"), viewModel.filesState.root.children.first().children.map { it.name })
        assertEquals(
            listOf(WorkspacePath.ROOT, "app", "app/src", "app/src/main"),
            fileSystem.listCalls,
        )
        assertTrue(fileSystem.listCalls.none { it.startsWith("gradle") }, "unopened folders must not be read")
        assertEquals(DirectoryLoadState.UNLOADED, viewModel.filesState.root.children[1].loadState)
    }

    @Test
    fun `repeated expansion never restarts a folder read`() {
        val (viewModel, fileSystem) = viewModel()

        repeat(4) {
            viewModel.toggleDirectory("app")
            viewModel.toggleDirectory("app")
        }

        assertEquals(1, fileSystem.listCount("app"))
        assertEquals(DirectoryLoadState.LOADED, viewModel.filesState.root.children.first().loadState)
    }

    @Test
    fun `an empty folder reports that it is empty`() {
        val (viewModel, _) = viewModel(files = mapOf("README.md" to "# Project"), emptyDirectories = listOf("empty"))

        viewModel.toggleDirectory("empty")

        val empty = viewModel.filesState.root.children.single { it.path == "empty" }
        assertEquals(DirectoryLoadState.LOADED, empty.loadState)
        assertTrue(empty.isEmptyDirectory)
        assertTrue(!viewModel.filesState.loading)
        assertTrue(!viewModel.filesState.isEmpty, "the root folder is not empty")
    }

    @Test
    fun `a folder that cannot be read shows an error and can be retried`() {
        val (viewModel, fileSystem) = viewModel()
        fileSystem.failures = mapOf(
            "app" to WorkspaceError(WorkspaceErrorCode.PERMISSION_DENIED, "denied", "app"),
        )

        viewModel.toggleDirectory("app")

        val app = viewModel.filesState.root.children.first { it.path == "app" }
        assertEquals(DirectoryLoadState.ERROR, app.loadState)
        assertNotNull(app.errorMessage)

        // Retry after the storage recovers.
        fileSystem.failures = emptyMap()
        viewModel.retryDirectory("app")

        assertEquals(DirectoryLoadState.LOADED, viewModel.filesState.root.children.first { it.path == "app" }.loadState)
    }

    @Test
    fun `navigating up collapses the current folder`() {
        val (viewModel, _) = viewModel()
        viewModel.toggleDirectory("app")
        viewModel.toggleDirectory("app/src")
        assertEquals("app/src", viewModel.filesState.focusedPath)

        viewModel.navigateUp()

        assertEquals("app", viewModel.filesState.focusedPath)
        assertTrue("app/src" !in viewModel.filesState.expanded)
    }

    @Test
    fun `opening a text file loads it into the editor`() {
        val (viewModel, _) = viewModel()

        viewModel.openFile("app/src/main/Main.kt")

        val file = assertNotNull(viewModel.editorState.file)
        assertEquals("Main.kt", file.name)
        assertEquals("fun main() { println(\"hi\") }\n", file.content)
        assertEquals(file.content, viewModel.editorState.draft)
        assertEquals("app/src/main/Main.kt", viewModel.filesState.selectedPath)
    }

    @Test
    fun `opening a binary file explains why instead of loading it`() {
        val (viewModel, _) = viewModel()

        viewModel.openFile("logo.png")

        assertNull(viewModel.editorState.file)
        val message = assertNotNull(viewModel.editorState.statusMessage)
        assertTrue(message.contains("logo.png"))
    }

    @Test
    fun `a file that is not in the workspace keeps the editor as it was`() {
        val (viewModel, _) = viewModel()
        viewModel.openFile("app/src/main/Main.kt")
        val loaded = assertIs<OpenFile>(viewModel.editorState.file)

        viewModel.openFile("app/src/main/Deleted.kt")

        assertEquals(loaded, viewModel.editorState.file)
        assertNotNull(viewModel.editorState.statusMessage)
    }

    @Test
    fun `the breadcrumb tracks the folder the user is in`() {
        val (viewModel, _) = viewModel()
        assertEquals("project", viewModel.filesState.breadcrumb())

        viewModel.toggleDirectory("app")
        viewModel.toggleDirectory("app/src")

        assertEquals("project / app / src", viewModel.filesState.breadcrumb())
    }

    @Test
    fun `reopening the workspace reads the root again but not the folders`() {
        val (viewModel, fileSystem) = viewModel()
        viewModel.toggleDirectory("app")

        // What retrying from the Files page does.
        viewModel.loadWorkspace()

        assertEquals(DirectoryLoadState.LOADED, viewModel.filesState.rootState)
        assertTrue(!viewModel.filesState.loading)
        assertEquals(2, fileSystem.listCount(WorkspacePath.ROOT))
        assertEquals(1, fileSystem.listCount("app"))
    }

    // --- code structure -----------------------------------------------------

    @Test
    fun `the outline follows the file opened in the editor`() {
        val viewModel = structureViewModel()

        viewModel.openFile("app/src/main/Main.kt")

        val structure = viewModel.structureState
        assertEquals(CodeLanguage.KOTLIN, structure.language)
        assertNull(structure.unavailableReason)
        assertEquals(1, structure.symbolCount)
        assertTrue(structure.hasOutline)
        assertEquals(listOf(0 to "Main"), structure.flattened().map { it.first to it.second.name })
    }

    @Test
    fun `a file this build cannot parse says why instead of showing an empty outline`() {
        val viewModel = structureViewModel()

        viewModel.openFile("README.md")

        val structure = viewModel.structureState
        assertEquals(CodeLanguage.MARKDOWN, structure.language)
        assertNull(structure.outline)
        assertNotNull(structure.unavailableReason)
        assertTrue(!structure.hasOutline)
    }

    @Test
    fun `the caret reports the symbol it is inside`() {
        val viewModel = structureViewModel()
        viewModel.openFile("app/src/main/Main.kt")

        viewModel.onCursorMoved(1, 1)
        assertEquals("Main", viewModel.structureState.cursorSymbol?.name)

        viewModel.onCursorMoved(90, 1)
        assertNull(viewModel.structureState.cursorSymbol)
    }

    @Test
    fun `editing marks the structure as being re-analysed`() {
        val viewModel = structureViewModel()
        viewModel.openFile("app/src/main/Main.kt")

        viewModel.editDraft("class Changed")

        assertTrue(viewModel.structureState.analyzing)
    }

    @Test
    fun `without code intelligence the editor has no structure and does not fail`() {
        val (viewModel, _) = viewModel()

        viewModel.openFile("app/src/main/Main.kt")

        assertNull(viewModel.structureState.outline)
        assertNull(viewModel.structureState.unavailableReason)
    }

    /**
     * The same workspace runtime as [viewModel], with the real code intelligence
     * engine on top of an in-memory Kotlin tree.
     */
    private fun structureViewModel(): WorkspaceViewModel {
        val fileSystem = RecordingFileSystem(InMemoryWorkspaceFileSystem(projectFiles))
        val workspace = DefaultWorkspace(
            metadata = WorkspaceMetadata(
                id = WorkspaceId(workspaceId),
                name = "project",
                displayLocation = "content://test/project",
                persisted = true,
            ),
            fileSystem = fileSystem,
        )
        val manager = DefaultWorkspaceManager(
            backend = FixedWorkspaceBackend(workspace),
            store = InMemoryWorkspaceMetadataStore(),
        )
        runBlocking { manager.open(workspaceId) }
        return WorkspaceViewModel(
            workspaceId,
            manager,
            codeIntelligence = DefaultCodeIntelligence(
                parsers = KotlinOnlyParserProvider(),
                dispatcher = Dispatchers.Unconfined,
            ),
        )
    }
}

/** Provider that answers for Kotlin with a one-class tree. */
private class KotlinOnlyParserProvider : SyntaxParserProvider {

    override fun supportedLanguages(): Set<CodeLanguage> = setOf(CodeLanguage.KOTLIN)

    override fun parserFor(language: CodeLanguage): SyntaxParser? {
        if (language != CodeLanguage.KOTLIN) return null
        return object : SyntaxParser {
            override val language: CodeLanguage get() = CodeLanguage.KOTLIN

            override fun parse(request: ParseRequest): SyntaxParseResult =
                SyntaxParseResult.Success(mainTree())
        }
    }
}

/** `class Main` on the first two lines, so symbol-at-caret is easy to assert. */
private fun mainTree(): SyntaxTree = InMemorySyntaxTree(
    language = CodeLanguage.KOTLIN,
    root = InMemorySyntaxNode(
        type = "source_file",
        text = "source",
        start = SourcePosition(1, 1),
        end = SourcePosition(2, 1),
        children = listOf(
            InMemorySyntaxNode(
                type = "class_declaration",
                text = "class Main {",
                start = SourcePosition(1, 1),
                end = SourcePosition(2, 1),
                children = listOf(
                    InMemorySyntaxNode(
                        type = "type_identifier",
                        text = "Main",
                        start = SourcePosition(1, 7),
                        end = SourcePosition(1, 11),
                    ),
                ),
                fields = mapOf("name" to 0),
            ),
        ),
    ),
)
