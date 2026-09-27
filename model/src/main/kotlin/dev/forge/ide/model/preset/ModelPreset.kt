package dev.forge.ide.model.preset

/**
 * Where a model actually runs. This is deliberately independent of *how* the
 * endpoint is spoken to: [ModelApiProtocol] covers the wire contract, while the
 * provider type decides which runner and which endpoint discovery applies.
 *
 * The Model Gateway never branches on this value. It only ever sees the
 * resulting endpoint/API contract, which is what keeps Colab-specific logic out
 * of the gateway.
 */
enum class ModelProviderType(
    val displayName: String,
    val description: String,
    /**
     * Whether the endpoint is reached over the public network. Remote endpoints
     * must be served over TLS; only on-device runtimes may use plain HTTP.
     */
    val requiresSecureEndpoint: Boolean,
) {
    LOCAL_PHONE(
        displayName = "On this device",
        description = "A model server running on the phone itself (another app or a local runtime).",
        requiresSecureEndpoint = false,
    ),
    GOOGLE_COLAB(
        displayName = "Google Colab",
        description = "A notebook runtime in Google Colab, reached through a tunnel endpoint.",
        requiresSecureEndpoint = true,
    ),
    REMOTE_OPENAI_COMPATIBLE(
        displayName = "Remote endpoint",
        description = "A model server on a machine you already run, exposed over the network.",
        requiresSecureEndpoint = true,
    ),
    CUSTOM(
        displayName = "Custom",
        description = "Anything else that exposes a supported API protocol.",
        requiresSecureEndpoint = true,
    ),
}

/**
 * The wire protocol an endpoint speaks.
 *
 * Only protocols the project can actually implement are listed. Both entries are
 * served by the existing OpenAI-compatible provider; they differ in where the
 * model-list ("is the model API really up?") endpoint lives.
 */
enum class ModelApiProtocol(
    val displayName: String,
    /** Provider id used by [dev.forge.ide.model.ModelConfig.providerId]. */
    val providerId: String,
    /** Default base path appended to the endpoint for chat requests. */
    val defaultApiBasePath: String,
    /** Chat-completions path relative to the API base. */
    val chatPath: String,
    /**
     * Default health path. When [healthIsRelativeToApiBase] is true the path is
     * appended to the API base (`<endpoint>/v1/models`), otherwise to the
     * endpoint root (`<endpoint>/api/tags`).
     */
    val defaultHealthPath: String,
    val healthIsRelativeToApiBase: Boolean,
) {
    OPENAI_COMPATIBLE(
        displayName = "OpenAI compatible",
        providerId = "openai-compatible",
        defaultApiBasePath = "/v1",
        chatPath = "/chat/completions",
        defaultHealthPath = "/models",
        healthIsRelativeToApiBase = true,
    ),
    OLLAMA(
        displayName = "Ollama",
        providerId = "openai-compatible",
        defaultApiBasePath = "/v1",
        chatPath = "/chat/completions",
        defaultHealthPath = "/api/tags",
        healthIsRelativeToApiBase = false,
    ),
}

/** How the runner is expected to find the model endpoint. */
enum class EndpointDiscoveryMode(val displayName: String) {
    /** The user typed the endpoint into the preset. */
    CONFIGURED_ENDPOINT("Configured endpoint"),

    /** Detect the endpoint the runtime printed (for example a tunnel URL). */
    RUNTIME_OUTPUT("Detect from runtime output"),

    /** A server bound to the device itself, addressed by port. */
    DEVICE_LOCAL_PORT("Device local port"),
}

/** Supported tunnel kinds. Only endpoint detection is implemented — see the providers. */
enum class TunnelType(val displayName: String) {
    NONE("None"),
    CLOUDFLARE_QUICK("Cloudflare Quick Tunnel"),
    MANUAL("Manual URL"),
}

/**
 * Tunnel discovery configuration. [marker] is the token the runtime is expected
 * to print next to the endpoint; it is a plain marker string, never a URL.
 */
data class TunnelConfig(
    val type: TunnelType = TunnelType.CLOUDFLARE_QUICK,
    val marker: String = DEFAULT_MARKER,
) {
    companion object {
        const val DEFAULT_MARKER: String = "FORGE_ENDPOINT="
    }
}

/** Where the endpoint comes from. */
data class EndpointConfig(
    val mode: EndpointDiscoveryMode = EndpointDiscoveryMode.RUNTIME_OUTPUT,
    /** Required when [mode] is [EndpointDiscoveryMode.CONFIGURED_ENDPOINT]. */
    val explicitUrl: String? = null,
)

/** How the runner decides that the model API is actually reachable. */
data class HealthCheckConfig(
    /** Overrides the protocol's default health path. Must start with "/". */
    val path: String? = null,
    val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    /** How often a connected model is re-checked while the app is in front. */
    val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
    /** When true, the configured model id must appear in the model list. */
    val requireModelInList: Boolean = true,
) {
    companion object {
        const val DEFAULT_TIMEOUT_MILLIS: Long = 6_000
        const val DEFAULT_INTERVAL_MILLIS: Long = 30_000
    }
}

/** Colab-specific settings. Android cannot start a Colab runtime; see the docs. */
data class ColabRuntimeConfig(
    /** The notebook the user runs the model in. */
    val notebookUrl: String,
) {
    companion object {
        /** Host the Model Runner browser is allowed to navigate to. */
        const val NOTEBOOK_HOST: String = "colab.research.google.com"
    }
}

/**
 * A saved, reproducible description of one model connection. Everything needed
 * to reconnect later lives here; no credential is ever stored in the preset
 * itself, only a reference into the platform's secret store.
 *
 * Nothing about a specific model (Qwen, Llama, DeepSeek, ...) is assumed: names,
 * the model identifier, paths and the startup script are all user input.
 */
data class ModelPreset(
    val id: String,
    val displayName: String,
    val providerType: ModelProviderType,
    /** Model id sent to the endpoint. Never hardcoded. */
    val modelIdentifier: String,
    val apiProtocol: ModelApiProtocol = ModelApiProtocol.OPENAI_COMPATIBLE,
    val apiBasePath: String = apiProtocol.defaultApiBasePath,
    /** Reference into the secret store. The secret itself is never persisted here. */
    val credentialRef: String? = null,
    /** Startup script/configuration that prepares the model environment. */
    val startupScript: String = "",
    val serverPort: Int? = null,
    val endpoint: EndpointConfig = EndpointConfig(),
    val tunnel: TunnelConfig = TunnelConfig(),
    val health: HealthCheckConfig = HealthCheckConfig(),
    val colab: ColabRuntimeConfig? = null,
    val enabled: Boolean = true,
    val createdAtMillis: Long = 0L,
    val updatedAtMillis: Long = 0L,
) {
    /** Whether the preset contains a usable connection description. */
    val isConfigured: Boolean get() = validate().isEmpty()

    /** Normalized API base path (may be empty when the endpoint already has one). */
    val normalizedApiBasePath: String get() = normalizePath(apiBasePath)

    /**
     * Returns every configuration problem found; an empty list means the preset
     * can be used. Messages are user-facing and contain no secrets.
     */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()

        if (displayName.isBlank()) errors += "Name must not be blank"
        if (modelIdentifier.isBlank()) errors += "Model identifier must not be blank"

        serverPort?.let { port ->
            if (port !in 1..65_535) errors += "Server port must be between 1 and 65535"
        }

        apiBasePath.takeIf { it.isNotBlank() }?.let { path ->
            if (!path.startsWith("/")) errors += "API base path must start with \"/\""
        }
        health.path?.let { path ->
            if (!path.startsWith("/")) errors += "Health check path must start with \"/\""
        }
        if (health.timeoutMillis <= 0) errors += "Health check timeout must be positive"
        if (health.intervalMillis <= 0) errors += "Health check interval must be positive"
        if (tunnel.marker.isBlank()) errors += "Tunnel marker must not be blank"

        if (providerType == ModelProviderType.GOOGLE_COLAB) {
            val url = colab?.notebookUrl
            if (url.isNullOrBlank()) {
                errors += "A Colab notebook URL is required for Google Colab models"
            } else if (validateEndpointUrl(url, requireHttps = true) == null) {
                errors += "Colab notebook URL must be a valid https:// URL"
            }
        }

        when (endpoint.mode) {
            EndpointDiscoveryMode.CONFIGURED_ENDPOINT -> {
                val raw = endpoint.explicitUrl
                if (raw.isNullOrBlank()) {
                    errors += "An endpoint is required when the endpoint is configured explicitly"
                } else if (validateEndpointUrl(raw, providerType.requiresSecureEndpoint) == null) {
                    errors += if (providerType.requiresSecureEndpoint) {
                        "Endpoint must be a valid https:// URL"
                    } else {
                        "Endpoint must be a valid http(s) URL"
                    }
                }
            }

            EndpointDiscoveryMode.DEVICE_LOCAL_PORT -> {
                if (serverPort == null) {
                    errors += "A server port is required to reach a device-local model"
                }
            }

            EndpointDiscoveryMode.RUNTIME_OUTPUT -> {
                if (tunnel.type == TunnelType.MANUAL && endpoint.explicitUrl.isNullOrBlank()) {
                    errors += "A manual tunnel requires the tunnel URL"
                }
            }
        }

        return errors
    }

    companion object {
        /** Normalizes a configured path so callers can concatenate safely. */
        fun normalizePath(path: String): String {
            val trimmed = path.trim()
            if (trimmed.isEmpty() || trimmed == "/") return ""
            val withLeading = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
            return withLeading.trimEnd('/')
        }
    }
}
