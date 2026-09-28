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
 * OAuth client configuration for one provider.
 *
 * Only **public** client values live here: the client id and the redirect URI.
 * A client id is not a secret, but it still has to be registered by the operator,
 * so it ships through the build/environment configuration instead of being
 * hardcoded. Client **secrets** never appear in this type or in the APK: the
 * authorization code is exchanged by [exchangeBrokerUrl], a server-side endpoint
 * that holds the confidential credentials.
 */
data class OAuthProviderConfig(
    val clientId: String = "",
    val redirectUri: String = "",
    /** Server-side endpoint that performs the confidential code exchange. */
    val exchangeBrokerUrl: String? = null,
    /**
     * Scopes registered for the OAuth app. Used only by providers that do not
     * report granted scopes back in the token response.
     */
    val scopes: Set<String> = emptySet(),
) {
    /** True when the provider can actually be authorized with this build. */
    val isConfigured: Boolean get() = clientId.isNotBlank() && redirectUri.isNotBlank()

    /** Explanation shown in the UI when [isConfigured] is false. */
    val missingConfigurationMessage: String?
        get() = when {
            clientId.isBlank() && redirectUri.isBlank() ->
                "No OAuth client id or redirect URI is configured for this provider."
            clientId.isBlank() -> "No OAuth client id is configured for this provider."
            redirectUri.isBlank() -> "No OAuth redirect URI is configured for this provider."
            else -> null
        }
}

/** OAuth configuration for every provider the app can authorize against. */
data class OAuthConfig(
    val github: OAuthProviderConfig = OAuthProviderConfig(),
    val supabase: OAuthProviderConfig = OAuthProviderConfig(),
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
    val oauth: OAuthConfig = OAuthConfig(),
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

    // OAuth client configuration. Client ids and redirect URIs are public values;
    // client secrets are never read here and never reach the APK.
    const val KEY_OAUTH_GITHUB_CLIENT_ID = "FORGE_OAUTH_GITHUB_CLIENT_ID"
    const val KEY_OAUTH_GITHUB_REDIRECT_URI = "FORGE_OAUTH_GITHUB_REDIRECT_URI"
    const val KEY_OAUTH_GITHUB_BROKER_URL = "FORGE_OAUTH_GITHUB_BROKER_URL"
    const val KEY_OAUTH_GITHUB_SCOPES = "FORGE_OAUTH_GITHUB_SCOPES"
    const val KEY_OAUTH_SUPABASE_CLIENT_ID = "FORGE_OAUTH_SUPABASE_CLIENT_ID"
    const val KEY_OAUTH_SUPABASE_REDIRECT_URI = "FORGE_OAUTH_SUPABASE_REDIRECT_URI"
    const val KEY_OAUTH_SUPABASE_BROKER_URL = "FORGE_OAUTH_SUPABASE_BROKER_URL"
    const val KEY_OAUTH_SUPABASE_SCOPES = "FORGE_OAUTH_SUPABASE_SCOPES"

    /** Splits a space- or comma-separated scope list. */
    fun parseScopes(raw: String?): Set<String> = raw
        ?.split(' ', ',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.toSet()
        .orEmpty()

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

        val oauth = OAuthConfig(
            github = OAuthProviderConfig(
                clientId = source[KEY_OAUTH_GITHUB_CLIENT_ID]?.trim().orEmpty(),
                redirectUri = source[KEY_OAUTH_GITHUB_REDIRECT_URI]?.trim().orEmpty(),
                exchangeBrokerUrl = source[KEY_OAUTH_GITHUB_BROKER_URL]?.trim()?.takeIf { it.isNotBlank() },
                scopes = parseScopes(source[KEY_OAUTH_GITHUB_SCOPES]),
            ),
            supabase = OAuthProviderConfig(
                clientId = source[KEY_OAUTH_SUPABASE_CLIENT_ID]?.trim().orEmpty(),
                redirectUri = source[KEY_OAUTH_SUPABASE_REDIRECT_URI]?.trim().orEmpty(),
                exchangeBrokerUrl = source[KEY_OAUTH_SUPABASE_BROKER_URL]?.trim()?.takeIf { it.isNotBlank() },
                scopes = parseScopes(source[KEY_OAUTH_SUPABASE_SCOPES]),
            ),
        )

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
                oauth = oauth,
            ),
        )
    }
}
