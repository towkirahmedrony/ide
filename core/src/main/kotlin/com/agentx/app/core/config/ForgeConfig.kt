package com.agentx.app.core.config

import com.agentx.app.core.ForgeError
import com.agentx.app.core.ForgeErrorCode
import com.agentx.app.core.ForgeResult
import com.agentx.app.core.failure
import com.agentx.app.core.logging.LogLevel
import com.agentx.app.core.success

enum class ForgeEnvironment {
    DEVELOPMENT,
    PRODUCTION,
    TEST,
}

/**
 * Model gateway configuration. No endpoint or provider is hardcoded; both local
 * runtimes and remote APIs are expected to be configured at runtime.
 */
data class ModelGatewayConfig(
    val remoteEnabled: Boolean = false,
    val endpoint: String? = null,
)

/**
 * Application configuration. Every value has a safe default so the app boots in
 * development without any environment setup.
 */
data class ForgeConfig(
    val appName: String = DEFAULT_APP_NAME,
    val environment: ForgeEnvironment = ForgeEnvironment.DEVELOPMENT,
    val logLevel: LogLevel = LogLevel.INFO,
    val modelGateway: ModelGatewayConfig = ModelGatewayConfig(),
) {
    companion object {
        const val DEFAULT_APP_NAME = "AgentX"
    }
}

/**
 * Builds [ForgeConfig] from a generic string map (environment variables, Gradle
 * BuildConfig values, or an in-memory map in tests). Keys are intentionally
 * provider-agnostic and contain no secrets.
 */
object ForgeConfigLoader {

    const val KEY_APP_NAME = "FORGE_APP_NAME"
    const val KEY_ENVIRONMENT = "FORGE_ENV"
    const val KEY_LOG_LEVEL = "FORGE_LOG_LEVEL"
    const val KEY_MODEL_GATEWAY_ENABLED = "FORGE_MODEL_GATEWAY_ENABLED"
    const val KEY_MODEL_GATEWAY_ENDPOINT = "FORGE_MODEL_GATEWAY_ENDPOINT"

    fun load(source: Map<String, String> = emptyMap()): ForgeResult<ForgeConfig, ForgeError> {
        val errors = mutableListOf<String>()

        val appName = source[KEY_APP_NAME]?.takeIf { it.isNotBlank() } ?: ForgeConfig.DEFAULT_APP_NAME

        val environment = source[KEY_ENVIRONMENT]?.let { raw ->
            runCatching { ForgeEnvironment.valueOf(raw.trim().uppercase()) }.getOrElse {
                errors += "Invalid $KEY_ENVIRONMENT '$raw'"
                ForgeEnvironment.DEVELOPMENT
            }
        } ?: ForgeEnvironment.DEVELOPMENT

        val logLevel = source[KEY_LOG_LEVEL]?.let { raw ->
            runCatching { LogLevel.valueOf(raw.trim().uppercase()) }.getOrElse {
                errors += "Invalid $KEY_LOG_LEVEL '$raw'"
                LogLevel.INFO
            }
        } ?: LogLevel.INFO

        val remoteEnabled = source[KEY_MODEL_GATEWAY_ENABLED]?.let { raw ->
            when (raw.trim().lowercase()) {
                "true", "1", "yes" -> true
                "false", "0", "no" -> false
                else -> {
                    errors += "Invalid $KEY_MODEL_GATEWAY_ENABLED '$raw'"
                    false
                }
            }
        } ?: false

        val endpoint = source[KEY_MODEL_GATEWAY_ENDPOINT]?.takeIf { it.isNotBlank() }

        if (errors.isNotEmpty()) {
            return failure(
                ForgeError(
                    code = ForgeErrorCode.CONFIG_INVALID,
                    message = "Configuration is invalid",
                    details = mapOf("errors" to errors),
                ),
            )
        }

        return success(
            ForgeConfig(
                appName = appName,
                environment = environment,
                logLevel = logLevel,
                modelGateway = ModelGatewayConfig(remoteEnabled = remoteEnabled, endpoint = endpoint),
            ),
        )
    }
}
