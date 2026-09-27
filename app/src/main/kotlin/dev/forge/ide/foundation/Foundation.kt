package dev.forge.ide.foundation

import dev.forge.ide.agent.AGENT_LAYER
import dev.forge.ide.context.CONTEXT_LAYER
import dev.forge.ide.core.architecture.ArchitectureModule
import dev.forge.ide.core.architecture.CORE_LAYER
import dev.forge.ide.core.architecture.LayerDescriptor
import dev.forge.ide.core.config.ConfigModule
import dev.forge.ide.core.config.ForgeConfig
import dev.forge.ide.core.di.ServiceContainer
import dev.forge.ide.core.foundation.ServiceKeys
import dev.forge.ide.core.health.HealthMonitor
import dev.forge.ide.core.health.HealthReport
import dev.forge.ide.core.logging.ForgeLoggers
import dev.forge.ide.core.module.ModuleRegistry
import dev.forge.ide.git.GIT_LAYER
import dev.forge.ide.integrations.INTEGRATIONS_LAYER
import dev.forge.ide.model.MODEL_LAYER
import dev.forge.ide.model.ModelModule
import dev.forge.ide.skills.SKILLS_LAYER
import dev.forge.ide.tools.TOOLS_LAYER
import dev.forge.ide.tools.ToolsModule
import dev.forge.ide.ui.UI_LAYER
import dev.forge.ide.workspace.WORKSPACE_LAYER

/** Everything the UI needs to render the running foundation. */
data class FoundationState(
    val layers: List<LayerDescriptor>,
    val health: HealthReport,
    val config: ForgeConfig,
)

/**
 * Composition root. Wires the core modules and layer descriptors together and
 * runs the startup health check. Feature modules will be registered here as
 * they are implemented.
 */
object Foundation {

    fun boot(config: ForgeConfig = ForgeConfig()): FoundationState {
        val logger = ForgeLoggers.create(
            level = config.logLevel,
            baseFields = mapOf("app" to config.appName),
        )

        val services = ServiceContainer()
        services.register(ServiceKeys.LOGGER, logger)

        val layers = forgeLayers()

        val modules = ModuleRegistry(logger)
        modules.register(ConfigModule(config))
        modules.register(ArchitectureModule(layers))
        modules.register(ToolsModule())
        modules.register(ModelModule())
        modules.initialize(services)

        val health = HealthMonitor()
            .register { configCheck(config) }
            .register { moduleCheck(modules, services) }
            .register { architectureCheck(layers) }
            .run()

        logger.info("Foundation ready", mapOf("layers" to layers.size, "status" to health.status))

        return FoundationState(layers = layers, health = health, config = config)
    }

    private fun forgeLayers(): List<LayerDescriptor> = listOf(
        CORE_LAYER,
        MODEL_LAYER,
        AGENT_LAYER,
        TOOLS_LAYER,
        WORKSPACE_LAYER,
        CONTEXT_LAYER,
        GIT_LAYER,
        SKILLS_LAYER,
        INTEGRATIONS_LAYER,
        UI_LAYER,
    )
}
