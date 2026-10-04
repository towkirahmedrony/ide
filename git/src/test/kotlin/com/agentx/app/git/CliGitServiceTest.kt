package com.agentx.app.git

import com.agentx.app.core.ForgeResult
import com.agentx.app.core.errorOrNull
import com.agentx.app.core.valueOrNull
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CliGitServiceTest {

    private val NUL = '\u0000'

    private class FakeRunner(
        private val handler: (GitProject, String) -> GitCommandOutput,
    ) : GitCommandRunner {
        data class Call(val project: GitProject, val command: String)

        val calls = mutableListOf<Call>()

        override suspend fun run(
            project: GitProject,
            command: String,
            timeoutSeconds: Long,
        ): GitCommandOutput {
            calls += Call(project, command)
            return handler(project, command)
        }
    }

    private class Projects(var current: GitProject?) : GitProjectProvider {
        override fun active(): GitProject? = current
    }

    private fun project(id: String, path: String = "/repos/$id") =
        GitProject(workspaceId = id, displayLocation = path, hostPath = path)

    private fun completed(exitCode: Int, stdout: String, stderr: String = "") =
        GitCommandOutput.Completed(exitCode = exitCode, stdout = stdout, stderr = stderr)

    // --- detection ----------------------------------------------------------

    @Test
    fun `detects a Git repository and reports its root`() = runBlocking {
        val projects = Projects(project("a"))
        val runner = FakeRunner { _, command ->
            when {
                command.startsWith("git rev-parse --is-inside-work-tree") -> completed(0, "true\n")
                command.startsWith("git rev-parse --show-toplevel") -> completed(0, "/repos/a\n")
                else -> completed(1, "", "unexpected: $command")
            }
        }

        val detection = CliGitService(projects, runner).detect("a").valueOrNull()

        assertNotNull(detection)
        assertTrue(detection.isRepository)
        assertEquals("/repos/a", detection.root)
    }

    @Test
    fun `a non-Git project is reported cleanly`() = runBlocking {
        val projects = Projects(project("a"))
        val runner = FakeRunner { _, _ ->
            completed(128, "", "fatal: not a git repository (or any of the parent directories): .git")
        }

        val detection = CliGitService(projects, runner).detect("a").valueOrNull()

        assertNotNull(detection)
        assertEquals(false, detection.isRepository)
        assertTrue(detection.reason.orEmpty().contains("not a git repository"))
    }

    // --- status and diffs ---------------------------------------------------

    @Test
    fun `status uses git and parses the current branch and changes`() = runBlocking {
        val projects = Projects(project("a"))
        val statusOutput = buildString {
            append("## main...origin/main [ahead 1]").append(NUL)
            append(" M src/main.kt").append(NUL)
            append("?? new file.txt").append(NUL)
            append("M  staged.kt").append(NUL)
        }
        val runner = FakeRunner { _, command ->
            if (command.startsWith("git status")) completed(0, statusOutput) else completed(0, "")
        }

        val status = CliGitService(projects, runner).status("a").valueOrNull()

        assertNotNull(status)
        assertEquals("main", status.branch)
        assertEquals("origin/main", status.upstream)
        assertEquals(1, status.ahead)
        assertEquals(3, status.changes.size)
        assertEquals(1, status.staged.size)
        assertEquals(2, status.unstaged.size)
    }

    @Test
    fun `diff requests the working tree and the index separately`() = runBlocking {
        val projects = Projects(project("a"))
        val runner = FakeRunner { _, command -> completed(0, "OUT:$command") }
        val service = CliGitService(projects, runner)

        val working = service.diff("a", staged = false).valueOrNull()
        val staged = service.diff("a", staged = true, paths = listOf("a b.kt")).valueOrNull()

        assertEquals("OUT:git diff", working)
        assertEquals("OUT:git diff --cached -- 'a b.kt'", staged)
    }

    // --- project awareness --------------------------------------------------

    @Test
    fun `a call for a project that is no longer active is refused`() = runBlocking {
        val projects = Projects(project("a"))
        val runner = FakeRunner { _, command ->
            if (command.startsWith("git status")) completed(0, "## main$NUL") else completed(0, "true\n")
        }
        val service = CliGitService(projects, runner)

        // Project B's screen asks while A is still active.
        val stale = service.status("b")
        val staleError = stale.errorOrNull()
        assertNotNull(staleError)
        assertEquals(GitErrorCode.NO_WORKSPACE, staleError.code)
        assertTrue(runner.calls.isEmpty(), "git must not run for a project that is not the active one")

        // B becomes active: now B's call works and A's would not.
        projects.current = project("b")
        assertEquals("b", service.status("b").valueOrNull()?.branch)
        val afterSwitch = service.status("a").errorOrNull()
        assertEquals(GitErrorCode.NO_WORKSPACE, afterSwitch?.code)
    }

    @Test
    fun `an unreachable project is reported with its reason and Git never runs`() = runBlocking {
        val projects = Projects(
            GitProject(
                workspaceId = "a",
                displayLocation = "content://tree/primary%3AProjects%2Fapp",
                hostPath = null,
                available = false,
                unavailableReason = "The project folder is not reachable as a filesystem path.",
            ),
        )
        val runner = FakeRunner { _, _ -> completed(0, "") }

        val error = CliGitService(projects, runner).status("a").errorOrNull()

        assertNotNull(error)
        assertEquals(GitErrorCode.PROJECT_UNAVAILABLE, error.code)
        assertTrue(error.message.contains("not reachable"))
        assertTrue(runner.calls.isEmpty())
    }

    // --- commit safety ------------------------------------------------------

    @Test
    fun `an empty or oversized commit message is rejected before Git runs`() = runBlocking {
        val projects = Projects(project("a"))
        val runner = FakeRunner { _, _ -> completed(0, "") }
        val service = CliGitService(projects, runner)

        val blank = service.commit("a", "   ")
        assertEquals(GitErrorCode.INVALID_MESSAGE, blank.errorOrNull()?.code)

        val oversized = service.commit("a", "x".repeat(CliGitService.MAX_MESSAGE_LENGTH + 1))
        assertEquals(GitErrorCode.INVALID_MESSAGE, oversized.errorOrNull()?.code)

        assertTrue(runner.calls.isEmpty(), "invalid messages must never reach git")
    }

    @Test
    fun `a valid commit runs git commit with the message`() = runBlocking {
        val projects = Projects(project("a"))
        val runner = FakeRunner { _, _ -> completed(0, "[main abc123] Fix bug\n") }
        val service = CliGitService(projects, runner)

        val result = service.commit("a", "Fix bug")

        assertTrue(result is ForgeResult.Success)
        assertEquals("git commit -m 'Fix bug'", runner.calls.single().command)
    }

    @Test
    fun `staging quotes a path with spaces`() = runBlocking {
        val projects = Projects(project("a"))
        val runner = FakeRunner { _, _ -> completed(0, "") }
        val service = CliGitService(projects, runner)

        service.add("a", listOf("my file.txt"))

        assertEquals("git add -- 'my file.txt'", runner.calls.single().command)
    }

    // --- remote and unusual paths ------------------------------------------

    @Test
    fun `a remote that rejects credentials reports authentication, not a random failure`() = runBlocking {
        val projects = Projects(project("a"))
        val runner = FakeRunner { _, _ ->
            completed(128, "", "remote: Authentication failed for 'https://github.com/x/y.git/'")
        }

        val error = CliGitService(projects, runner).push("a").errorOrNull()

        assertNotNull(error)
        assertEquals(GitErrorCode.AUTH_REQUIRED, error.code)
    }

    @Test
    fun `a repository with no commits has an empty log, not a failure`() = runBlocking {
        val projects = Projects(project("a"))
        val runner = FakeRunner { _, _ ->
            completed(128, "", "fatal: your current branch 'main' does not have any commits yet")
        }

        val log = CliGitService(projects, runner).log("a").valueOrNull()

        assertNotNull(log)
        assertTrue(log.isEmpty())
    }

    @Test
    fun `git runs for the active project even when its path has unusual characters`() = runBlocking {
        val path = "/repos/weird #1 (copy) ünïcode"
        val projects = Projects(GitProject(workspaceId = "a", displayLocation = path, hostPath = path))
        val runner = FakeRunner { _, _ -> completed(0, "## main$NUL") }
        val service = CliGitService(projects, runner)

        service.status("a")

        val call = runner.calls.single()
        assertEquals(path, call.project.hostPath)
        assertTrue(call.command.startsWith("git "), "the command is a git invocation")
    }
}
