package com.agentx.app.core.module

import com.agentx.app.core.di.ServiceContainer
import com.agentx.app.core.logging.ForgeLogger

/** Context handed to every module during startup. */
class ModuleContext(
    val logger: ForgeLogger,
    val services: ServiceContainer,
)

/**
 * A unit of the application that participates in startup and shutdown. The
 * foundation registers a small number of modules; future layers (agent, model,
 * workspace, ...) will register their own.
 */
interface ForgeModule {
    val id: String

    fun initialize(context: ModuleContext)

    fun dispose() = Unit
}
