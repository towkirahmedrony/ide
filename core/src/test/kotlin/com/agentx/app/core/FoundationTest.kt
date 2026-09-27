package com.agentx.app.core

import com.agentx.app.core.architecture.ArchitectureModule
import com.agentx.app.core.architecture.CORE_LAYER
import com.agentx.app.core.config.ConfigModule
import com.agentx.app.core.config.ForgeConfig
import com.agentx.app.core.config.ForgeConfigLoader
import com.agentx.app.core.config.ForgeEnvironment
import com.agentx.app.core.di.ServiceContainer
import com.agentx.app.core.foundation.ServiceKeys
import com.agentx.app.core.health.HealthCheckResult
import com.agentx.app.core.health.HealthMonitor
import com.agentx.app.core.health.HealthStatus
import com.agentx.app.core.logging.ForgeLoggers
import com.agentx.app.core.logging.LogLevel
import com.agentx.app.core.logging.LogRecord
import com.agentx.app.core.module.ModuleRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FoundationTest {

    @Test
    fun `config loader falls back to safe defaults`() {
        val result = ForgeConfigLoader.load(emptyMap())
        val config = assertIs<ForgeResult.Success<ForgeConfig>>(result).value
        assertEquals(ForgeConfig.DEFAULT_APP_NAME, config.appName)
        assertEquals(ForgeEnvironment.DEVELOPMENT, config.environment)
        assertEquals(false, config.modelGateway.remoteEnabled)
    }

    @Test
    fun `config loader rejects invalid values`() {
        val result = ForgeConfigLoader.load(mapOf(ForgeConfigLoader.KEY_ENVIRONMENT to "nope"))
        val error = assertIs<ForgeResult.Failure<ForgeError>>(result).error
        assertEquals(ForgeErrorCode.CONFIG_INVALID, error.code)
    }

    @Test
    fun `config loader parses provided values without hardcoded providers`() {
        val result = ForgeConfigLoader.load(
            mapOf(
                ForgeConfigLoader.KEY_ENVIRONMENT to "production",
                ForgeConfigLoader.KEY_LOG_LEVEL to "warn",
                ForgeConfigLoader.KEY_MODEL_GATEWAY_ENABLED to "true",
            ),
        )
        val config = assertIs<ForgeResult.Success<ForgeConfig>>(result).value
        assertEquals(ForgeEnvironment.PRODUCTION, config.environment)
        assertEquals(LogLevel.WARN, config.logLevel)
        assertEquals(true, config.modelGateway.remoteEnabled)
        assertEquals(null, config.modelGateway.endpoint)
    }

    @Test
    fun `module registry initializes modules and registers services`() {
        val logger = ForgeLoggers.create(LogLevel.ERROR, sink = {})
        val services = ServiceContainer()
        val registry = ModuleRegistry(logger)

        registry.register(ConfigModule(ForgeConfig()))
        registry.register(ArchitectureModule(listOf(CORE_LAYER)))
        registry.initialize(services)

        assertTrue(registry.isInitialized())
        assertNotNull(services.get(ServiceKeys.CONFIG))
        assertNotNull(services.get(ServiceKeys.ARCHITECTURE))
    }

    @Test
    fun `module registry rejects duplicate ids`() {
        val registry = ModuleRegistry(ForgeLoggers.create(LogLevel.ERROR, sink = {}))
        registry.register(ConfigModule(ForgeConfig()))
        val thrown = runCatching { registry.register(ConfigModule(ForgeConfig())) }.exceptionOrNull()
        assertIs<ForgeError>(thrown)
        assertEquals(ForgeErrorCode.DUPLICATE_MODULE, thrown.code)
    }

    @Test
    fun `health monitor aggregates the worst status`() {
        val report = HealthMonitor()
            .register { HealthCheckResult("ok", HealthStatus.HEALTHY) }
            .register { HealthCheckResult("weak", HealthStatus.DEGRADED) }
            .run()
        assertEquals(HealthStatus.DEGRADED, report.status)
        assertEquals(2, report.checks.size)
    }

    @Test
    fun `logger respects the configured level`() {
        val records = mutableListOf<LogRecord>()
        val logger = ForgeLoggers.create(LogLevel.WARN, sink = { records += it })
        logger.info("hidden")
        logger.error("shown")
        assertEquals(1, records.size)
        assertEquals(LogLevel.ERROR, records.single().level)
    }
}
