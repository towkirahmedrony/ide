package com.agentx.app.core.architecture

import com.agentx.app.core.foundation.ServiceKeys
import com.agentx.app.core.module.ForgeModule
import com.agentx.app.core.module.ModuleContext

/** Read-only view of the platform's architectural layers. */
class Architecture(private val layers: List<LayerDescriptor>) {
    fun layers(): List<LayerDescriptor> = layers

    val count: Int get() = layers.size
}

/** Publishes the assembled layer list into the service container. */
class ArchitectureModule(private val layers: List<LayerDescriptor>) : ForgeModule {

    override val id: String = "core.architecture"

    override fun initialize(context: ModuleContext) {
        context.services.register(ServiceKeys.ARCHITECTURE, Architecture(layers))
    }
}
