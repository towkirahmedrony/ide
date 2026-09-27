package dev.forge.ide.foundation

import dev.forge.ide.core.architecture.LayerDescriptor
import dev.forge.ide.core.config.ForgeConfig
import dev.forge.ide.core.di.ServiceContainer
import dev.forge.ide.core.health.HealthCheckResult
import dev.forge.ide.core.health.HealthStatus
import dev.forge.ide.core.module.ModuleRegistry

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
