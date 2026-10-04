package com.agentx.app.git

/**
 * The exact command lines the service runs, kept pure so the quoting rules are directly
 * testable.
 *
 * Git runs inside the embedded runtime with the working directory already at `/workspace`
 * (see [GitCommandRunner]), so the project path is never part of a command — only
 * repository-relative paths are, and those can contain spaces or other shell characters, which
 * is why every dynamic value is single-quoted.
 */
object GitCommands {

    fun isRepository(): String = "git rev-parse --is-inside-work-tree"

    fun repositoryRoot(): String = "git rev-parse --show-toplevel"

    /**
     * `--porcelain=v1 -z -b`: machine-readable, NUL-delimited (so paths with spaces, quotes or
     * newlines are not mangled), with the branch header. `--untracked-files=all` lists every
     * untracked file rather than collapsing whole directories.
     */
    fun status(): String = "git status --porcelain=v1 -b -z --untracked-files=all"

    fun diff(staged: Boolean, paths: List<String>): String {
        val base = if (staged) "git diff --cached" else "git diff"
        return if (paths.isEmpty()) base else "$base -- ${pathArguments(paths)}"
    }

    fun branches(): String =
        "git for-each-ref --format=%(refname)%09%(refname:short)%09%(HEAD)%09%(upstream:short) " +
            "refs/heads refs/remotes"

    /** `%x1f` separates fields and `%x1e` ends each commit, so subjects can hold anything else. */
    fun log(limit: Int): String =
        "git log --max-count=$limit --date=short --format=%H%x1f%h%x1f%an%x1f%ad%x1f%s%x1e"

    fun remotes(): String = "git remote -v"

    fun add(paths: List<String>): String = "git add -- ${pathArguments(paths)}"

    fun commit(message: String): String = "git commit -m ${shellQuote(message)}"

    fun checkout(branch: String): String = "git checkout ${shellQuote(branch)}"

    fun pull(): String = "git pull"

    fun push(): String = "git push"

    private fun pathArguments(paths: List<String>): String = paths.joinToString(" ") { shellQuote(it) }

    /**
     * POSIX single-quote escaping: the value is wrapped in single quotes and every embedded
     * single quote is closed, escaped and reopened. Safe for spaces, `$`, backticks, newlines
     * and non-ASCII; never lets a value introduce a second command.
     */
    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
