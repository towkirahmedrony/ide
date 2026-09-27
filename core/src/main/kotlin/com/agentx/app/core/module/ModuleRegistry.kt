package com.agentx.app.core.module

import com.agentx.app.core.ForgeError
import com.agentx.app.core.ForgeErrorCode
import com.agentx.app.core.di.ServiceContainer
import com.agentx.app.core.logging.ForgeLogger

/**
 * Ordered registry of [ForgeModule]s. Modules initialize in registration order
 * and dispose in reverse, so dependents are torn down before their dependencies.
 */
class ModuleRegistry(private val logger: ForgeLogger) {

    private val modules = LinkedHashMap<String, ForgeModule>()
    private val initialized = ArrayDeque<ForgeModule>()

    fun register(module: ForgeModule) {
        if (modules.containsKey(module.id)) {
            throw ForgeError(ForgeErrorCode.DUPLICATE_MODULE, "Module '${module.id}' is already registered")
        }
        modules[module.id] = module
    }

    fun modules(): List<ForgeModule> = modules.values.toList()

    fun isInitialized(): Boolean = initialized.isNotEmpty()

    fun initialize(services: ServiceContainer) {
        val context = ModuleContext(logger = logger, services = services)
        for (module in modules.values) {
            try {
                module.initialize(context)
                initialized.addLast(module)
                logger.debug("Module initialized", mapOf("module" to module.id))
            } catch (error: Throwable) {
                throw ForgeError(
                    code = ForgeErrorCode.MODULE_INIT_FAILED,
                    message = "Failed to initialize module '${module.id}'",
                    cause = error,
                )
            }
        }
    }

    fun dispose() {
        while (initialized.isNotEmpty()) {
            val module = initialized.removeLast()
            runCatching { module.dispose() }
                .onFailure { error -> logger.warn("Module dispose failed", mapOf("module" to module.id, "error" to error.message)) }
        }
    }
}
