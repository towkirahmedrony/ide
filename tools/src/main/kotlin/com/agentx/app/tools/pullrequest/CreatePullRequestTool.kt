package com.agentx.app.tools.pullrequest

import com.agentx.app.core.ForgeResult
import com.agentx.app.core.pullrequest.NewPullRequest
import com.agentx.app.core.pullrequest.PullRequestError
import com.agentx.app.core.pullrequest.PullRequestRef
import com.agentx.app.core.pullrequest.PullRequestService
import com.agentx.app.tools.Json
import com.agentx.app.tools.Tool
import com.agentx.app.tools.ToolCapability
import com.agentx.app.tools.ToolCategory
import com.agentx.app.tools.ToolConnectionCapability
import com.agentx.app.tools.ToolConnectionRequirement
import com.agentx.app.tools.ToolConnectionType
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

/**
 * Opens a GitHub pull request — the one optional, approval-gated GitHub write.
 *
 * It is deliberately separate from the autonomous workflow: pushing to `main`
 * stays the default, a successful push never implies a pull request, and this tool
 * only runs when the agent explicitly asks for it and the existing Tool Bus
 * approval mechanism authorizes it.
 *
 * The tool carries no credential. The repository defaults to the active project
 * (resolved by [repository], like `ci_verification`), and a `repository` argument,
 * when given, must match the active project. Head and base are branch names only:
 * nothing here can create a branch, delete one, force-push, or rewrite history.
 * The service underneath performs the call through the existing credential
 * gateway, so the token never appears in an argument, a result, or an exception.
 */
class CreatePullRequestTool(
    private val service: PullRequestService,
    private val repository: PullRequestRepositoryProvider,
) : Tool {

    override val definition = ToolDefinition(
        name = NAME,
        title = "Create pull request",
        description = "Opens a GitHub pull request from a head branch into a base branch using the " +
            "connected GitHub account. Optional and approval-gated: it is never run automatically " +
            "after a push and it never creates, switches or deletes a branch.",
        inputSchema = ToolInputSchema(
            parameters = listOf(
                ToolParameter(
                    name = ARG_TITLE,
                    type = ToolParameterType.STRING,
                    description = "Pull request title.",
                    required = true,
                ),
                ToolParameter(
                    name = ARG_HEAD,
                    type = ToolParameterType.STRING,
                    description = "Branch holding the changes to propose (the head).",
                    required = true,
                ),
                ToolParameter(
                    name = ARG_BASE,
                    type = ToolParameterType.STRING,
                    description = "Branch to merge into (the base). Defaults to '$DEFAULT_BASE'.",
                    required = false,
                ),
                ToolParameter(
                    name = ARG_BODY,
                    type = ToolParameterType.STRING,
                    description = "Pull request description. Never carries a credential or a token.",
                    required = false,
                ),
                ToolParameter(
                    name = ARG_REPOSITORY,
                    type = ToolParameterType.STRING,
                    description = "Repository as 'owner/name'. Defaults to the active project; when " +
                        "supplied it must match the active project.",
                    required = false,
                ),
            ),
        ),
        output = ToolOutputSpec(description = "PR number, title, state, head/base branch, repository and URL."),
        permission = ToolPermissionDecision.ASK,
        capabilities = setOf(ToolCapability.GIT, ToolCapability.MUTATING, ToolCapability.NETWORK),
        category = ToolCategory.GIT,
        requiredPermissions = setOf(ToolPermissionLevel.GIT_WRITE),
        connectionRequirement = ToolConnectionRequirement(
            type = ToolConnectionType.GITHUB,
            capability = ToolConnectionCapability.PULL_REQUEST,
        ),
        metadata = mapOf("sideEffects" to "github-write", "action" to "create_pull_request"),
    )

    override suspend fun execute(input: ToolInput, context: ToolExecutionContext): ToolOutput {
        val target = resolveRepository(input)
        val title = input.string(ARG_TITLE)?.trim().orEmpty()
        if (title.isEmpty()) {
            throw invalid("'$ARG_TITLE' must not be blank")
        }
        if (title.length > MAX_TITLE_LENGTH) {
            throw invalid("'$ARG_TITLE' must be at most $MAX_TITLE_LENGTH characters")
        }
        val head = validateBranch(input.string(ARG_HEAD), ARG_HEAD)
        val base = validateBranch(
            input.string(ARG_BASE)?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE,
            ARG_BASE,
        )
        if (head == base) {
            throw invalid("The head and base branches must differ (both were '$head')")
        }
        val body = input.string(ARG_BODY)?.trim().orEmpty()

        val created = when (val result = service.create(NewPullRequest(target, title, body, head, base))) {
            is ForgeResult.Success -> result.value
            is ForgeResult.Failure -> throw result.error.toToolError(NAME)
        }

        return ToolOutput(
            content = mapOf(
                "repository" to Json.of(created.repository.fullName),
                "number" to Json.of(created.number),
                "title" to Json.of(created.title),
                "state" to Json.of(created.state),
                "base" to Json.of(created.baseBranch),
                "head" to Json.of(created.headBranch),
                "url" to Json.of(created.htmlUrl),
                "draft" to Json.of(created.draft),
            ),
            displayText = "Pull request #${created.number} created: ${created.title}\n${created.htmlUrl}",
        )
    }

    /**
     * The repository to target: the active project unless the caller names one, and
     * a named repository must be the active one so the model cannot direct the write
     * at a different account/repository than the workspace it is working in.
     */
    private suspend fun resolveRepository(input: ToolInput): PullRequestRef {
        val requested = input.string(ARG_REPOSITORY)?.trim()?.takeIf { it.isNotBlank() }
        val active = repository.resolve()
        if (requested == null) {
            return active ?: throw ToolExecutionError(
                code = ToolErrorCode.WORKSPACE_UNAVAILABLE,
                message = "No GitHub repository is available. Open a project with a GitHub remote first.",
                toolName = NAME,
            )
        }
        val parsed = parseRepository(requested)
            ?: throw invalid("'$requested' must be a repository in owner/name form")
        if (active != null && !parsed.sameRepository(active)) {
            throw invalid("'$requested' does not match the active project '${active.fullName}'")
        }
        return parsed
    }

    private fun parseRepository(raw: String): PullRequestRef? {
        val cleaned = raw.trim().removePrefix("/").removeSuffix(".git")
        val parts = cleaned.split('/')
        if (parts.size != 2) return null
        val owner = parts[0]
        val name = parts[1]
        if (owner.isEmpty() || name.isEmpty()) return null
        if (!OWNER_PATTERN.matches(owner) || !NAME_PATTERN.matches(name)) return null
        return PullRequestRef(owner, name)
    }

    /** Rejects an obviously malformed or unsafe branch name before it reaches GitHub. */
    private fun validateBranch(raw: String?, argument: String): String {
        val branch = raw?.trim().orEmpty()
        if (branch.isEmpty()) {
            throw invalid("'$argument' must not be blank")
        }
        val unsafe = branch.startsWith("/") || branch.endsWith("/") ||
            branch.endsWith(".lock") || branch.startsWith("-") || branch.startsWith("refs/") ||
            ".." in branch || "@{" in branch
        if (unsafe || !BRANCH_PATTERN.matches(branch)) {
            throw invalid("'$branch' is not a valid branch name")
        }
        return branch
    }

    private fun invalid(message: String): ToolExecutionError = ToolExecutionError(
        code = ToolErrorCode.INVALID_ARGUMENTS,
        message = message,
        toolName = NAME,
    )

    companion object {
        const val NAME: String = "create_pr"
        const val ARG_TITLE: String = "title"
        const val ARG_HEAD: String = "head"
        const val ARG_BASE: String = "base"
        const val ARG_BODY: String = "body"
        const val ARG_REPOSITORY: String = "repository"

        const val DEFAULT_BASE: String = "main"
        const val MAX_TITLE_LENGTH: Int = 256

        /** GitHub owner/org names: letters, digits and hyphens. */
        private val OWNER_PATTERN = Regex("[A-Za-z0-9-]+")

        /** GitHub repository names: letters, digits, '.', '_' and '-'. */
        private val NAME_PATTERN = Regex("[A-Za-z0-9._-]+")

        /**
         * Conservative branch-name characters. Anything outside this set — spaces,
         * `~`, `^`, `:`, `?`, `*`, `[`, `\`, `@` — is refused before GitHub sees it.
         */
        private val BRANCH_PATTERN = Regex("[A-Za-z0-9._/-]+")
    }
}

private fun PullRequestRef.sameRepository(other: PullRequestRef): Boolean =
    owner.equals(other.owner, ignoreCase = true) && name.equals(other.name, ignoreCase = true)

/**
 * Maps a structured pull-request error onto the tool result contract without
 * collapsing categories: the specific reason is preserved in
 * `details["pullRequestCode"]` so a validation problem stays distinguishable from
 * an authentication or transport failure.
 */
internal fun PullRequestError.toToolError(toolName: String): ToolExecutionError {
    val code = when (this) {
        PullRequestError.NoConnection,
        PullRequestError.Unauthenticated,
        PullRequestError.Forbidden,
        -> ToolErrorCode.PERMISSION_DENIED

        is PullRequestError.NotFound,
        is PullRequestError.ValidationFailed,
        -> ToolErrorCode.INVALID_ARGUMENTS

        is PullRequestError.Conflict,
        PullRequestError.RateLimited,
        PullRequestError.NetworkFailure,
        is PullRequestError.ServerError,
        is PullRequestError.MalformedResponse,
        is PullRequestError.Unknown,
        -> ToolErrorCode.EXECUTION_FAILED
    }
    return ToolExecutionError(
        code = code,
        message = messageFor(this),
        toolName = toolName,
        details = mapOf("pullRequestCode" to Json.of(codeName(this))),
    )
}

private fun codeName(error: PullRequestError): String = when (error) {
    PullRequestError.NoConnection -> "NO_CONNECTION"
    PullRequestError.Unauthenticated -> "UNAUTHENTICATED"
    PullRequestError.Forbidden -> "FORBIDDEN"
    is PullRequestError.NotFound -> "NOT_FOUND"
    is PullRequestError.Conflict -> "CONFLICT"
    is PullRequestError.ValidationFailed -> "VALIDATION_FAILED"
    PullRequestError.RateLimited -> "RATE_LIMITED"
    PullRequestError.NetworkFailure -> "NETWORK_FAILURE"
    is PullRequestError.ServerError -> "SERVER_ERROR"
    is PullRequestError.MalformedResponse -> "MALFORMED_RESPONSE"
    is PullRequestError.Unknown -> "UNKNOWN"
}

private fun messageFor(error: PullRequestError): String = when (error) {
    PullRequestError.NoConnection -> "No connected GitHub account with pull-request access is available."
    PullRequestError.Unauthenticated -> "The GitHub credential expired; reconnect the account."
    PullRequestError.Forbidden -> "GitHub refused the pull request."
    is PullRequestError.NotFound -> error.detail
    is PullRequestError.Conflict -> error.detail
    is PullRequestError.ValidationFailed -> error.detail
    PullRequestError.RateLimited -> "GitHub rate-limited the pull request."
    PullRequestError.NetworkFailure -> "The pull request could not reach GitHub."
    is PullRequestError.ServerError -> "GitHub failed to create the pull request."
    is PullRequestError.MalformedResponse -> "GitHub sent an unreadable pull-request response."
    is PullRequestError.Unknown -> error.message
}
