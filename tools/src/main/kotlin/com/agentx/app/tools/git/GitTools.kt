package com.agentx.app.tools.git

import com.agentx.app.git.GitBranch
import com.agentx.app.git.GitChangeType
import com.agentx.app.git.GitFileChange
import com.agentx.app.git.GitLogEntry
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

/** `git diff` for the working tree or the index, optionally limited to paths. */
class GitDiffTool(private val git: GitService) : Tool {

    override val definition = ToolDefinition(
        name = NAME,
        title = "Git diff",
        description = "Shows the active workspace's git diff (working tree, or staged when requested).",
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
                    description = "Optional list of repository-relative paths to limit the diff to.",
                    required = false,
                ),
            ),
        ),
        output = ToolOutputSpec(description = "The unified diff text."),
        permission = ToolPermissionDecision.ALLOW,
        capabilities = setOf(ToolCapability.READ_ONLY),
        category = ToolCategory.GIT,
        requiredPermissions = setOf(ToolPermissionLevel.READ_ONLY),
        metadata = mapOf("sideEffects" to "none"),
    )

    override suspend fun execute(input: ToolInput, context: ToolExecutionContext): ToolOutput {
        val workspaceId = context.requireWorkspaceId(NAME)
        val staged = input.boolean(ARG_STAGED) ?: false
        val paths = input.arrayValue(ARG_PATHS).orEmpty().mapNotNull { it.stringOrNull() }
        val diff = git.diff(workspaceId, staged, paths).orThrowGit(NAME)
        val text = diff.ifBlank { "(no changes)" }
        return ToolOutput(
            content = mapOf(
                "staged" to Json.of(staged),
                "paths" to Json.array(paths.map { Json.of(it) }),
                "diff" to Json.of(text),
            ),
            displayText = text,
        )
    }

    companion object {
        const val NAME = "git_diff"
        const val ARG_STAGED = "staged"
        const val ARG_PATHS = "paths"
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
        val paths = input.arrayValue(ARG_PATHS).orEmpty().mapNotNull { it.stringOrNull() }
        if (paths.isNotEmpty()) {
            git.add(workspaceId, paths).orThrowGit(NAME)
        }
        val result = git.commit(workspaceId, message).orThrowGit(NAME)
        return ToolOutput(
            content = mapOf(
                "success" to Json.of(result.success),
                "stagedPaths" to Json.array(paths.map { Json.of(it) }),
                "output" to Json.of(result.output),
            ),
            displayText = result.output.ifBlank { if (result.success) "Committed" else "Commit failed" },
        )
    }

    companion object {
        const val NAME = "git_commit"
        const val ARG_MESSAGE = "message"
        const val ARG_PATHS = "paths"
    }
}
