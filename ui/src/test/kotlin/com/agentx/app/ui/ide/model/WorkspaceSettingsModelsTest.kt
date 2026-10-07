package com.agentx.app.ui.ide.model

import com.agentx.app.workspace.WorkspaceId
import com.agentx.app.workspace.WorkspaceMetadata
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Storage classification for Settings → Workspace.
 *
 * The classification decides how the screen describes a delete, so it has to be exact: only a direct
 * child of an AgentX-managed root is AgentX-managed, a `content://` handle is never turned into a
 * path, and anything else is a folder the user chose.
 */
class WorkspaceSettingsModelsTest {

    private val tempDirs = mutableListOf<File>()

    private fun freshDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { tempDirs += it }

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun managedRoot(): File = File(freshDir("agentx-managed"), "AgentX")

    @Test
    fun `a content handle is a SAF folder, never a path`() {
        assertEquals(
            WorkspaceStorageKind.SAF_FOLDER,
            classifyWorkspaceStorage("content://com.android.externalstorage.documents/tree/primary%3ANotes"),
        )
    }

    @Test
    fun `an empty handle is unknown`() {
        assertEquals(WorkspaceStorageKind.UNKNOWN, classifyWorkspaceStorage(null))
        assertEquals(WorkspaceStorageKind.UNKNOWN, classifyWorkspaceStorage("   "))
    }

    @Test
    fun `a direct child of an AgentX-managed root is AgentX-managed`() {
        val root = managedRoot()
        val project = File(root, "MyProject")
        project.mkdirs()

        assertEquals(
            WorkspaceStorageKind.AGENTX_MANAGED,
            classifyWorkspaceStorage(project.canonicalPath, listOf(root.canonicalPath)),
        )
    }

    @Test
    fun `a folder beside the managed root is a device folder`() {
        val root = managedRoot()
        root.mkdirs()
        val sibling = File(root.parentFile, "NotAgentX")
        sibling.mkdirs()

        assertEquals(
            WorkspaceStorageKind.DEVICE_FOLDER,
            classifyWorkspaceStorage(sibling.canonicalPath, listOf(root.canonicalPath)),
        )
    }

    @Test
    fun `a folder inside a managed project is not the managed project`() {
        val root = managedRoot()
        val nested = File(File(root, "MyProject"), "src")
        nested.mkdirs()

        assertEquals(
            WorkspaceStorageKind.DEVICE_FOLDER,
            classifyWorkspaceStorage(nested.canonicalPath, listOf(root.canonicalPath)),
        )
    }

    @Test
    fun `a project of the same name elsewhere is not AgentX-managed`() {
        val root = managedRoot()
        root.mkdirs()
        val elsewhere = File(freshDir("elsewhere"), "MyProject")
        elsewhere.mkdirs()

        assertEquals(
            WorkspaceStorageKind.DEVICE_FOLDER,
            classifyWorkspaceStorage(elsewhere.canonicalPath, listOf(root.canonicalPath)),
        )
    }

    @Test
    fun `with no known roots a path is a device folder`() {
        val folder = freshDir("plain")
        assertEquals(
            WorkspaceStorageKind.DEVICE_FOLDER,
            classifyWorkspaceStorage(folder.canonicalPath, emptyList()),
        )
    }

    @Test
    fun `a SAF workspace keeps its display name as its location`() {
        val metadata = WorkspaceMetadata(
            id = WorkspaceId("saf-1a2b"),
            name = "Notes",
            displayLocation = "Notes",
            lastOpenedAtEpochMillis = null,
            persisted = true,
        )

        val info = metadata.toWorkspaceInfo(
            handle = "content://com.android.externalstorage.documents/tree/primary%3ANotes",
        )

        assertEquals("Notes", info.location, "a SAF workspace must not be given a fabricated path")
        assertEquals(WorkspaceStorageKind.SAF_FOLDER, info.storageKind)
        assertEquals(false, info.managed)
        assertEquals(true, info.saf)
    }

    @Test
    fun `a managed workspace keeps its real path as its location`() {
        val root = managedRoot()
        val project = File(root, "MyProject")
        project.mkdirs()
        val metadata = WorkspaceMetadata(
            id = WorkspaceId("file-1"),
            name = "MyProject",
            displayLocation = project.canonicalPath,
            persisted = true,
        )

        val info = metadata.toWorkspaceInfo(
            handle = project.canonicalPath,
            managedRoots = listOf(root.canonicalPath),
        )

        assertEquals(project.canonicalPath, info.location)
        assertEquals(WorkspaceStorageKind.AGENTX_MANAGED, info.storageKind)
        assertEquals(true, info.managed)
    }
}
