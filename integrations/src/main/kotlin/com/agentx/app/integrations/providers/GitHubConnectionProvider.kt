package com.agentx.app.integrations.providers

import com.agentx.app.core.ForgeError
import com.agentx.app.core.ForgeErrorCode
import com.agentx.app.core.ForgeResult
import com.agentx.app.core.errorOrNull
import com.agentx.app.core.failure
import com.agentx.app.core.success
import com.agentx.app.core.valueOrNull
import com.agentx.app.integrations.connection.Connection
import com.agentx.app.integrations.connection.ConnectionAuthMethod
import com.agentx.app.integrations.connection.ConnectionCapabilities
import com.agentx.app.integrations.connection.ConnectionId
import com.agentx.app.integrations.connection.ConnectionType
import com.agentx.app.integrations.connection.DeviceAuthorization
import com.agentx.app.integrations.connection.ProviderCapabilityInfo
import com.agentx.app.integrations.connection.ProviderDescriptor
import com.agentx.app.integrations.connection.ProviderGrant
import com.agentx.app.integrations.connection.ProviderIdentity
import com.agentx.app.integrations.connection.ProviderToolCatalog
import com.agentx.app.integrations.connection.ProviderToolCategory
import com.agentx.app.integrations.connection.ProviderToolSpec
import com.agentx.app.integrations.connection.connectionFailure
import com.agentx.app.integrations.github.GitHubDiagnostics
import com.agentx.app.integrations.oauth.DeviceFlowRunner
import com.agentx.app.integrations.oauth.DeviceFlowState
import com.agentx.app.integrations.oauth.GitHubOAuthProvider
import com.agentx.app.integrations.oauth.OAuthFailureReason
import com.agentx.app.integrations.oauth.OAuthFlowRunner
import com.agentx.app.integrations.oauth.OAuthProvider
import com.agentx.app.integrations.oauth.OAuthTokenCodec

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
    /**
     * OAuth Device Flow support. Optional so a build without it (and the redirect
     * tests) keeps working unchanged; when absent the provider reports that device
     * authorization is unsupported instead of faking it.
     */
    private val deviceFlow: DeviceFlowRunner? = null,
) : OAuthBackedConnectionProvider(oauthProvider, flow, clock) {

    /** GitHub device flow needs no redirect; it needs a Client ID, nothing else. */
    override val supportsDeviceAuthorization: Boolean get() = deviceFlow != null && oauthProvider.client.clientId.isNotBlank()

    override fun hasPendingAuthorization(connectionId: ConnectionId): Boolean =
        super.hasPendingAuthorization(connectionId) || deviceFlow?.pendingFor(connectionId) != null

    override suspend fun cancelAuthorization(connectionId: ConnectionId) {
        super.cancelAuthorization(connectionId)
        deviceFlow?.cancel(connectionId)
    }

    override suspend fun beginDeviceAuthorization(
        connection: Connection,
    ): ForgeResult<DeviceAuthorization, ForgeError> {
        val runner = deviceFlow
        GitHubDiagnostics.auth(
            "device authorization prerequisites",
            mapOf(
                "connectionId" to connection.id.value,
                "deviceFlowAvailable" to (runner != null),
                "clientIdPresent" to oauthProvider.client.clientId.isNotBlank(),
            ),
        )
        if (runner == null) {
            GitHubDiagnostics.failure(
                GitHubDiagnostics.STAGE_AUTH,
                "device authorization unavailable in this build",
                fields = mapOf("connectionId" to connection.id.value),
            )
            return failure(deviceFlowUnsupported(connection))
        }
        if (oauthProvider.client.clientId.isBlank()) {
            GitHubDiagnostics.failure(
                GitHubDiagnostics.STAGE_AUTH,
                "device authorization refused: no client id saved",
                fields = mapOf("connectionId" to connection.id.value),
            )
            return failure(
                connectionFailure(
                    code = ForgeErrorCode.CONNECTION_OAUTH_UNAVAILABLE,
                    message = "GitHub authorization is not configured: save a Client ID first.",
                    details = mapOf("connectionId" to connection.id.value, "type" to connection.type.name),
                ),
            )
        }
        GitHubDiagnostics.auth(
            "starting GitHub device authorization",
            mapOf("connectionId" to connection.id.value),
        )
        return runner.begin(connection, oauthProvider.scopesFor(connection.capabilities))
    }

    override suspend fun completeDeviceAuthorization(
        connection: Connection,
        onState: suspend (DeviceFlowState) -> Unit,
    ): ForgeResult<ProviderGrant, ForgeError> {
        val runner = deviceFlow ?: return failure(deviceFlowUnsupported(connection))

        GitHubDiagnostics.auth(
            "device authorization completion started",
            mapOf("connectionId" to connection.id.value),
        )
        val polled = runner.complete(connection.id, onState)
        val tokens = polled.valueOrNull()
        if (tokens == null) {
            val error = polled.errorOrNull() ?: deviceFlowUnsupported(connection)
            GitHubDiagnostics.failure(
                GitHubDiagnostics.STAGE_AUTH,
                "device authorization completion failed",
                fields = mapOf(
                    "connectionId" to connection.id.value,
                    "errorCode" to error.code.name,
                ),
            )
            return failure(error)
        }
        GitHubDiagnostics.auth(
            "token exchange succeeded: accessTokenPresent=true",
            mapOf("refreshTokenPresent" to tokens.hasRefreshToken),
        )

        // The grant is verified with GitHub's own API before it is stored: a token
        // that cannot be used is never reported as connected.
        val validation = oauthProvider.validate(tokens)
        if (!validation.valid) {
            onState(DeviceFlowState.AUTH_ERROR)
            GitHubDiagnostics.failure(
                GitHubDiagnostics.STAGE_AUTH,
                "GitHub connection FAILED: credential validation failed",
                fields = mapOf("connectionId" to connection.id.value),
            )
            return failure(
                connectionFailure(
                    code = ForgeErrorCode.CONNECTION_OAUTH_FAILED,
                    message = validation.message.ifBlank { "GitHub rejected the new credentials" },
                    details = mapOf(
                        "connectionId" to connection.id.value,
                        "type" to connection.type.name,
                        "reason" to OAuthFailureReason.VALIDATION_FAILED.name,
                    ),
                ),
            )
        }

        val accountLabel = validation.accountLabel ?: tokens.accountLabel ?: handleFor(connection)
        val grantedScopes = oauthProvider.grantedScopes(tokens)
        GitHubDiagnostics.auth(
            "GitHub connection SUCCESS",
            mapOf(
                "connectionId" to connection.id.value,
                "accountLabelPresent" to accountLabel.isNotBlank(),
                "scopes" to grantedScopes.sorted(),
            ),
        )
        return success(
            ProviderGrant(
                connectionId = connection.id,
                payload = OAuthTokenCodec.encode(tokens.copy(accountLabel = accountLabel)),
                scopes = grantedScopes,
                identity = ProviderIdentity(
                    accountLabel = accountLabel,
                    message = validation.message,
                    expiresAtMillis = tokens.expiresAtMillis,
                ),
                capabilities = oauthProvider.capabilitiesFor(grantedScopes).intersect(connection.capabilities),
                expiresAtMillis = tokens.expiresAtMillis,
                refreshable = tokens.hasRefreshToken && oauthProvider.descriptor.supportsRefresh,
            ),
        )
    }

    private fun deviceFlowUnsupported(connection: Connection): ForgeError = connectionFailure(
        code = ForgeErrorCode.CONNECTION_OAUTH_UNAVAILABLE,
        message = "GitHub device authorization is unavailable in this build.",
        details = mapOf("connectionId" to connection.id.value, "type" to connection.type.name),
    )

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

    /**
     * The tools GitHub would contribute. Every entry is `implemented = false`: the
     * connection and capability gating is complete, but the service calls are not
     * written yet, so a tool is never installed that could not run.
     */
    override fun toolCatalog(): ProviderToolCatalog = ProviderToolCatalog(
        provider = ConnectionType.GITHUB,
        tools = listOf(
            ProviderToolSpec(
                toolName = "github.list_repositories",
                title = "List repositories",
                description = "Lists repositories the connected account can access.",
                requiredCapability = ConnectionCapabilities.REPOSITORY_READ,
                category = ProviderToolCategory.REPOSITORY_READ,
                mutating = false,
                implemented = false,
            ),
            ProviderToolSpec(
                toolName = "github.search_repositories",
                title = "Search repositories",
                description = "Searches repositories the connected account can access.",
                requiredCapability = ConnectionCapabilities.REPOSITORY_READ,
                category = ProviderToolCategory.REPOSITORY_READ,
                mutating = false,
                implemented = false,
            ),
            ProviderToolSpec(
                toolName = "github.get_repository",
                title = "Repository information",
                description = "Reads repository metadata: default branch, visibility, description.",
                requiredCapability = ConnectionCapabilities.REPOSITORY_READ,
                category = ProviderToolCategory.REPOSITORY_READ,
                mutating = false,
                implemented = false,
            ),
            ProviderToolSpec(
                toolName = "github.get_file",
                title = "File content",
                description = "Reads a file's content at a given ref.",
                requiredCapability = ConnectionCapabilities.REPOSITORY_READ,
                category = ProviderToolCategory.REPOSITORY_READ,
                mutating = false,
                implemented = false,
            ),
            ProviderToolSpec(
                toolName = "github.search_code",
                title = "Code search",
                description = "Searches code across repositories the connected account can access.",
                requiredCapability = ConnectionCapabilities.REPOSITORY_READ,
                category = ProviderToolCategory.REPOSITORY_READ,
                mutating = false,
                implemented = false,
            ),
            ProviderToolSpec(
                toolName = "github.list_branches",
                title = "Branches",
                description = "Lists branches of a repository.",
                requiredCapability = ConnectionCapabilities.REPOSITORY_READ,
                category = ProviderToolCategory.REPOSITORY_READ,
                mutating = false,
                implemented = false,
            ),
            ProviderToolSpec(
                toolName = "github.list_commits",
                title = "Commits",
                description = "Lists commits on a branch or ref.",
                requiredCapability = ConnectionCapabilities.REPOSITORY_READ,
                category = ProviderToolCategory.REPOSITORY_READ,
                mutating = false,
                implemented = false,
            ),
            ProviderToolSpec(
                toolName = "github.list_pull_requests",
                title = "List pull requests",
                description = "Lists pull requests in a repository.",
                requiredCapability = ConnectionCapabilities.PULL_REQUEST,
                category = ProviderToolCategory.PULL_REQUEST,
                mutating = false,
                implemented = false,
            ),
            ProviderToolSpec(
                toolName = "github.list_issues",
                title = "List issues",
                description = "Lists issues in a repository.",
                requiredCapability = ConnectionCapabilities.ISSUES,
                category = ProviderToolCategory.ISSUES,
                mutating = false,
                implemented = false,
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
