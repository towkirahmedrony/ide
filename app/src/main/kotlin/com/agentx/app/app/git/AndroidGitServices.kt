package com.agentx.app.app.git

import com.agentx.app.git.GitCommandOutput
import com.agentx.app.git.GitCommandRunner
import com.agentx.app.git.GitProject
import com.agentx.app.git.GitProjectProvider
import com.agentx.app.ubuntu.LocalUbuntuRuntime
import com.agentx.app.ubuntu.ProotCommand
import com.agentx.app.ubuntu.UbuntuProjectBinding
import com.agentx.app.ubuntu.UbuntuProjectBindings
import com.agentx.app.workspace.WorkspaceManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The active project, resolved live from the workspace runtime.
 *
 * It reuses the same [UbuntuProjectBindings] decision the terminal uses, so Git and the shell
 * treat a project identically: a real path (an AgentX clone) is bound as-is, a SAF tree is
 * resolved to the phone-storage path it names, and a folder that cannot be reached as a path is
 * reported — never silently substituted.
 */
class ActiveGitProjectProvider(
    private val manager: WorkspaceManager,
) : GitProjectProvider {

    override fun active(): GitProject? {
        val session = manager.current ?: return null
        val handle = manager.currentHandle
        val displayLocation = session.workspace.metadata.displayLocation
        val binding = UbuntuProjectBindings.resolve(
            handle = handle,
            displayLocation = displayLocation,
            isDirectory = { path -> File(path).let { it.isDirectory && it.canRead() } },
        )
        return when (binding) {
            is UbuntuProjectBinding.Direct -> GitProject(
                workspaceId = session.workspace.id.value,
                displayLocation = displayLocation,
                hostPath = binding.hostPath,
                available = true,
            )

            is UbuntuProjectBinding.Home -> GitProject(
                workspaceId = session.workspace.id.value,
                displayLocation = displayLocation,
                hostPath = null,
                available = false,
                unavailableReason = binding.reason,
            )
        }
    }
}

/**
 * Runs Git inside the embedded Ubuntu runtime, in the guest's `/workspace`.
 *
 * The command is executed through the same [LocalUbuntuRuntime] path the terminal uses, with the
 * project's real host path bound at [ProotCommand.GUEST_PROJECT_ROOT], so `git` reads and writes
 * exactly the files the IDE and the shell see. This class never rewrites the PRoot/PTY machinery.
 */
class UbuntuGitCommandRunner(
    private val runtime: LocalUbuntuRuntime,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : GitCommandRunner {

    override suspend fun run(
        project: GitProject,
        command: String,
        timeoutSeconds: Long,
    ): GitCommandOutput {
        val hostPath = project.hostPath
            ?: return GitCommandOutput.Unavailable(
                project.unavailableReason ?: "This project's files are not reachable by Git.",
            )
        if (!runtime.isReady()) {
            return GitCommandOutput.Unavailable(
                "The embedded Ubuntu runtime is not installed yet, so Git is unavailable.",
            )
        }
        return withContext(ioDispatcher) {
            val result = runtime.executeBlocking(
                command = command,
                projectHostPath = hostPath,
                workingDirectory = ProotCommand.GUEST_PROJECT_ROOT,
                timeoutSeconds = timeoutSeconds,
            )
            GitCommandOutput.Completed(
                exitCode = result.exitCode,
                stdout = result.stdout,
                stderr = result.stderr,
                timedOut = result.timedOut,
            )
        }
    }
}
