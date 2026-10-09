package com.agentx.app.tools.github

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

/** One repository as the agent sees it: enough to choose one. No URLs, no credentials. */
data class GitHubRepoSummary(
    val fullName: String,
    val isPrivate: Boolean,
    val defaultBranch: String,
)

enum class GitHubCatalogFailure {
    NOT_CONNECTED,
    AUTH_EXPIRED,
    RATE_LIMITED,
    NETWORK,
    NOT_FOUND,
    INVALID,
    OTHER,
}

sealed interface GitHubCatalogResult {
    data class Success(
        val repositories: List<GitHubRepoSummary>,
        /** True when the account has more repositories than the listing cap. */
        val truncated: Boolean = false,
    ) : GitHubCatalogResult

    data class Failure(
        val reason: GitHubCatalogFailure,
        val message: String,
    ) : GitHubCatalogResult
}

sealed interface GitHubCloneResult {
    data class Success(
        val fullName: String,
        val defaultBranch: String,
        /** True when an earlier clone was opened instead of cloning again. */
        val alreadyCloned: Boolean,
        val projectName: String,
        val workspaceId: String = "",
    ) : GitHubCloneResult

    data class Failure(
        val reason: GitHubCatalogFailure,
        val message: String,
    ) : GitHubCloneResult
}

/**
 * Port the app implements on top of the Connection Manager and the workspace
 * manager, so the tool system never depends on the integrations module and never
 * sees a token.
 */
interface GitHubRepositoryCatalog {
    suspend fun listAll(): GitHubCatalogResult

    /** Clones [fullName] (or reuses an earlier clone) and makes it the active project. */
    suspend fun cloneAndOpen(fullName: String, branch: String?): GitHubCloneResult
}

/** Fails closed until the app binds the real catalog. */
object UnavailableGitHubRepositoryCatalog : GitHubRepositoryCatalog {
    private const val MESSAGE = "No connected GitHub account is available. Connect GitHub in Connections first."

    override suspend fun listAll(): GitHubCatalogResult =
        GitHubCatalogResult.Failure(GitHubCatalogFailure.NOT_CONNECTED, MESSAGE)

    override suspend fun cloneAndOpen(fullName: String, branch: String?): GitHubCloneResult =
        GitHubCloneResult.Failure(GitHubCatalogFailure.NOT_CONNECTED, MESSAGE)
}

class DelegatingGitHubRepositoryCatalog(
    @Volatile private var delegate: GitHubRepositoryCatalog = UnavailableGitHubRepositoryCatalog,
) : GitHubRepositoryCatalog {

    fun bind(catalog: GitHubRepositoryCatalog) {
        delegate = catalog
    }

    override suspend fun listAll(): GitHubCatalogResult = delegate.listAll()

    override suspend fun cloneAndOpen(fullName: String, branch: String?): GitHubCloneResult =
        delegate.cloneAndOpen(fullName, branch)
}

internal fun GitHubCatalogFailure.toToolErrorCode(): ToolErrorCode = when (this) {
    GitHubCatalogFailure.NOT_CONNECTED,
    GitHubCatalogFailure.AUTH_EXPIRED,
    -> ToolErrorCode.PERMISSION_DENIED

    GitHubCatalogFailure.NOT_FOUND,
    GitHubCatalogFailure.INVALID,
    -> ToolErrorCode.INVALID_ARGUMENTS

    GitHubCatalogFailure.RATE_LIMITED,
    GitHubCatalogFailure.NETWORK,
    GitHubCatalogFailure.OTHER,
    -> ToolErrorCode.EXECUTION_FAILED
}

private fun invalidArguments(toolName: String, message: String): ToolExecutionError =
    ToolExecutionError(
        code = ToolErrorCode.INVALID_ARGUMENTS,
        message = message,
        toolName = toolName,
    )

private fun githubFailure(toolName: String, reason: GitHubCatalogFailure, message: String): ToolExecutionError =
    ToolExecutionError(
        code = reason.toToolErrorCode(),
        message = message,
        toolName = toolName,
        details = mapOf("githubCode" to Json.of(reason.name)),
    )

/**
 * Lists the repositories the connected GitHub account can access.
 *
 * Read-only. Results are compact (one entry per repository) and paged with
 * `offset`, so a large account never floods a small model context.
 */
class GitHubListReposTool(
    private val catalog: GitHubRepositoryCatalog,
) : Tool {

    override val definition = ToolDefinition(
        name = NAME,
        title = "List GitHub repositories",
        description = "Lists the GitHub repositories the connected account can access (owned, " +
            "collaborator and organization). Filter by a name fragment or visibility, and page " +
            "with offset. Use it to find the right repository before cloning it.",
        inputSchema = ToolInputSchema(
            parameters = listOf(
                ToolParameter(
                    name = ARG_QUERY,
                    type = ToolParameterType.STRING,
                    description = "Case-insensitive fragment of the 'owner/name' to match.",
                    required = false,
                ),
                ToolParameter(
                    name = ARG_VISIBILITY,
                    type = ToolParameterType.STRING,
                    description = "'all' (default), 'public' or 'private'.",
                    required = false,
                ),
                ToolParameter(
                    name = ARG_LIMIT,
                    type = ToolParameterType.NUMBER,
                    description = "Maximum repositories to return (default $DEFAULT_LIMIT, max $MAX_LIMIT).",
                    required = false,
                ),
                ToolParameter(
                    name = ARG_OFFSET,
                    type = ToolParameterType.NUMBER,
                    description = "How many matching repositories to skip (for the next page).",
                    required = false,
                ),
            ),
        ),
        output = ToolOutputSpec(
            description = "Repositories as owner/name with visibility and default branch, plus total and hasMore.",
        ),
        permission = ToolPermissionDecision.ALLOW,
        // The access token is never on this tool: the Connection Manager lends it to
        // the GitHub service inside the call, so the tool does not declare
        // CREDENTIALS. It also does not declare NETWORK, because the transport
        // belongs to the connection-scoped service, not to the tool — the same way
        // `ci_verification` is READ_ONLY while it reads GitHub Actions. Those two
        // capabilities exceeded the WORKSPACE_WRITE ceiling of the MAIN role that is
        // granted this tool, which silently kept it out of the agent's tool set.
        // The declared `connectionRequirement` below is what gates the call.
        capabilities = setOf(ToolCapability.READ_ONLY),
        category = ToolCategory.GIT,
        requiredPermissions = setOf(ToolPermissionLevel.READ_ONLY),
        connectionRequirement = ToolConnectionRequirement(
            type = ToolConnectionType.GITHUB,
            capability = ToolConnectionCapability.REPOSITORY_READ,
        ),
        metadata = mapOf("sideEffects" to "none", "provider" to "github"),
    )

    override suspend fun execute(input: ToolInput, context: ToolExecutionContext): ToolOutput {
        val query = input.string(ARG_QUERY)?.trim()?.takeIf { it.isNotEmpty() }
        val visibility = input.string(ARG_VISIBILITY)?.trim()?.lowercase().orEmpty()
        if (visibility.isNotEmpty() && visibility !in VISIBILITIES) {
            throw invalidArguments(NAME, "'$ARG_VISIBILITY' must be 'all', 'public' or 'private' (got '$visibility')")
        }
        val limit = (input.number(ARG_LIMIT)?.toInt() ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val offset = (input.number(ARG_OFFSET)?.toInt() ?: 0).coerceAtLeast(0)

        val listing = when (val result = catalog.listAll()) {
            is GitHubCatalogResult.Success -> result
            is GitHubCatalogResult.Failure -> throw githubFailure(NAME, result.reason, result.message)
        }

        val matched = listing.repositories
            .filter { repo ->
                when (visibility) {
                    "public" -> !repo.isPrivate
                    "private" -> repo.isPrivate
                    else -> true
                }
            }
            .filter { repo -> query == null || repo.fullName.contains(query, ignoreCase = true) }
            .sortedBy { it.fullName.lowercase() }

        val page = matched.drop(offset).take(limit)
        val nextOffset = offset + page.size
        val hasMore = nextOffset < matched.size

        val lines = page.joinToString("\n") { repo ->
            "${repo.fullName} · ${if (repo.isPrivate) "private" else "public"} · ${repo.defaultBranch}"
        }
        return ToolOutput(
            content = mapOf(
                "total" to Json.of(matched.size),
                "returned" to Json.of(page.size),
                "hasMore" to Json.of(hasMore),
                "nextOffset" to Json.of(nextOffset),
                "truncated" to Json.of(listing.truncated),
                "repositories" to Json.array(
                    page.map { repo ->
                        Json.obj(
                            "name" to Json.of(repo.fullName),
                            "visibility" to Json.of(if (repo.isPrivate) "private" else "public"),
                            "defaultBranch" to Json.of(repo.defaultBranch),
                        )
                    },
                ),
            ),
            displayText = if (page.isEmpty()) {
                "No repositories matched."
            } else {
                buildString {
                    append("Repositories ").append(page.size).append(" of ").append(matched.size).append('\n')
                    append(lines)
                    if (hasMore) append("\n… more: offset=").append(nextOffset)
                }
            },
        )
    }

    companion object {
        const val NAME: String = "github_list_repos"
        const val ARG_QUERY: String = "query"
        const val ARG_VISIBILITY: String = "visibility"
        const val ARG_LIMIT: String = "limit"
        const val ARG_OFFSET: String = "offset"
        const val DEFAULT_LIMIT: Int = 30
        const val MAX_LIMIT: Int = 100
        private val VISIBILITIES = setOf("all", "public", "private")
    }
}

/**
 * Clones a repository into AgentX's projects folder and makes it the active
 * project, so the file, edit and git tools work on it.
 *
 * Approval-gated: it writes to storage and switches the project the user has
 * open. A repository that was cloned before is reopened, never cloned twice.
 */
class GitHubCloneRepoTool(
    private val catalog: GitHubRepositoryCatalog,
) : Tool {

    override val definition = ToolDefinition(
        name = NAME,
        title = "Clone GitHub repository",
        description = "Clones a GitHub repository the connected account can access into AgentX's " +
            "projects folder and makes it the active project, so file, edit and git tools work on " +
            "it. An existing clone is opened instead of cloning again. Find the exact name with " +
            "github_list_repos first. This switches the active project, so it needs approval.",
        inputSchema = ToolInputSchema(
            parameters = listOf(
                ToolParameter(
                    name = ARG_REPO,
                    type = ToolParameterType.STRING,
                    description = "The repository as 'owner/name'.",
                    required = true,
                ),
                ToolParameter(
                    name = ARG_BRANCH,
                    type = ToolParameterType.STRING,
                    description = "Branch to check out. Defaults to the repository's default branch.",
                    required = false,
                ),
            ),
        ),
        output = ToolOutputSpec(
            description = "The repository, its default branch, whether an earlier clone was reused, and the project name.",
        ),
        permission = ToolPermissionDecision.ASK,
        // Same as `github_list_repos`: the credential and the transport stay inside
        // the connection-scoped services, so this tool is MUTATING only. Declaring
        // CREDENTIALS/NETWORK here exceeded MAIN's WORKSPACE_WRITE ceiling and meant
        // the Main Agent was never offered its own GitHub tools.
        capabilities = setOf(ToolCapability.MUTATING),
        category = ToolCategory.GIT,
        requiredPermissions = setOf(ToolPermissionLevel.WORKSPACE_WRITE),
        connectionRequirement = ToolConnectionRequirement(
            type = ToolConnectionType.GITHUB,
            capability = ToolConnectionCapability.REPOSITORY_READ,
        ),
        metadata = mapOf(
            "sideEffects" to "clones into the projects folder and switches the active project",
            "provider" to "github",
        ),
    )

    override suspend fun execute(input: ToolInput, context: ToolExecutionContext): ToolOutput {
        val repo = input.string(ARG_REPO)?.trim().orEmpty()
        if (!REPO_PATTERN.matches(repo)) {
            throw invalidArguments(NAME, "'$ARG_REPO' must look like 'owner/name'")
        }
        val branch = input.string(ARG_BRANCH)?.trim()?.takeIf { it.isNotEmpty() }
        if (branch != null && (!BRANCH_PATTERN.matches(branch) || ".." in branch || branch.length > MAX_BRANCH_LENGTH)) {
            throw invalidArguments(NAME, "'$ARG_BRANCH' is not a valid branch name")
        }

        val cloned = when (val result = catalog.cloneAndOpen(repo, branch)) {
            is GitHubCloneResult.Success -> result
            is GitHubCloneResult.Failure -> throw githubFailure(NAME, result.reason, result.message)
        }

        return ToolOutput(
            content = mapOf(
                "repository" to Json.of(cloned.fullName),
                "defaultBranch" to Json.of(cloned.defaultBranch),
                "alreadyCloned" to Json.of(cloned.alreadyCloned),
                "projectName" to Json.of(cloned.projectName),
                "note" to Json.of(
                    "The repository is now the active project. If a file or git tool reports the " +
                        "workspace as unavailable, ask the user to send a new message so the next " +
                        "turn uses the new project.",
                ),
            ),
            displayText = if (cloned.alreadyCloned) {
                "Opened the existing clone of ${cloned.fullName} as the active project."
            } else {
                "Cloned ${cloned.fullName} and opened it as the active project."
            },
        )
    }

    companion object {
        const val NAME: String = "github_clone_repo"
        const val ARG_REPO: String = "repo"
        const val ARG_BRANCH: String = "branch"
        private const val MAX_BRANCH_LENGTH: Int = 200
        private val REPO_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*/[A-Za-z0-9._-]+$")
        private val BRANCH_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._/-]*$")
    }
}

/** Service-container key for the bindable catalog, so the app attaches the real one after boot. */
object GitHubToolServiceKeys {
    const val CATALOG: String = "forge.tools.github.catalog"
}

/** Factory for the GitHub tools, registered with the other builtin families. */
object GitHubTools {
    fun create(catalog: GitHubRepositoryCatalog): List<Tool> = listOf(
        GitHubListReposTool(catalog),
        GitHubCloneRepoTool(catalog),
    )
}
