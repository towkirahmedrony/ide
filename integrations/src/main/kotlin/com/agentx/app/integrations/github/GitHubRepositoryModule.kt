package com.agentx.app.integrations.github

import com.agentx.app.integrations.connection.ConnectionCredentialGateway

/**
 * Safe, token-free logging for GitHub repository work.
 *
 * Every parameter is either a connection id, repository metadata, or a typed
 * error, so a logger can be pointed at a durable sink without a credential ever
 * reaching it — the token exists only inside the clone transport.
 */
interface GitHubRepositoryLogger {
    fun logCloneStarted(connectionId: String, repository: GitHubRepository)
    fun logCloneFinished(connectionId: String, workspacePath: String)
    fun logCloneFailed(connectionId: String, repository: GitHubRepository, error: GitHubRepositoryError)
}

/** The production logger: records nothing, which is the safest default. */
internal object QuietGitHubRepositoryLogger : GitHubRepositoryLogger {
    override fun logCloneStarted(connectionId: String, repository: GitHubRepository) = Unit
    override fun logCloneFinished(connectionId: String, workspacePath: String) = Unit
    override fun logCloneFailed(
        connectionId: String,
        repository: GitHubRepository,
        error: GitHubRepositoryError,
    ) = Unit
}

/** Service-container keys Phase 2 publishes when the GitHub provider is registered. */
object GitHubRepositoryServiceKeys {
    const val REPOSITORY_SERVICE: String = "forge.integrations.github.repositoryService"
    const val CLONE_SERVICE: String = "forge.integrations.github.cloneService"
    const val DESTINATION_VALIDATOR: String = "forge.integrations.github.cloneDestinationValidator"
}

/**
 * Assembles the GitHub repository services from the Phase-1 credential gateway,
 * so the repository client and the clone transport share one source of
 * credentials rather than each holding their own.
 */
object GitHubRepositoryServices {

    fun create(
        credentialGateway: ConnectionCredentialGateway,
        restClient: GitHubRestClient = UrlConnectionGitHubRestClient(),
    ): Services = Services(
        repositoryService = GitHubRepositoryServiceImpl(
            credentialGateway = credentialGateway,
            restClient = restClient,
        ),
        cloneService = JGitGitHubRepositoryCloneService(credentialGateway = credentialGateway),
        destinationValidator = CloneDestinationValidator(),
    )

    data class Services(
        val repositoryService: GitHubRepositoryService,
        val cloneService: GitHubRepositoryCloneService,
        val destinationValidator: CloneDestinationValidator,
    )
}
