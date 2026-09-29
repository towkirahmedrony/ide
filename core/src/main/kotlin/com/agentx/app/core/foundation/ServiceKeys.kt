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
    const val CONTEXT_ENGINE = "forge.context.engine"
    const val MODEL_GATEWAY = "forge.model.gateway"
    const val MODEL_MANAGER = "forge.model.manager"
    const val AGENT_ORCHESTRATOR = "forge.agent.orchestrator"
    const val AGENT_REGISTRY = "forge.agent.registry"
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
