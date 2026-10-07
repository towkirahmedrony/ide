package com.agentx.app.ui.ide.state

import com.agentx.app.core.valueOrNull
import com.agentx.app.ubuntu.ProotCommand
import com.agentx.app.ubuntu.UbuntuProjectBinding
import com.agentx.app.ubuntu.UbuntuProjectBindings
import com.agentx.app.workspace.AgentxProjectRoot
import com.agentx.app.workspace.DefaultWorkspaceManager
import com.agentx.app.workspace.FileWorkspaceBackend
import com.agentx.app.workspace.ManagedProjectDirectory
import com.agentx.app.workspace.memory.InMemoryWorkspaceMetadataStore
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 7: a project created in AgentX-managed storage is exposed to the existing runtime as
 * `/workspace` through the same active-project binding used for cloned projects — no second
 * workspace mechanism.
 */
class CreateProjectWorkspaceTest {

    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun `a newly created project resolves as the terminal workspace`() = runBlocking {
        val root = AgentxProjectRoot
            .under(Files.createTempDirectory("agentx-create-ws").toFile().also { tempDirs += it })
        val manager = DefaultWorkspaceManager(
            backend = FileWorkspaceBackend(),
            store = InMemoryWorkspaceMetadataStore(),
            projects = ManagedProjectDirectory(root),
        )

        val session = manager.createProject("MyProject").valueOrNull()
        assertNotNull(session)

        // Exactly what the terminal and Git do with the active project: resolve its handle into a
        // bind-mount at /workspace.
        val binding = UbuntuProjectBindings.resolve(
            handle = manager.currentHandle,
            displayLocation = session.workspace.metadata.displayLocation,
            isDirectory = { path -> File(path).let { it.isDirectory && it.canRead() } },
        )

        val direct = assertIs<UbuntuProjectBinding.Direct>(binding)
        assertEquals(ProotCommand.GUEST_PROJECT_ROOT, direct.guestPath)
        assertEquals("/workspace", direct.guestPath)
        val hostPath = direct.hostPath
        assertNotNull(hostPath)
        assertEquals(File(root.canonicalFile, "MyProject").path, hostPath)
        assertTrue(File(hostPath).isDirectory)
    }
}
