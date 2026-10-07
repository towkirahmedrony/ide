package com.agentx.app.tools

import com.agentx.app.core.foundation.ServiceKeys
import com.agentx.app.core.module.ForgeModule
import com.agentx.app.core.module.ModuleContext
import com.agentx.app.git.DelegatingGitProjectProvider
import com.agentx.app.git.DelegatingGitPushService
import com.agentx.app.git.DelegatingGitService
import com.agentx.app.git.GitProjectProvider
import com.agentx.app.git.GitPushService
import com.agentx.app.git.GitService
import com.agentx.app.tools.pullrequest.DelegatingPullRequestRepositoryProvider
import com.agentx.app.tools.pullrequest.DelegatingPullRequestService
import com.agentx.app.tools.verification.DelegatingCiRepositoryRefProvider
import com.agentx.app.tools.verification.DelegatingCiVerificationService
import com.agentx.app.tools.web.DelegatingHttpGetClient
import com.agentx.app.tools.web.DelegatingWebSearchProvider
import com.agentx.app.tools.web.HttpGetClient
import com.agentx.app.tools.web.WebSearchProvider
import com.agentx.app.tools.planning.TodoWriteTool
import com.agentx.app.workspace.ProcessExecutor

/**
 * Wires the tool infrastructure into the platform. Filesystem tools are
 * registered against a bindable workspace resolver so the Android app can
 * attach the live Workspace Runtime after boot.
 *
 * The command, git and web families are registered here too. Each takes a
 * collaborator that the app may bind after boot (a process executor, a Git
 * service, an HTTP client, a search provider); when one is absent the tool is
 * still registered but fails closed with a structured "unavailable" error — a
 * declared-but-unavailable tool is never advertised or run as if it worked.
 */
class ToolsModule(
    private val tools: List<Tool> = emptyList(),
    private val policy: ToolPermissionPolicy = ToolPermissionPolicy.default(),
    private val workspaces: WorkspaceFileSystemResolver = DelegatingWorkspaceFileSystemResolver(),
    private val connections: ToolConnectionAuthorizer = DelegatingToolConnectionAuthorizer(),
    private val hostPaths: WorkspaceHostPathResolver = DelegatingWorkspaceHostPathResolver(),
    private val commandExecutor: ProcessExecutor? = null,
    private val git: GitService = DelegatingGitService(),
    private val gitPush: GitPushService = DelegatingGitPushService(),
    private val gitProjects: GitProjectProvider = DelegatingGitProjectProvider(),
    private val webFetch: HttpGetClient = DelegatingHttpGetClient(),
    private val webSearch: WebSearchProvider = DelegatingWebSearchProvider(),
    private val ciVerification: DelegatingCiVerificationService = DelegatingCiVerificationService(),
    private val ciRepository: DelegatingCiRepositoryRefProvider = DelegatingCiRepositoryRefProvider(),
    private val pullRequests: DelegatingPullRequestService = DelegatingPullRequestService(),
    private val pullRequestRepository: DelegatingPullRequestRepositoryProvider =
        DelegatingPullRequestRepositoryProvider(),
) : ForgeModule {

    private val registry = DefaultToolRegistry()

    override val id: String = "tools"

    override fun initialize(context: ModuleContext) {
        val resolver = workspaces
        BuiltinTools.filesystem(resolver).forEach(registry::register)
        // Execution is only registered when a backend exists for this build; a
        // build with no executor therefore does not advertise a command tool.
        commandExecutor?.let { executor ->
            BuiltinTools.execution(executor, hostPaths).forEach(registry::register)
        }
        BuiltinTools.git(git, gitPush).forEach(registry::register)
        BuiltinTools.web(webFetch, webSearch).forEach(registry::register)
        registry.register(TodoWriteTool())
        // CI verification is registered with bindable collaborators so it fails
        // closed until the app attaches the GitHub-backed service and the active
        // project's repository resolver.
        BuiltinTools.verification(ciVerification, ciRepository).forEach(registry::register)
        // Optional, approval-gated pull-request creation. Same bindable pattern, so
        // `create_pr` is discoverable but fails closed until the app wires GitHub.
        BuiltinTools.pullRequests(pullRequests, pullRequestRepository).forEach(registry::register)
        tools.forEach(registry::register)
        context.services.register(ServiceKeys.TOOL_REGISTRY, registry)
        context.services.register(
            ServiceKeys.TOOL_ROUTER,
            DefaultToolRouter(registry = registry, policy = policy, connections = connections),
        )
        context.services.register(ServiceKeys.TOOL_PERMISSION_POLICY, policy)
        context.services.register(ServiceKeys.TOOL_WORKSPACE_RESOLVER, resolver)
        context.services.register(ServiceKeys.TOOL_WORKSPACE_HOST_PATHS, hostPaths)
        context.services.register(ServiceKeys.GIT_SERVICE, git)
        // The push service and the active-project provider it resolves are bindable:
        // the app attaches the GitHub-backed implementation and the workspace-backed
        // provider once the connection infrastructure and workspace runtime exist.
        context.services.register(ServiceKeys.GIT_PUSH_SERVICE, gitPush)
        context.services.register(ServiceKeys.GIT_PROJECT_PROVIDER, gitProjects)
        context.services.register(ServiceKeys.CI_VERIFICATION_SERVICE, ciVerification)
        context.services.register(ServiceKeys.CI_REPOSITORY_REF_PROVIDER, ciRepository)
        context.services.register(ServiceKeys.PULL_REQUEST_SERVICE, pullRequests)
        context.services.register(ServiceKeys.PULL_REQUEST_REPOSITORY_PROVIDER, pullRequestRepository)
        context.services.register(ServiceKeys.TOOL_CONNECTION_AUTHORIZER, connections)
    }
}
