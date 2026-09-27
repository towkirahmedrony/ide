package dev.forge.ide.workspace.memory

import dev.forge.ide.core.failure
import dev.forge.ide.core.success
import dev.forge.ide.workspace.DefaultWorkspace
import dev.forge.ide.workspace.Workspace
import dev.forge.ide.workspace.WorkspaceBackend
import dev.forge.ide.workspace.WorkspaceError
import dev.forge.ide.workspace.WorkspaceErrorCode
import dev.forge.ide.workspace.WorkspaceId
import dev.forge.ide.workspace.WorkspaceMetadata
import dev.forge.ide.workspace.WorkspaceResult

/**
 * A [WorkspaceBackend] that opens synthetic, in-memory workspaces. It lets the
 * IDE be demonstrated and unit-tested without touching device storage.
 */
class InMemoryWorkspaceBackend(
    private val seedFiles: Map<String, String> = defaultSeedFiles(),
) : WorkspaceBackend {

    private val opened = HashMap<String, Workspace>()

    override suspend fun open(handle: String): WorkspaceResult<Workspace> {
        val key = handle.trim()
        if (key.isEmpty()) {
            return failure(WorkspaceError(WorkspaceErrorCode.INVALID_HANDLE, "No workspace was selected."))
        }
        opened[key]?.let { return success(it) }

        val metadata = WorkspaceMetadata(
            id = WorkspaceId("memory-" + key.hashCode().toUInt().toString(16)),
            name = key,
            displayLocation = "demo:/$key",
            persisted = false,
        )
        val workspace = DefaultWorkspace(
            metadata = metadata,
            fileSystem = InMemoryWorkspaceFileSystem(seedFiles),
        )
        opened[key] = workspace
        return success(workspace)
    }

    companion object {
        /** A tiny sample project shown by the demo backend. */
        fun defaultSeedFiles(): Map<String, String> = mapOf(
            "README.md" to "# Demo workspace\n\nThis workspace lives in memory until the real Android runtime is used.\n",
            "src/main.kt" to "fun main() {\n    println(\"hello from the demo workspace\")\n}\n",
            "src/util/Strings.kt" to "package util\n\nfun String.shout(): String = uppercase()\n",
            "docs/notes.md" to "# Notes\n\n- open a real folder from Home\n",
        )
    }
}
