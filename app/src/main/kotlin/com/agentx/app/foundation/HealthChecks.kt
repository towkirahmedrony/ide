package com.agentx.app.foundation

import com.agentx.app.core.architecture.LayerDescriptor
import com.agentx.app.core.config.ForgeConfig
import com.agentx.app.core.di.ServiceContainer
import com.agentx.app.core.foundation.ServiceKeys
import com.agentx.app.core.health.HealthCheckResult
import com.agentx.app.core.health.HealthStatus
import com.agentx.app.core.module.ModuleRegistry

internal fun configCheck(config: ForgeConfig): HealthCheckResult = HealthCheckResult(
    name = "config",
    status = HealthStatus.HEALTHY,
    message = "environment=${config.environment}, logLevel=${config.logLevel}",
)

internal fun moduleCheck(registry: ModuleRegistry, services: ServiceContainer): HealthCheckResult {
    val moduleCount = registry.modules().size
    return if (registry.isInitialized() && moduleCount > 0) {
        HealthCheckResult(
            name = "modules",
            status = HealthStatus.HEALTHY,
            message = "$moduleCount initialized, ${services.keys().size} services",
        )
    } else {
        HealthCheckResult(
            name = "modules",
            status = HealthStatus.UNHEALTHY,
            message = "no modules initialized",
        )
    }
}

/**
 * The model layer must publish a manager: without it no saved model could be
 * connected, and the agent would have nothing to talk to.
 */
internal fun modelCheck(services: ServiceContainer): HealthCheckResult {
    val hasManager = services.has(ServiceKeys.MODEL_MANAGER)
    val hasGateway = services.has(ServiceKeys.MODEL_GATEWAY)
    return HealthCheckResult(
        name = "model",
        status = if (hasManager && hasGateway) HealthStatus.HEALTHY else HealthStatus.UNHEALTHY,
        message = "gateway=${if (hasGateway) "ready" else "missing"}, " +
            "manager=${if (hasManager) "ready" else "missing"}",
    )
}

internal fun architectureCheck(layers: List<LayerDescriptor>): HealthCheckResult {
    val ids = layers.map { it.id }
    val valid = ids.isNotEmpty() && ids.size == ids.toSet().size
    return if (valid) {
        HealthCheckResult(
            name = "architecture",
            status = HealthStatus.HEALTHY,
            message = "${layers.size} layers registered",
        )
    } else {
        HealthCheckResult(
            name = "architecture",
            status = HealthStatus.UNHEALTHY,
            message = "duplicate or missing layer ids",
        )
    }
}
