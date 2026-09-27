package com.agentx.app.core.config

import com.agentx.app.core.foundation.ServiceKeys
import com.agentx.app.core.module.ForgeModule
import com.agentx.app.core.module.ModuleContext

/** Publishes the resolved [ForgeConfig] into the service container. */
class ConfigModule(private val config: ForgeConfig) : ForgeModule {

    override val id: String = "core.config"

    override fun initialize(context: ModuleContext) {
        context.services.register(ServiceKeys.CONFIG, config)
    }
}
