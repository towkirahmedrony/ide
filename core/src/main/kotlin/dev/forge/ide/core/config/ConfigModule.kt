package dev.forge.ide.core.config

import dev.forge.ide.core.foundation.ServiceKeys
import dev.forge.ide.core.module.ForgeModule
import dev.forge.ide.core.module.ModuleContext

/** Publishes the resolved [ForgeConfig] into the service container. */
class ConfigModule(private val config: ForgeConfig) : ForgeModule {

    override val id: String = "core.config"

    override fun initialize(context: ModuleContext) {
        context.services.register(ServiceKeys.CONFIG, config)
    }
}
