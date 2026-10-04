package com.agentx.app.git

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitParsingTest {

    private val NUL = '\u0000'

    @Test
    fun `status parses branch, tracking, ahead-behind and every change kind`() {
        val raw = buildString {
            append("## main...origin/main [ahead 1, behind 2]").append(NUL)
            append(" M src/main.kt").append(NUL)
            append("?? new file.txt").append(NUL)
            append("M  staged.kt").append(NUL)
            append("R  new name.kt").append(NUL).append("old name.kt").append(NUL)
        }

        val status = GitParsing.parseStatus(raw)

        assertEquals("main", status.branch)
        assertEquals("origin/main", status.upstream)
        assertEquals(1, status.ahead)
        assertEquals(2, status.behind)
        assertEquals(GitRepositoryState.NORMAL, status.state)
        assertEquals(4, status.changes.size)

        val modified = status.changes.first { it.path == "src/main.kt" }
        assertEquals(GitChangeType.MODIFIED, modified.type)
        assertEquals(false, modified.staged)
        assertEquals(true, modified.unstaged)

        val untracked = status.changes.first { it.path == "new file.txt" }
        assertEquals(GitChangeType.UNTRACKED, untracked.type)
        assertEquals(false, untracked.staged)

        val staged = status.changes.first { it.path == "staged.kt" }
        assertEquals(GitChangeType.MODIFIED, staged.type)
        assertEquals(true, staged.staged)
        assertEquals(false, staged.unstaged)

        val renamed = status.changes.first { it.path == "new name.kt" }
        assertEquals(GitChangeType.RENAMED, renamed.type)
        assertEquals("old name.kt", renamed.originalPath)
        assertEquals(true, renamed.staged)
    }

    @Test
    fun `status reports a detached head and a repository with no commits`() {
        val detached = GitParsing.parseStatus("## HEAD (no branch)$NUL")
        assertNull(detached.branch)
        assertEquals(GitRepositoryState.DETACHED, detached.state)

        val unborn = GitParsing.parseStatus("## No commits yet on main$NUL")
        assertEquals("main", unborn.branch)
        assertEquals(GitRepositoryState.NO_COMMITS, unborn.state)
    }

    @Test
    fun `branches come back local and remote with the current one marked`() {
        val raw = listOf(
            "refs/heads/main\tmain\t*\torigin/main",
            "refs/heads/feature/x\tfeature/x\t\t",
            "refs/remotes/origin/main\torigin/main\t\t",
            "refs/remotes/origin/HEAD\torigin/HEAD\t\t",
        ).joinToString("\n")

        val branches = GitParsing.parseBranches(raw)

        assertEquals(3, branches.size)
        val main = branches.first { it.name == "main" }
        assertTrue(main.current)
        assertEquals(false, main.remote)
        assertEquals("origin/main", main.upstream)
        assertTrue(branches.none { it.name == "origin/HEAD" }, "a remote's symbolic HEAD is not a branch")
        assertTrue(branches.first { it.name == "origin/main" }.remote)
    }

    @Test
    fun `log entries keep hash, author, date and subject`() {
        val raw = "abc123\u001fabc\u001fAda\u001f2024-01-01\u001fFirst commit\u001e" +
            "\ndef456\u001fdef\u001fBob\u001f2024-01-02\u001fSecond commit\u001e"

        val log = GitParsing.parseLog(raw)

        assertEquals(2, log.size)
        assertEquals("abc123", log[0].hash)
        assertEquals("Ada", log[0].author)
        assertEquals("2024-01-01", log[0].date)
        assertEquals("First commit", log[0].subject)
        assertEquals("def", log[1].shortHash)
    }

    @Test
    fun `remotes keep each fetch url once`() {
        val raw = listOf(
            "origin\thttps://github.com/x/y.git (fetch)",
            "origin\thttps://github.com/x/y.git (push)",
            "upstream\tgit@github.com:a/b.git (fetch)",
        ).joinToString("\n")

        val remotes = GitParsing.parseRemotes(raw)

        assertEquals(2, remotes.size)
        assertEquals("origin", remotes[0].name)
        assertEquals("https://github.com/x/y.git", remotes[0].url)
        assertEquals("git@github.com:a/b.git", remotes[1].url)
    }
}
