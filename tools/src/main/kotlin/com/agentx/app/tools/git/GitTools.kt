package com.agentx.app.tools.git

import com.agentx.app.core.ForgeResult
import com.agentx.app.git.GitBranch
import com.agentx.app.git.GitChangeType
import com.agentx.app.git.GitFileChange
import com.agentx.app.git.GitLogEntry
import com.agentx.app.git.GitPushService
import com.agentx.app.git.GitService
import com.agentx.app.git.GitStatus
import com.agentx.app.tools.Json
import com.agentx.app.tools.JsonObject
import com.agentx.app.tools.JsonValue
import com.agentx.app.tools.Tool
import com.agentx.app.tools.ToolCapability
import com.agentx.app.tools.ToolCategory
import com.agentx.app.tools.ToolDefinition
import com.agentx.app.tools.ToolErrorCode
import com.agentx.app.tools.ToolExecutionContext
import com.agentx.app.tools.ToolExecutionError
import com.agentx.app.tools.ToolInput
import com.agentx.app.tools.ToolInputSchema
import com.agentx.app.tools.ToolOutput
import com.agentx.app.tools.ToolOutputSpec
import com.agentx.app.tools.ToolParameter
import com.agentx.app.tools.ToolParameterType
import com.agentx.app.tools.ToolPermissionDecision
import com.agentx.app.tools.ToolPermissionLevel
import com.agentx.app.tools.SecretRedactor
import com.agentx.app.tools.stringOrNull

/**
 * Read-only git tools for the active workspace.
 *
 * They are thin adapters over the project's existing [GitService], which already
 * resolves the active project live and refuses a call for a workspace it is no
 * longer pointed at. No command is built here and no repository state is cached,
 * so `git` stays the single source of truth.
 *
 * The tools declare only the READ_ONLY capability so a read-only role (Reviewer,
 * Planner, Security Reviewer) can inspect history and diffs without ever being
 * granted a write tool.
 */
class GitStatusTool(private val git: GitService) : Tool {

    override val definition = ToolDefinition(
        name = NAME,
        title = "Git status",
        description = "Shows the active workspace's git branch, upstream and changed files (staged and unstaged).",
        inputSchema = ToolInputSchema(),
        output = ToolOutputSpec(description = "Branch, upstream, ahead/behind and the changed paths."),
        permission = ToolPermissionDecision.ALLOW,
        capabilities = setOf(ToolCapability.READ_ONLY),
        category = ToolCategory.GIT,
        requiredPermissions = setOf(ToolPermissionLevel.READ_ONLY),
        metadata = mapOf("sideEffects" to "none"),
    )

    override suspend fun execute(input: ToolInput, context: ToolExecutionContext): ToolOutput {
        val workspaceId = context.requireWorkspaceId(NAME)
        val status = git.status(workspaceId).orThrowGit(NAME)
        return ToolOutput(
            content = status.toJson(),
            displayText = status.render(),
        )
    }

    private fun GitStatus.toJson(): JsonObject = mapOf(
        "branch" to (branch?.let { Json.of(it) } ?: JsonValue.Null),
        "state" to Json.of(state.name),
        "upstream" to (upstream?.let { Json.of(it) } ?: JsonValue.Null),
        "ahead" to Json.of(ahead),
        "behind" to Json.of(behind),
        "clean" to Json.of(isClean),
        "changes" to Json.array(changes.map { it.toJson() }),
    )

    private fun GitStatus.render(): String = buildString {
        append("branch ").append(branch ?: "(detached)")
        if (upstream != null) append(" → ").append(upstream)
        if (ahead > 0 || behind > 0) append(" (+").append(ahead).append("/-").append(behind).append(')')
        if (isClean) {
            append("\n(clean)")
        } else {
            changes.forEach { change ->
                append("\n").append(change.indexCode()).append(' ').append(change.path)
            }
        }
    }

    private fun GitFileChange.indexCode(): String = buildString {
        append(if (staged) 'S' else ' ')
        append(if (unstaged) 'U' else ' ')
    }

    private fun GitFileChange.toJson(): JsonValue = Json.obj(
        buildMap<String, JsonValue> {
            put("path", Json.of(path))
            put("type", Json.of(type.name))
            put("staged", Json.of(staged))
            put("unstaged", Json.of(unstaged))
            originalPath?.let { put("originalPath", Json.of(it)) }
        },
    )

    companion object {
        const val NAME = "git_status"
    }
}

/**
 * `git diff` for the working tree or the index, optionally limited to paths.
 *
 * The repository is never chosen by the caller: it is resolved from the active
 * workspace context by the existing [GitService], and a call for a workspace the
 * service is not pointed at is refused. The result is a bounded, secret-redacted
 * unified diff plus the structured change summary the agent needs (changed files,
 * additions, deletions), so a huge diff cannot flood the model and a crafted
 * pathspec cannot pull in `.git` internals.
 */
class GitDiffTool(private val git: GitService) : Tool {

    override val definition = ToolDefinition(
        name = NAME,
        title = "Git diff",
        description = "Shows the active workspace's git diff (working tree, or staged when requested) " +
            "together with the changed files and a change summary.",
        inputSchema = ToolInputSchema(
            parameters = listOf(
                ToolParameter(
                    name = ARG_STAGED,
                    type = ToolParameterType.BOOLEAN,
                    description = "Diff the index against HEAD instead of the working tree.",
                    required = false,
                ),
                ToolParameter(
                    name = ARG_PATHS,
                    type = ToolParameterType.ARRAY,
                    description = "Optional list of repository-relative paths to limit the diff to. " +
                        "Protected paths such as .git are refused.",
                    required = false,
                ),
            ),
        ),
        output = ToolOutputSpec(
            description = "Branch, changed files, additions/deletions and the bounded unified diff text.",
        ),
        permission = ToolPermissionDecision.ALLOW,
        capabilities = setOf(ToolCapability.READ_ONLY),
        category = ToolCategory.GIT,
        requiredPermissions = setOf(ToolPermissionLevel.READ_ONLY),
        metadata = mapOf("sideEffects" to "none"),
    )

    override suspend fun execute(input: ToolInput, context: ToolExecutionContext): ToolOutput {
        val workspaceId = context.requireWorkspaceId(NAME)
        val staged = input.boolean(ARG_STAGED) ?: false
        val paths = input.arrayValue(ARG_PATHS).orEmpty()
            .mapNotNull { it.stringOrNull() }
            .map { validateRepositoryPath(it, NAME) }
            .distinct()

        val status = git.status(workspaceId).orThrowGit(NAME)
        val raw = git.diff(workspaceId, staged, paths).orThrowGit(NAME)
        val redacted = SecretRedactor.redactText(raw)
        val bounded = redacted.take(MAX_DIFF_CHARS)
        val truncated = bounded.length < redacted.length
        val summary = summarize(redacted)
        val text = bounded.ifBlank { NO_CHANGES }

        return ToolOutput(
            content = mapOf(
                "branch" to (status.branch?.let { Json.of(it) } ?: JsonValue.Null),
                "clean" to Json.of(status.isClean),
                "staged" to Json.of(staged),
                "paths" to Json.array(paths.map { Json.of(it) }),
                "changedFiles" to Json.array(status.changes.map { it.toJson() }),
                "changedFileCount" to Json.of(status.changes.size),
                "additions" to Json.of(summary.additions),
                "deletions" to Json.of(summary.deletions),
                "diff" to Json.of(text),
                "truncated" to Json.of(truncated),
                "returnedBytes" to Json.of(bounded.length),
            ),
            displayText = text,
        )
    }

    private fun summarize(diff: String): DiffSummary {
        var additions = 0
        var deletions = 0
        for (line in diff.lineSequence()) {
            when {
                line.startsWith("+++") || line.startsWith("---") -> Unit
                line.startsWith("+") -> additions++
                line.startsWith("-") -> deletions++
            }
        }
        return DiffSummary(additions, deletions)
    }

    private data class DiffSummary(val additions: Int, val deletions: Int)

    private fun GitFileChange.toJson(): JsonValue = Json.obj(
        buildMap<String, JsonValue> {
            put("path", Json.of(path))
            put("type", Json.of(type.name))
            put("staged", Json.of(staged))
            put("unstaged", Json.of(unstaged))
            originalPath?.let { put("originalPath", Json.of(it)) }
        },
    )

    companion object {
        const val NAME = "git_diff"
        const val ARG_STAGED = "staged"
        const val ARG_PATHS = "paths"

        /** Upper bound on the diff characters returned to the model. */
        const val MAX_DIFF_CHARS: Int = 200_000

        const val NO_CHANGES: String = "(no changes)"
    }
}

/** Recent commits, newest first. */
class GitLogTool(private val git: GitService) : Tool {

    override val definition = ToolDefinition(
        name = NAME,
        title = "Git log",
        description = "Lists recent commits of the active workspace, newest first.",
        inputSchema = ToolInputSchema(
            parameters = listOf(
                ToolParameter(
                    name = ARG_LIMIT,
                    type = ToolParameterType.NUMBER,
                    description = "Maximum commits to return (default ${GitService.DEFAULT_LOG_LIMIT}).",
                    required = false,
                ),
            ),
        ),
        output = ToolOutputSpec(description = "Commit hash, author, date and subject."),
        permission = ToolPermissionDecision.ALLOW,
        capabilities = setOf(ToolCapability.READ_ONLY),
        category = ToolCategory.GIT,
        requiredPermissions = setOf(ToolPermissionLevel.READ_ONLY),
        metadata = mapOf("sideEffects" to "none"),
    )

    override suspend fun execute(input: ToolInput, context: ToolExecutionContext): ToolOutput {
        val workspaceId = context.requireWorkspaceId(NAME)
        val limit = input.number(ARG_LIMIT)?.toInt() ?: GitService.DEFAULT_LOG_LIMIT
        val entries = git.log(workspaceId, limit).orThrowGit(NAME)
        return ToolOutput(
            content = mapOf(
                "count" to Json.of(entries.size),
                "commits" to Json.array(entries.map { it.toJson() }),
            ),
            displayText = if (entries.isEmpty()) "(no commits)" else entries.joinToString("\n") { it.render() },
        )
    }

    private fun GitLogEntry.toJson(): JsonValue = Json.obj(
        "hash" to Json.of(hash),
        "shortHash" to Json.of(shortHash),
        "author" to Json.of(author),
        "date" to Json.of(date),
        "subject" to Json.of(subject),
    )

    private fun GitLogEntry.render(): String = "$shortHash $date $author — $subject"

    companion object {
        const val NAME = "git_log"
        const val ARG_LIMIT = "limit"
    }
}

/** Local and remote branches of the active workspace. */
class GitBranchesTool(private val git: GitService) : Tool {

    override val definition = ToolDefinition(
        name = NAME,
        title = "Git branches",
        description = "Lists the active workspace's local and remote branches and marks the current one.",
        inputSchema = ToolInputSchema(),
        output = ToolOutputSpec(description = "Branch names, current flag and upstream."),
        permission = ToolPermissionDecision.ALLOW,
        capabilities = setOf(ToolCapability.READ_ONLY),
        category = ToolCategory.GIT,
        requiredPermissions = setOf(ToolPermissionLevel.READ_ONLY),
        metadata = mapOf("sideEffects" to "none"),
    )

    override suspend fun execute(input: ToolInput, context: ToolExecutionContext): ToolOutput {
        val workspaceId = context.requireWorkspaceId(NAME)
        val branches = git.branches(workspaceId).orThrowGit(NAME)
        return ToolOutput(
            content = mapOf(
                "count" to Json.of(branches.size),
                "branches" to Json.array(branches.map { it.toJson() }),
            ),
            displayText = if (branches.isEmpty()) "(no branches)" else branches.joinToString("\n") { it.render() },
        )
    }

    private fun GitBranch.toJson(): JsonValue = Json.obj(
        buildMap<String, JsonValue> {
            put("name", Json.of(name))
            put("current", Json.of(current))
            put("remote", Json.of(remote))
            upstream?.let { put("upstream", Json.of(it)) }
        },
    )

    private fun GitBranch.render(): String = buildString {
        append(if (current) "* " else "  ").append(name)
        if (remote) append(" (remote)")
        upstream?.let { append(" → ").append(it) }
    }

    companion object {
        const val NAME = "git_branches"
    }
}

/**
 * The controlled write operation: stage the requested paths and commit them.
 *
 * This is the only git tool that mutates the repository. It requires
 * [ToolPermissionLevel.GIT_WRITE] and declares ASK, so the router pauses for the
 * user's approval, and it never stages the whole tree implicitly — a path list
 * is mandatory when paths are staged. The real command construction and the
 * empty-message rejection live in `GitService`/`CliGitService`, not here.
 */
class GitCommitTool(private val git: GitService) : Tool {

    override val definition = ToolDefinition(
        name = NAME,
        title = "Git commit",
        description = "Stages the given repository-relative paths (when provided) and commits them with " +
            "the given message. Never stages the whole tree implicitly.",
        inputSchema = ToolInputSchema(
            parameters = listOf(
                ToolParameter(
                    name = ARG_MESSAGE,
                    type = ToolParameterType.STRING,
                    description = "Commit message.",
                    required = true,
                ),
                ToolParameter(
                    name = ARG_PATHS,
                    type = ToolParameterType.ARRAY,
                    description = "Repository-relative paths to stage before committing. When omitted, " +
                        "only already-staged changes are committed.",
                    required = false,
                ),
            ),
        ),
        output = ToolOutputSpec(description = "Whether the commit succeeded and git's own output."),
        permission = ToolPermissionDecision.ASK,
        capabilities = setOf(ToolCapability.GIT, ToolCapability.MUTATING),
        category = ToolCategory.GIT,
        requiredPermissions = setOf(ToolPermissionLevel.GIT_WRITE),
        metadata = mapOf("sideEffects" to "repository-write"),
    )

    override suspend fun execute(input: ToolInput, context: ToolExecutionContext): ToolOutput {
        val workspaceId = context.requireWorkspaceId(NAME)
        val message = input.string(ARG_MESSAGE)?.trim().orEmpty()
        if (message.isEmpty()) {
            throw ToolExecutionError(
                code = ToolErrorCode.INVALID_ARGUMENTS,
                message = "'$ARG_MESSAGE' must not be blank",
                toolName = NAME,
            )
        }
        // The repository is resolved from the workspace context, so the model can
        // never choose a different one. Every path it does supply is validated first.
        val paths = input.arrayValue(ARG_PATHS).orEmpty()
            .mapNotNull { it.stringOrNull() }
            .map { validateRepositoryPath(it, NAME) }
            .distinct()
        if (paths.isNotEmpty()) {
            git.add(workspaceId, paths).orThrowGit(NAME)
        }
        // Reject an empty commit before Git runs: when no paths were staged implicitly
        // and nothing is already staged, there is exactly nothing to commit.
        val status = git.status(workspaceId).orThrowGit(NAME)
        if (paths.isEmpty() && status.staged.isEmpty()) {
            throw ToolExecutionError(
                code = ToolErrorCode.EXECUTION_FAILED,
                message = "Nothing is staged to commit.",
                toolName = NAME,
            )
        }
        val result = git.commit(workspaceId, message).orThrowGit(NAME)
        val parsed = parseCommitOutput(result.output)
        return ToolOutput(
            content = mapOf(
                "success" to Json.of(result.success),
                "branch" to (parsed.branch?.let { Json.of(it) } ?: JsonValue.Null),
                "commitSha" to (parsed.sha?.let { Json.of(it) } ?: JsonValue.Null),
                "stagedPaths" to Json.array(paths.map { Json.of(it) }),
                "message" to Json.of(message),
                "output" to Json.of(result.output),
            ),
            displayText = result.output.ifBlank { if (result.success) "Committed" else "Commit failed" },
        )
    }

    /**
     * Reads the branch and commit id out of git's own `[<branch> <sha>] <subject>`
     * line. Pure, so the parsing is directly testable; a message git did not print in
     * this shape simply yields nulls rather than a wrong id.
     */
    private fun parseCommitOutput(output: String): CommitSummary {
        val match = COMMIT_LINE.find(output) ?: return CommitSummary(null, null)
        return CommitSummary(branch = match.groupValues[1], sha = match.groupValues[2])
    }

    private data class CommitSummary(val branch: String?, val sha: String?)

    companion object {
        const val NAME = "git_commit"
        const val ARG_MESSAGE = "message"
        const val ARG_PATHS = "paths"

        /** git prints `[main 1a2b3c4] subject` on a successful commit. */
        private val COMMIT_LINE = Regex("""\[([^\]\s]+)\s+([0-9a-fA-F]{7,40})\]""")
    }
}

/**
 * The high-impact remote operation: push the current branch to the configured GitHub
 * remote's `main`.
 *
 * It carries no arguments at all, so the model cannot choose a repository, a remote
 * URL, a branch, a refspec, or a credential: the workspace is taken from context, the
 * remote from the repository, the target is always `main`, and the token is obtained
 * by the push service through the credential gateway. It declares ASK, so the router
 * pauses for the user's approval before anything reaches the network, and the service
 * underneath cannot force, delete a ref, or push a tag.
 */
class GitPushTool(private val push: GitPushService) : Tool {

    override val definition = ToolDefinition(
        name = NAME,
        title = "Git push",
        description = "Pushes the active workspace's current branch to the configured GitHub remote's " +
            "'main' using the connected GitHub account. Never force-pushes, deletes a branch, or pushes tags.",
        inputSchema = ToolInputSchema(),
        output = ToolOutputSpec(description = "Success, remote, branch and the pushed commit SHA."),
        permission = ToolPermissionDecision.ASK,
        capabilities = setOf(ToolCapability.GIT, ToolCapability.MUTATING),
        category = ToolCategory.GIT,
        requiredPermissions = setOf(ToolPermissionLevel.GIT_WRITE),
        metadata = mapOf("sideEffects" to "remote-write", "targetBranch" to GitPushService.MAIN_BRANCH),
    )

    override suspend fun execute(input: ToolInput, context: ToolExecutionContext): ToolOutput {
        val workspaceId = context.requireWorkspaceId(NAME)
        return when (val result = push.push(workspaceId)) {
            is ForgeResult.Success -> {
                val value = result.value
                ToolOutput(
                    content = mapOf(
                        "success" to Json.of(true),
                        "remote" to Json.of(value.remote),
                        "branch" to Json.of(value.branch),
                        "commitSha" to (value.commitSha?.let { Json.of(it) } ?: JsonValue.Null),
                        "message" to Json.of(value.message),
                    ),
                    displayText = buildString {
                        append("Pushed '").append(value.branch).append("' to '").append(value.remote).append('\'')
                        value.commitSha?.let { append(" ($it)") }
                    },
                )
            }

            is ForgeResult.Failure -> throw result.error.toToolError(NAME)
        }
    }

    companion object {
        const val NAME = "git_push"
    }
}
