package com.agentx.app.integrations.providers

import com.agentx.app.integrations.connection.Connection
import com.agentx.app.integrations.connection.ConnectionAuthMethod
import com.agentx.app.integrations.connection.ConnectionCapabilities
import com.agentx.app.integrations.connection.ConnectionType
import com.agentx.app.integrations.connection.ProviderCapabilityInfo
import com.agentx.app.integrations.connection.ProviderDescriptor
import com.agentx.app.integrations.connection.ProviderToolCatalog
import com.agentx.app.integrations.connection.ProviderToolCategory
import com.agentx.app.integrations.connection.ProviderToolSpec
import com.agentx.app.integrations.oauth.GitHubOAuthProvider
import com.agentx.app.integrations.oauth.OAuthFlowRunner
import com.agentx.app.integrations.oauth.OAuthProvider

/**
 * GitHub, described for the Connections UI and the Tool System.
 *
 * Authorization itself lives in [GitHubOAuthProvider]; this type adds the product
 * surface: card copy, the capability list with the permission each one maps to,
 * the account handle shown after connecting, and the tool catalog the Tool System
 * installs while the connection is available.
 *
 * Write capabilities are never granted implicitly: they only follow the `repo`
 * scope, and every mutating tool stays subject to the Tool System's own
 * permission policy.
 */
class GitHubConnectionProvider(
    oauthProvider: OAuthProvider,
    flow: OAuthFlowRunner,
    clock: () -> Long = System::currentTimeMillis,
) : OAuthBackedConnectionProvider(oauthProvider, flow, clock) {

    override val descriptor: ProviderDescriptor = ProviderDescriptor(
        type = ConnectionType.GITHUB,
        displayName = "GitHub",
        description = "Code hosting, repositories, branches, pull requests and issues.",
        details = "Connect your GitHub account to let the AI agent work with your repositories. " +
            "The agent only gets the access you approve on GitHub's own authorization page.",
        capabilities = listOf(
            ProviderCapabilityInfo(
                capability = ConnectionCapabilities.REPOSITORY_READ,
                label = "Read repositories",
                description = "Search repositories and read files, branches and commit history.",
                scopes = setOf(GitHubOAuthProvider.SCOPE_REPO, GitHubOAuthProvider.SCOPE_PUBLIC_REPO),
            ),
            ProviderCapabilityInfo(
                capability = ConnectionCapabilities.PULL_REQUEST,
                label = "Pull requests",
                description = "Read and open pull requests.",
                scopes = setOf(GitHubOAuthProvider.SCOPE_REPO, GitHubOAuthProvider.SCOPE_PUBLIC_REPO),
            ),
            ProviderCapabilityInfo(
                capability = ConnectionCapabilities.ISSUES,
                label = "Issues",
                description = "Read and manage issues.",
                scopes = setOf(GitHubOAuthProvider.SCOPE_REPO, GitHubOAuthProvider.SCOPE_PUBLIC_REPO),
            ),
            ProviderCapabilityInfo(
                capability = ConnectionCapabilities.REPOSITORY_WRITE,
                label = "Write repositories",
                description = "Create commits and branches. Requires the full repository scope.",
                scopes = setOf(GitHubOAuthProvider.SCOPE_REPO),
                mutating = true,
            ),
        ),
        authMethods = listOf(ConnectionAuthMethod.OAUTH, ConnectionAuthMethod.ACCESS_TOKEN),
        authorizationNote = "You sign in on github.com; the app never sees your password.",
    )

    /** The account handle shown as "@username" after connecting. */
    override fun handleFor(connection: Connection): String = connection.displayName

    override fun toolCatalog(): ProviderToolCatalog = ProviderToolCatalog(
        provider = ConnectionType.GITHUB,
        tools = listOf(
            ProviderToolSpec(
                toolName = "github.search_repositories",
                title = "Search repositories",
                description = "Searches repositories the connected account can access.",
                requiredCapability = ConnectionCapabilities.REPOSITORY_READ,
                category = ProviderToolCategory.REPOSITORY_READ,
                mutating = false,
                implemented = true,
            ),
            ProviderToolSpec(
                toolName = "github.get_repository",
                title = "Repository information",
                description = "Reads repository metadata: default branch, visibility, description.",
                requiredCapability = ConnectionCapabilities.REPOSITORY_READ,
                category = ProviderToolCategory.REPOSITORY_READ,
                mutating = false,
                implemented = true,
            ),
            ProviderToolSpec(
                toolName = "github.get_file",
                title = "File content",
                description = "Reads a file's content at a given ref.",
                requiredCapability = ConnectionCapabilities.REPOSITORY_READ,
                category = ProviderToolCategory.REPOSITORY_READ,
                mutating = false,
                implemented = true,
            ),
            ProviderToolSpec(
                toolName = "github.list_branches",
                title = "Branches",
                description = "Lists branches of a repository.",
                requiredCapability = ConnectionCapabilities.REPOSITORY_READ,
                category = ProviderToolCategory.REPOSITORY_READ,
                mutating = false,
                implemented = true,
            ),
            ProviderToolSpec(
                toolName = "github.create_commit",
                title = "Create commits",
                description = "Commits file changes to a branch. Declared, not enabled in this build.",
                requiredCapability = ConnectionCapabilities.REPOSITORY_WRITE,
                category = ProviderToolCategory.REPOSITORY_WRITE,
                mutating = true,
                implemented = false,
            ),
            ProviderToolSpec(
                toolName = "github.create_pull_request",
                title = "Open pull requests",
                description = "Opens a pull request. Declared, not enabled in this build.",
                requiredCapability = ConnectionCapabilities.PULL_REQUEST,
                category = ProviderToolCategory.PULL_REQUEST,
                mutating = true,
                implemented = false,
            ),
            ProviderToolSpec(
                toolName = "github.create_issue",
                title = "Create issues",
                description = "Opens an issue. Declared, not enabled in this build.",
                requiredCapability = ConnectionCapabilities.ISSUES,
                category = ProviderToolCategory.ISSUES,
                mutating = true,
                implemented = false,
            ),
        ),
    )
}
