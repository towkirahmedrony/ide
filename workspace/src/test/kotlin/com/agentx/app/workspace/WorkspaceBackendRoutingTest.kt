package com.agentx.app.workspace

import com.agentx.app.core.errorOrNull
import com.agentx.app.core.success
import com.agentx.app.core.valueOrNull
import com.agentx.app.workspace.memory.InMemoryWorkspaceFileSystem
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SAF trees and filesystem paths reach the same workspace port without a second abstraction.
 */
class WorkspaceBackendRoutingTest {

    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    /** Records the handles it was asked for and succeeds with a synthetic workspace. */
    private class RecordingBackend(private val label: String) : WorkspaceBackend {
        val handles = mutableListOf<String>()

        override suspend fun open(handle: String): WorkspaceResult<Workspace> {
            handles += handle
            return success(
                DefaultWorkspace(
                    metadata = WorkspaceMetadata(
                        id = WorkspaceId("$label-${handle.hashCode()}"),
                        name = label,
                        displayLocation = handle,
                        persisted = true,
                    ),
                    fileSystem = InMemoryWorkspaceFileSystem(),
                ),
            )
        }
    }

    @Test
    fun `content handles route to SAF and paths route to the filesystem`() = runBlocking {
        val saf = RecordingBackend("saf")
        val files = RecordingBackend("files")
        val routing = RoutingWorkspaceBackend.contentAndPath(saf = saf, files = files)

        routing.open("content://com.android.externalstorage.documents/tree/primary%3AProjects")
        routing.open("/data/app/clones/repo")

        assertEquals(listOf("content://com.android.externalstorage.documents/tree/primary%3AProjects"), saf.handles)
        assertEquals(listOf("/data/app/clones/repo"), files.handles)
    }

    @Test
    fun `the filesystem backend opens the real cloned directory`() = runBlocking {
        val clone = Files.createTempDirectory("agentx-clone").toFile()
        tempDirs += clone
        File(clone, ".git").mkdirs()
        File(clone, "app.kt").writeText("val x = 1\n")

        val opened = FileWorkspaceBackend().open(clone.path).valueOrNull()
        assertNotNull(opened)
        assertEquals(clone.name, opened.metadata.name)
        assertEquals(clone.canonicalPath, opened.metadata.displayLocation)

        // ".git" stays part of the project, and edits land in the clone itself.
        assertTrue(opened.fileSystem.exists(".git"))
        assertTrue(opened.fileSystem.writeFile("app.kt", "val x = 2\n").valueOrNull() == Unit)
        assertEquals("val x = 2\n", File(clone, "app.kt").readText())
    }

    @Test
    fun `a missing path is reported, not opened as an empty workspace`() = runBlocking {
        val missing = File(Files.createTempDirectory("agentx-parent").toFile(), "nope").path
        tempDirs += File(missing).parentFile

        val error = FileWorkspaceBackend().open(missing).errorOrNull()
        assertEquals(WorkspaceErrorCode.WORKSPACE_NOT_FOUND, error?.code)
    }
}
