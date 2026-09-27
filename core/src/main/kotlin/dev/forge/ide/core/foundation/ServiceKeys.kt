package dev.forge.ide.core.foundation

/** Canonical keys for services registered in the [dev.forge.ide.core.di.ServiceContainer]. */
object ServiceKeys {
    const val CONFIG = "forge.config"
    const val LOGGER = "forge.logger"
    const val ARCHITECTURE = "forge.architecture"
    const val TOOL_REGISTRY = "forge.tools.registry"
    const val TOOL_ROUTER = "forge.tools.router"
    const val TOOL_PERMISSION_POLICY = "forge.tools.permissionPolicy"
    const val MODEL_GATEWAY = "forge.model.gateway"
}
