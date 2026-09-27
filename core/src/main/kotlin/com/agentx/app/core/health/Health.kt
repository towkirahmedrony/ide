package com.agentx.app.core.health

/** Overall or per-check health state. */
enum class HealthStatus {
    HEALTHY,
    DEGRADED,
    UNHEALTHY,
}

/** Outcome of a single startup/health check. */
data class HealthCheckResult(
    val name: String,
    val status: HealthStatus,
    val message: String? = null,
)

/** Aggregated result reported at startup and surfaced by the UI. */
data class HealthReport(
    val status: HealthStatus,
    val checks: List<HealthCheckResult>,
    val generatedAtMillis: Long,
)

/** A single check. Implementations should be fast and side-effect free. */
fun interface HealthCheck {
    fun run(): HealthCheckResult
}

/** Runs registered checks and rolls their statuses into one report. */
class HealthMonitor {

    private val checks = mutableListOf<HealthCheck>()

    fun register(check: HealthCheck): HealthMonitor {
        checks += check
        return this
    }

    fun run(): HealthReport {
        val results = checks.map { check ->
            runCatching { check.run() }.getOrElse { error ->
                HealthCheckResult(
                    name = "unknown",
                    status = HealthStatus.UNHEALTHY,
                    message = error.message ?: error::class.simpleName,
                )
            }
        }
        return HealthReport(
            status = aggregate(results),
            checks = results,
            generatedAtMillis = System.currentTimeMillis(),
        )
    }

    private fun aggregate(results: List<HealthCheckResult>): HealthStatus = when {
        results.isEmpty() -> HealthStatus.DEGRADED
        results.any { it.status == HealthStatus.UNHEALTHY } -> HealthStatus.UNHEALTHY
        results.any { it.status == HealthStatus.DEGRADED } -> HealthStatus.DEGRADED
        else -> HealthStatus.HEALTHY
    }
}
