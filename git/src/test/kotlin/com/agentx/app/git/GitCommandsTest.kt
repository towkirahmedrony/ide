package com.agentx.app.git

import kotlin.test.Test
import kotlin.test.assertEquals

class GitCommandsTest {

    @Test
    fun `paths with spaces and shell characters are single-quoted`() {
        assertEquals("'my file.txt'", GitCommands.shellQuote("my file.txt"))
        assertEquals("'a\$b`c'", GitCommands.shellQuote("a\$b`c"))
        // An embedded single quote is closed, escaped and reopened.
        assertEquals("'it'\\''s'", GitCommands.shellQuote("it's"))
    }

    @Test
    fun `diff selects the working tree or the index and quotes paths`() {
        assertEquals("git diff", GitCommands.diff(staged = false, paths = emptyList()))
        assertEquals("git diff --cached", GitCommands.diff(staged = true, paths = emptyList()))
        assertEquals("git diff -- 'a b.kt'", GitCommands.diff(staged = false, paths = listOf("a b.kt")))
        assertEquals(
            "git diff --cached -- 'a b.kt' 'weird \$name #1'",
            GitCommands.diff(staged = true, paths = listOf("a b.kt", "weird \$name #1")),
        )
    }

    @Test
    fun `stage, commit and checkout quote their dynamic values`() {
        assertEquals("git add -- 'x y' 'z'", GitCommands.add(listOf("x y", "z")))
        assertEquals("git commit -m 'Fix bug'", GitCommands.commit("Fix bug"))
        assertEquals("git checkout 'feature/x'", GitCommands.checkout("feature/x"))
    }

    @Test
    fun `read-only commands do not depend on a path`() {
        assertEquals("git rev-parse --is-inside-work-tree", GitCommands.isRepository())
        assertEquals("git remote -v", GitCommands.remotes())
        assertEquals("git pull", GitCommands.pull())
        assertEquals("git push", GitCommands.push())
    }
}
