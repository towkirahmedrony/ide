package com.agentx.app.foundation

import com.agentx.app.core.ForgeResult
import com.agentx.app.core.errorOrNull
import com.agentx.app.core.valueOrNull
import com.agentx.app.integrations.connection.ConnectionCapabilities
import com.agentx.app.integrations.connection.ConnectionId
import com.agentx.app.integrations.connection.ConnectionManager
import com.agentx.app.integrations.connection.ConnectionType
import com.agentx.app.integrations.github.CloneDestinationValidator
import com.agentx.app.integrations.github.GitHubRepository
import com.agentx.app.integrations.github.GitHubRepositoryCloneService
import com.agentx.app.integrations.github.GitHubRepositoryError
import com.agentx.app.integrations.github.GitHubRepositoryService
import com.agentx.app.integrations.github.GitHubRepositoryVisibility
import com.agentx.app.tools.ToolRegistry
import com.agentx.app.tools.github.DelegatingGitHubRepositoryCatalog
import com.agentx.app.tools.github.GitHubCatalogFailure
import com.agentx.app.tools.github.GitHubCatalogResult
import com.agentx.app.tools.github.GitHubCloneRepoTool
import com.agentx.app.tools.github.GitHubCloneResult
import com.agentx.app.tools.github.GitHubListReposTool
import com.agentx.app.tools.github.GitHubRepoSummary
import com.agentx.app.tools.github.GitHubRepositoryCatalog
import com.agentx.app.workspace.WorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The agent's view of the connected GitHub account: which repositories exist,
 * and cloning one into the active project.
 *
 * The connection is authorized through the Connection Manager on every call, and
 * the token is only ever lent inside the GitHub services; nothing here can see
 * it. A short cache keeps paging through a large account from re-fetching.
 */
class ConnectionGitHubRepositoryCatalog(
    private val manager: ConnectionManager,
    private val repositories: GitHubRepositoryService,
    private val cloneService: GitHubRepositoryCloneService,
    private val workspaces: WorkspaceManager,
    private val managedRoot: File,
    private val validator: CloneDestinationValidator = CloneDestinationValidator(),
    private val clock: () -> Long = System::currentTimeMillis,
) : GitHubRepositoryCatalog {

    private val mutex = Mutex()
    private var cached: Snapshot? = null

    override suspend fun listAll(): GitHubCatalogResult = when (val loaded = load()) {
        is Loaded.Failed -> loaded.failure
        is Loaded.Ok -> GitHubCatalogResult.Success(
            repositories = loaded.snapshot.repos.map { it.toSummary() },
            truncated = loaded.snapshot.truncated,
        )
    }

    override suspend fun cloneAndOpen(fullName: String, branch: String?): GitHubCloneResult {
        val loaded = when (val result = load()) {
            is Loaded.Failed -> return GitHubCloneResult.Failure(result.failure.reason, result.failure.message)
            is Loaded.Ok -> result
        }
        val repo = loaded.snapshot.repos.firstOrNull { it.fullName.equals(fullName, ignoreCase = true) }
            ?: return GitHubCloneResult.Failure(
                GitHubCatalogFailure.NOT_FOUND,
                "'$fullName' is not among the repositories this account can access. " +
                    "Use github_list_repos to find the exact name.",
            )
        val directoryName = validator.directoryName(repo.owner, repo.name)
            ?: return GitHubCloneResult.Failure(
                GitHubCatalogFailure.INVALID,
                "'${repo.fullName}' cannot be used as a project folder name.",
            )
        return withContext(Dispatchers.IO) { cloneOrReuse(loaded.connectionId, repo, directoryName, branch) }
    }

    private suspend fun cloneOrReuse(
        connectionId: ConnectionId,
        repo: GitHubRepository,
        directoryName: String,
        branch: String?,
    ): GitHubCloneResult {
        val root = managedRoot.canonicalFile
        val target = File(root, directoryName)
        val existed = target.exists()

        val path: String = if (existed) {
            val insideRoot = target.canonicalFile.parentFile == root
            if (!insideRoot || !File(target, ".git").exists()) {
                return GitHubCloneResult.Failure(
                    GitHubCatalogFailure.INVALID,
                    "A folder named \"$directoryName\" already exists in the AgentX projects folder " +
                        "but is not a Git clone, so it was left untouched.",
                )
            }
            target.path
        } else {
            when (val cloned = cloneService.clone(connectionId, repo, managedRoot, branch, onProgress = {})) {
                is ForgeResult.Success -> cloned.value
                is ForgeResult.Failure -> {
                    val failure = cloned.error.toFailure()
                    return GitHubCloneResult.Failure(failure.reason, failure.message)
                }
            }
        }

        return when (val opened = workspaces.open(path)) {
            is ForgeResult.Success -> GitHubCloneResult.Success(
                fullName = repo.fullName,
                defaultBranch = repo.defaultBranch,
                alreadyCloned = existed,
                projectName = directoryName,
            )

            is ForgeResult.Failure -> GitHubCloneResult.Failure(
                GitHubCatalogFailure.OTHER,
                "The repository was ${if (existed) "found" else "cloned"} but could not be opened " +
                    "as the active project: ${opened.error.message}",
            )
        }
    }

    private suspend fun load(): Loaded {
        val authorized = manager.authorize(
            type = ConnectionType.GITHUB,
            capability = ConnectionCapabilities.REPOSITORY_READ,
        )
        val connection = authorized.valueOrNull()
            ?: return Loaded.Failed(
                GitHubCatalogResult.Failure(
                    GitHubCatalogFailure.NOT_CONNECTED,
                    authorized.errorOrNull()?.message ?: "No connected GitHub account is available.",
                ),
            )

        return mutex.withLock {
            val now = clock()
            val hit = cached
            if (hit != null && hit.connectionId == connection.id.value && now - hit.atMillis < CACHE_MILLIS) {
                return@withLock Loaded.Ok(connection.id, hit)
            }
            when (val fetched = fetchAll(connection.id, now)) {
                is Fetched.Ok -> {
                    cached = fetched.snapshot
                    Loaded.Ok(connection.id, fetched.snapshot)
                }

                is Fetched.Failed -> Loaded.Failed(fetched.failure)
            }
        }
    }

    private suspend fun fetchAll(id: ConnectionId, now: Long): Fetched {
        val collected = LinkedHashMap<String, GitHubRepository>()
        var truncated = false
        // GitHub's list endpoint takes one visibility at a time, so both are read and merged.
        for (visibility in listOf(GitHubRepositoryVisibility.PUBLIC, GitHubRepositoryVisibility.PRIVATE)) {
            var page = 1
            while (true) {
                when (val result = repositories.list(id, visibility, page, GitHubRepositoryService.MAX_PER_PAGE)) {
                    is ForgeResult.Success -> {
                        result.value.repositories.forEach { repo -> collected[repo.fullName] = repo }
                        val next = result.value.nextPageNumber
                        if (!result.value.hasNextPage || next == null) break
                        if (page >= MAX_PAGES_PER_VISIBILITY) {
                            truncated = true
                            break
                        }
                        page = next
                    }

                    is ForgeResult.Failure -> return Fetched.Failed(result.error.toFailure())
                }
            }
        }
        return Fetched.Ok(Snapshot(id.value, now, collected.values.toList(), truncated))
    }

    private data class Snapshot(
        val connectionId: String,
        val atMillis: Long,
        val repos: List<GitHubRepository>,
        val truncated: Boolean,
    )

    private sealed interface Loaded {
        data class Ok(val connectionId: ConnectionId, val snapshot: Snapshot) : Loaded
        data class Failed(val failure: GitHubCatalogResult.Failure) : Loaded
    }

    private sealed interface Fetched {
        data class Ok(val snapshot: Snapshot) : Fetched
        data class Failed(val failure: GitHubCatalogResult.Failure) : Fetched
    }

    private companion object {
        const val CACHE_MILLIS: Long = 60_000L
        const val MAX_PAGES_PER_VISIBILITY: Int = 10
    }
}

private fun GitHubRepository.toSummary(): GitHubRepoSummary = GitHubRepoSummary(
    fullName = fullName,
    isPrivate = visibility == GitHubRepositoryVisibility.PRIVATE,
    defaultBranch = defaultBranch,
)

private fun GitHubRepositoryError.toFailure(): GitHubCatalogResult.Failure = when (this) {
    is GitHubRepositoryError.Unauthenticated -> GitHubCatalogResult.Failure(
        GitHubCatalogFailure.AUTH_EXPIRED,
        "The GitHub credential expired. Reconnect GitHub in Connections.",
    )

    is GitHubRepositoryError.NoCredential -> GitHubCatalogResult.Failure(
        GitHubCatalogFailure.NOT_CONNECTED,
        "No GitHub access is available. Connect GitHub in Connections.",
    )

    is GitHubRepositoryError.RateLimited -> GitHubCatalogResult.Failure(
        GitHubCatalogFailure.RATE_LIMITED,
        "GitHub is rate limiting this account. Try again later.",
    )

    is GitHubRepositoryError.NetworkFailure -> GitHubCatalogResult.Failure(
        GitHubCatalogFailure.NETWORK,
        "GitHub could not be reached.",
    )

    is GitHubRepositoryError.NotFound -> GitHubCatalogResult.Failure(
        GitHubCatalogFailure.NOT_FOUND,
        "GitHub could not find that repository.",
    )

    is GitHubRepositoryError.InvalidDestination -> GitHubCatalogResult.Failure(
        GitHubCatalogFailure.INVALID,
        detail,
    )

    is GitHubRepositoryError.PathTraversal -> GitHubCatalogResult.Failure(
        GitHubCatalogFailure.INVALID,
        "That clone destination is not allowed.",
    )

    is GitHubRepositoryError.Unknown -> GitHubCatalogResult.Failure(
        GitHubCatalogFailure.OTHER,
        message,
    )

    else -> GitHubCatalogResult.Failure(
        GitHubCatalogFailure.OTHER,
        "GitHub could not complete the request.",
    )
}

/**
 * Registers the GitHub tools once the connection and workspace layers exist.
 * The tools stay fail-closed until a GitHub connection is authorized, and a
 * build without the GitHub services installs nothing.
 */
fun installGitHubAgentTools(
    registry: ToolRegistry?,
    connectionManager: ConnectionManager?,
    repositoryService: Any?,
    cloneService: Any?,
    workspaceManager: WorkspaceManager,
    managedRoot: File,
) {
    val repositories = repositoryService as? GitHubRepositoryService ?: return
    val cloner = cloneService as? GitHubRepositoryCloneService ?: return
    if (registry == null || connectionManager == null) return
    val catalog = DelegatingGitHubRepositoryCatalog()
    catalog.bind(
        ConnectionGitHubRepositoryCatalog(
            manager = connectionManager,
            repositories = repositories,
            cloneService = cloner,
            workspaces = workspaceManager,
            managedRoot = managedRoot,
        ),
    )
    runCatching { registry.register(GitHubListReposTool(catalog)) }
    runCatching { registry.register(GitHubCloneRepoTool(catalog)) }
}
