package com.agentx.app.model

import com.agentx.app.core.foundation.ServiceKeys
import com.agentx.app.core.module.ForgeModule
import com.agentx.app.core.module.ModuleContext

/**
 * Wires the model gateway into the platform. Providers are supplied by later
 * tasks (or configuration); this module only publishes the gateway, so it
 * stays independent of any concrete provider.
 */
class ModelModule(
    private val providers: List<ModelProvider> = emptyList(),
) : ForgeModule {

    private val gateway = DefaultModelGateway()

    override val id: String = "model"

    override fun initialize(context: ModuleContext) {
        providers.forEach(gateway::register)
        context.services.register(ServiceKeys.MODEL_GATEWAY, gateway)
    }
}
