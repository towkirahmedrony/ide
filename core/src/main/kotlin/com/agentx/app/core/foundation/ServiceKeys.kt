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
}
