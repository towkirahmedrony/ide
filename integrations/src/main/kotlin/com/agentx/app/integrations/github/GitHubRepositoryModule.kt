package com.agentx.app.integrations.github

import com.agentx.app.integrations.connection.ConnectionCredentialGateway
import com.agentx.app.integrations.connection.ConnectionManager
import com.agentx.app.integrations.connection.ConnectionId
import com.agentx.app.integrations.github.GitHubRepository
import com.agentx.app.integrations.github.GitHubRepositoryError
import com.agentx.app.integrations.github.GitHubRepositoryService
import com.agentx.app.integrations.github.GitHubRepositoryCloneService
import com.agentx.app.integrations.github.CloneDestinationValidator
import com.agentx.app.integrations.github.JGitGitHubRepositoryCloneService
import com.agentx.app.integrations.github.UrlConnectionGitHubRestClient
import com.agentx.app.integrations.github.GitHubRepositoryServiceImpl
import com.agentx.app.integrations.github.GitHubRepositoryCloneError
import com.agentx.app.integrations.github.GitHubRepositoryPage
/**
 * Factory for creating GitHub repository services with proper dependencies.
 *
 * This is the main entry point for creating configured instances of the
 * repository service and clone service.
 */
object GitHubRepositoryServices {

    /**
     * Create a fully-configured GitHub repository service.
     *
     * @param connectionManager The existing connection manager from Phase 1.
     * @param managedProjectsRoot The managed workspace root for clones.
     * @return A triple of (repositoryService, cloneService, validator).
     */
    fun create(
        connectionManager: ConnectionManager,
        managedProjectsRoot: File,
    ): Services {
        require(managedProjectsRoot.isDirectory || managedProjectsRoot.mkdirs()) {
            "Managed workspace root must be a directory: ${managedProjectsRoot.absolutePath}"
        }

        // Get the credential gateway from the connection manager
        // The connection manager implements ConnectionCredentialGateway
        val credentialGateway = connectionManager as ConnectionCredentialGateway

        // Create the HTTP client for GitHub REST API
        val restClient = UrlConnectionGitHubRestClient()

        // Create repository service
        val repositoryService = GitHubRepositoryServiceImpl(
            credentialGateway = credentialGateway,
            restClient = restClient,
        )

        // Create clone destination validator
        val validator = CloneDestinationValidator()

        // Create clone service
        val cloneService = JGitGitHubRepositoryCloneService(
            credentialGateway = credentialGateway,
            validator = validator,
            logger = object : GitHubRepositoryLogger {
                override fun logError(connectionId: String, error: GitHubRepositoryError, repositoriesListed: Int) {
                    // No-op in production; could delegate to actual logger
                }

                override fun logNetworkFailure(connectionId: String) {
                    // No-op
                }

                override fun logCloneStart(connectionId: String, owner: String, repo: String) {
                    // No-op
                }

                override fun logCloneProgress(connectionId: String, progress: String) {
                    // No-op
                }

                override fun logCloneComplete(connectionId: String, workspacePath: String) {
                    // No-op
                }

                override fun logCloneError(connectionId: String, error: GitHubRepositoryError) {
                    // No-op
                }
            },
        )

        return Services(
            repositoryService = repositoryService,
            cloneService = cloneService,
            validator = validator,
        )
    }

    /**
     * All GitHub repository services bound together.
     */
    data class Services(
        val repositoryService: GitHubRepositoryService,
        val cloneService: GitHubRepositoryCloneService,
        val validator: CloneDestinationValidator,
    )
}

/**
 * Convenience extension to clone a repository and get the workspace path.
 *
 * This is the main operation the UI will call.
 */
suspend fun GitHubRepositoryCloneService.cloneToWorkspace(
    connectionId: ConnectionId,
    repository: GitHubRepository,
    destinationDir: File,
    branch: String? = null,
    onProgress: suspend (String) -> Unit = {},
): ForgeResult<String, GitHubRepositoryError> {
    return clone(connectionId, repository, destinationDir, branch, onProgress)
}

/**
 * Default logger that does nothing (can be replaced with real logging in production).
 */
private object QuietGitHubRepositoryLogger : GitHubRepositoryLogger {
    override fun logError(connectionId: String, error: GitHubRepositoryError, repositoriesListed: Int) {}
    override fun logNetworkFailure(connectionId: String) {}
    override fun logCloneStart(connectionId: String, owner: String, repo: String) {}
    override fun logCloneProgress(connectionId: String, progress: String) {}
    override fun logCloneComplete(connectionId: String, workspacePath: String) {}
    override fun logCloneError(connectionId: String, error: GitHubRepositoryError) {}
}
