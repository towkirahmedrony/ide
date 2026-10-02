package com.agentx.app.model

import com.agentx.app.core.foundation.ServiceKeys
import com.agentx.app.core.module.ForgeModule
import com.agentx.app.core.module.ModuleContext
import com.agentx.app.model.capability.InMemoryModelCapabilityRegistry
import com.agentx.app.model.capability.ModelCapabilityRegistry
import com.agentx.app.model.ratelimit.RateLimitManager
import com.agentx.app.model.ratelimit.RateLimitedModelGateway

/**
 * Wires the model gateway into the platform. Providers are supplied by later
 * tasks (or configuration); this module only publishes the gateway, so it
 * stays independent of any concrete provider.
 *
 * When a [RateLimitManager] is registered in the container, the published
 * gateway is a [RateLimitedModelGateway] so *every* agent role's request passes
 * one centralized admission point before the provider API call. Without a
 * manager the plain gateway is published, exactly as before.
 */
class ModelModule(
    private val providers: List<ModelProvider> = emptyList(),
) : ForgeModule {

    override val id: String = "model"

    override fun initialize(context: ModuleContext) {
        val capabilities = context.services.get<ModelCapabilityRegistry>(ServiceKeys.MODEL_CAPABILITY_REGISTRY)
            ?: InMemoryModelCapabilityRegistry.DEFAULT.also {
                context.services.register(ServiceKeys.MODEL_CAPABILITY_REGISTRY, it)
            }
        val base = DefaultModelGateway(capabilities)
        providers.forEach(base::register)
        val rateLimitManager = context.services.get<RateLimitManager>(ServiceKeys.RATE_LIMIT_MANAGER)
        val gateway: ModelGateway = if (rateLimitManager != null) {
            RateLimitedModelGateway(base, rateLimitManager)
        } else {
            base
        }
        context.services.register(ServiceKeys.MODEL_GATEWAY, gateway)
    }
}
