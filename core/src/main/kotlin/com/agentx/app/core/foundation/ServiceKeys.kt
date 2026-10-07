package com.agentx.app.core.foundation

/** Canonical keys for services registered in the [com.agentx.app.core.di.ServiceContainer]. */
object ServiceKeys {
    const val CONFIG = "forge.config"
    const val LOGGER = "forge.logger"
    const val ARCHITECTURE = "forge.architecture"
    const val TOOL_REGISTRY = "forge.tools.registry"
    const val TOOL_ROUTER = "forge.tools.router"
    const val TOOL_PERMISSION_POLICY = "forge.tools.permissionPolicy"
    const val TOOL_WORKSPACE_RESOLVER = "forge.tools.workspaceResolver"

    /** Bindable host-directory resolver for agent-issued commands. */
    const val TOOL_WORKSPACE_HOST_PATHS = "forge.tools.workspaceHostPaths"

    /** Bindable Git service the git tools operate through. */
    const val GIT_SERVICE = "forge.git.service"

    /**
     * Bindable authenticated push service the agent's `git_push` tool operates
     * through. Fail closed with "no connection" until the app binds the real one.
     */
    const val GIT_PUSH_SERVICE = "forge.git.pushService"

    /**
     * Bindable active-project provider the GitHub push service resolves the
     * repository from; the app attaches the workspace-backed provider after boot.
     */
    const val GIT_PROJECT_PROVIDER = "forge.git.projectProvider"

    /**
     * Bindable GitHub Actions verification service the agent's `ci_verification`
     * tool reads through. Fail closed with "no connection" until the app binds the
     * real, authenticated implementation.
     */
    const val CI_VERIFICATION_SERVICE = "forge.git.ciVerificationService"

    /**
     * Bindable resolver for the repository whose CI is observed; the app attaches
     * one that reads the active project's GitHub remote.
     */
    const val CI_REPOSITORY_REF_PROVIDER = "forge.git.ciRepositoryRefProvider"

    /**
     * Bindable GitHub pull-request service the agent's `create_pr` tool writes
     * through. Fail closed with "no connection" until the app binds the real,
     * authenticated implementation.
     */
    const val PULL_REQUEST_SERVICE = "forge.git.pullRequestService"

    /**
     * Bindable resolver for the repository a pull request targets; the app attaches
     * one that reads the active project's GitHub remote.
     */
    const val PULL_REQUEST_REPOSITORY_PROVIDER = "forge.git.pullRequestRepositoryProvider"
    const val CONTEXT_ENGINE = "forge.context.engine"
    const val MODEL_GATEWAY = "forge.model.gateway"
    const val MODEL_MANAGER = "forge.model.manager"

    /** Central admission control for remote model traffic (RPM/TPM/RPD/concurrency). */
    const val RATE_LIMIT_MANAGER = "forge.model.rateLimitManager"

    /** Persistent-ish usage totals recorded by the rate-limit manager. */
    const val MODEL_USAGE = "forge.model.usage"

    /** Dynamic per-provider model catalogs (for example Groq's `/openai/v1/models`). */
    const val MODEL_CATALOG = "forge.model.catalog"

    /** Authoritative provider/model capability lookup. */
    const val MODEL_CAPABILITY_REGISTRY = "forge.model.capabilities"
    const val AGENT_ORCHESTRATOR = "forge.agent.orchestrator"
    const val AGENT_REGISTRY = "forge.agent.registry"
    const val AGENT_SESSION_STORE = "forge.agent.sessions"
    const val AGENT_CONVERSATION_STORE = "forge.agent.conversations"
    const val AGENT_HISTORY = "forge.agent.history"

    /** Central agent system-prompt manager (defaults + user overrides). */
    const val AGENT_PROMPTS = "forge.agent.prompts"

    /**
     * The single authoritative role → model configuration shared by Settings and
     * the Agent Core's model resolver.
     */
    const val AGENT_ROLE_MODELS = "forge.agent.roleModels"

    /**
     * The persisted, user-owned fallback configuration: whether controlled fallback
     * is enabled and which ordered candidate chain each role may use.
     *
     * Registered so the runtime can execute it and a settings surface can change it
     * without either side inventing a second source of truth.
     */
    const val AGENT_FALLBACK_CONFIG = "forge.agent.fallbackConfig"

    /**
     * Central, per-operation execution budgets shared by the Agent Core, the Tool
     * System and the Model Gateway. One source of truth for every timeout.
     */
    const val AGENT_TIMEOUTS = "forge.agent.timeouts"

    /** Central skills registry and manager. */
    const val SKILLS = "forge.skills"
    const val CONNECTION_MANAGER = "forge.integrations.connectionManager"
    const val INTEGRATION_REGISTRY = "forge.integrations.registry"
    const val TOOL_CONNECTION_AUTHORIZER = "forge.tools.connectionAuthorizer"

    /** Service providers this build can connect to, with their tool catalogs. */
    const val CONNECTION_PROVIDER_REGISTRY = "forge.integrations.connectionProviders"

    /** Hands a credential to a service client without exposing it to the agent. */
    const val CONNECTION_CREDENTIAL_GATEWAY = "forge.integrations.credentialGateway"

    /** Personal provider setup: Client IDs and callback URIs for this IDE. */
    const val INTEGRATION_SETUP = "forge.integrations.setup"

    /** Starts OS processes for one-shot command execution. */
    const val PROCESS_RUNTIME = "forge.workspace.processRuntime"

    /** One-shot command executor sharing the same [PROCESS_RUNTIME]. */
    const val PROCESS_EXECUTOR = "forge.workspace.processExecutor"

    // The human terminal is not registered here any more. It is the embedded Termux runtime
    // (`:termux-runtime`), created once per process by the application instead of being
    // published through the service container, because its sessions must outlive the Activity.

    /** Structural understanding of source files: languages, syntax trees, symbols. */
    const val CODE_INTELLIGENCE = "forge.codeintel"

    /**
     * The parser backend, bindable after boot. The platform attaches the
     * tree-sitter provider here exactly like the workspace resolver.
     */
    const val CODE_INTELLIGENCE_PARSERS = "forge.codeintel.parsers"
}
