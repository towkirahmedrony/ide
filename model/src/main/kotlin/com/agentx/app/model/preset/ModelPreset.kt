package com.agentx.app.model.preset

import com.agentx.app.model.capability.ModelCapabilityDeclaration

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
    /** Provider id used by [com.agentx.app.model.ModelConfig.providerId]. */
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
    /**
     * Chat path that addresses the model inside the path instead of the body.
     *
     * Gemini's own API is `models/<model>:generateContent`; when this is set the
     * provider builds the URL from the template and the request body carries no
     * model name. OpenAI-compatible protocols leave it null and send the model in
     * the body at [chatPath].
     */
    val chatPathTemplate: String? = null,
    /**
     * Header this API's own endpoints read a credential from.
     *
     * Part of the wire contract, like [defaultHealthPath]: discovery, the chat
     * probe, the runtime health check and normal completions all address the same
     * API, so they must all authenticate the same way. Hardcoding one header in
     * one layer is what made a working Gemini connection look unreachable — the
     * list request sent `x-goog-api-key` while the health check sent
     * `Authorization: Bearer`, which Gemini's API does not accept.
     */
    val credentialHeader: String = DEFAULT_CREDENTIAL_HEADER,
    /**
     * Prefix placed before the credential value. Empty when the header carries the
     * key on its own (`x-goog-api-key`), `"Bearer "` for an `Authorization` header.
     */
    val credentialScheme: String = DEFAULT_CREDENTIAL_SCHEME,
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

    /**
     * Gemini's own API.
     *
     * Not the same thing as Google's OpenAI-compatible surface: the model list is
     * `/v1beta/models`, a completion is
     * `POST /v1beta/models/<model>:generateContent` with the key in the
     * `x-goog-api-key` header, and the prompt travels in a native `contents`
     * payload. The compatible surface is a separate endpoint and is only used when
     * a preset is configured for it, so `/v1beta/openai/chat/completions` is never
     * requested on a native connection.
     */
    GEMINI_NATIVE(
        displayName = "Gemini API",
        providerId = "gemini",
        defaultApiBasePath = "/v1beta",
        chatPath = "",
        defaultHealthPath = "/models",
        healthIsRelativeToApiBase = true,
        chatPathTemplate = "/models/{model}:generateContent",
        // Google's documented API-key header for the Gemini API. An
        // `Authorization: Bearer` header is for OAuth access tokens, not an API
        // key, and is rejected.
        credentialHeader = GEMINI_API_KEY_HEADER,
        credentialScheme = "",
    ),
    ;

    /** The header/value pair that authenticates a request with [credential]. */
    fun credentialHeaders(credential: String?): Map<String, String> {
        val value = credential?.takeIf { it.isNotBlank() } ?: return emptyMap()
        return mapOf(credentialHeader to credentialScheme + value)
    }

    /** Chat path for [modelId], for protocols that address the model in the path. */
    fun chatPathFor(modelId: String): String =
        chatPathTemplate?.replace(MODEL_TOKEN, normalizeModelId(modelId)) ?: chatPath

    companion object {
        const val MODEL_TOKEN: String = "{model}"
    }
}

/**
 * The single form of a model id.
 *
 * A provider may report a model as `models/<id>` (Gemini's model list does) while a
 * preset, a catalog entry and the UI all use the bare `<id>` that is sent to chat.
 * Normalizing both sides keeps the two forms resolving to one model — which is what
 * lets a health check confirm that the configured model is really offered, and what
 * keeps discovery, the catalog and the runtime agreeing about one model.
 */
fun normalizeModelId(raw: String): String =
    raw.trim().removePrefix(MODELS_PREFIX).trim().trimStart('/').trim()

/**
 * Credential headers and schemes, declared at file level on purpose.
 *
 * An enum entry's constructor arguments cannot read the enum's own companion
 * object, so the constants a protocol declares have to live outside it.
 */

/** Prefix a model id carries in the model list of a provider like Gemini. */
const val MODELS_PREFIX: String = "models/"

/**
 * The setup kind of an endpoint the user runs themselves — Local, ngrok, Cloudflare
 * Tunnel, Colab.
 *
 * Declared here rather than in the connect layer because a preset stores it and the
 * runtime derives the endpoint's request headers from it, so both halves have to
 * read the same value without the runtime depending on the connect package.
 */
const val CUSTOM_SETUP_KIND: String = "custom"

/**
 * The request flag Ngrok's free tier requires before it will answer an automated
 * client instead of its browser interstitial, which replies HTTP 403 to anything
 * without it.
 *
 * It is a request flag, not a credential: no secret, nothing user-specific, and it
 * never weakens authentication or TLS. Every real model server ignores an unknown
 * header, so it is safe to send whenever AgentX talks to a user-supplied endpoint.
 */
const val NGROK_SKIP_BROWSER_WARNING_HEADER: String = "ngrok-skip-browser-warning"

/**
 * The headers every request to a user-supplied endpoint carries.
 *
 * A Custom/Local endpoint is a server AgentX knows nothing about: it may be a bare
 * local runtime, or that same runtime reached through a proxy that inspects clients.
 * Anything the proxy needs has to be attached to *every* request — discovery, the
 * chat probe, the runtime health check and normal completions — which is why the
 * rule lives here once and every layer reads it from the same place.
 */
fun customEndpointRequestHeaders(setupKind: String): Map<String, String> =
    if (setupKind.trim().equals(CUSTOM_SETUP_KIND, ignoreCase = true)) {
        mapOf(NGROK_SKIP_BROWSER_WARNING_HEADER to "true")
    } else {
        emptyMap()
    }

/** Default credential header: an OpenAI-compatible bearer token. */
const val DEFAULT_CREDENTIAL_HEADER: String = "Authorization"

/** Prefix placed before the credential value in [DEFAULT_CREDENTIAL_HEADER]. */
const val DEFAULT_CREDENTIAL_SCHEME: String = "Bearer "

/** The API-key header Google documents for the Gemini API. */
const val GEMINI_API_KEY_HEADER: String = "x-goog-api-key"

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
        /**
         * Bounds one health request. Right after the app starts, or while a
         * Colab/ngrok tunnel reconnects, the model list can take a while to
         * answer, so this must not be tighter than the discovery read timeout.
         */
        const val DEFAULT_TIMEOUT_MILLIS: Long = 20_000
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
    /**
     * What the user states this one model/configuration can do.
     *
     * Empty by default, which changes nothing: an undeclared model keeps
     * resolving to `CapabilitySupport.UNKNOWN` and is never assumed capable. A
     * Custom/Local user who knows their own server declares the capabilities
     * AgentX cannot discover for itself here, so capability resolution stays
     * accurate for a model that has no authoritative definition — without
     * marking every OpenAI-compatible model tool-capable.
     */
    val declaredCapabilities: ModelCapabilityDeclaration = ModelCapabilityDeclaration(),
    /**
     * Quick-connect catalog id (`custom`, `gemini`, `groq`). Informational only;
     * the gateway still sees a normal OpenAI-compatible preset.
     */
    val setupKind: String = "custom",
    val createdAtMillis: Long = 0L,
    val updatedAtMillis: Long = 0L,
) {
    /** Whether the preset contains a usable connection description. */
    val isConfigured: Boolean get() = validate().isEmpty()

    /**
     * Stable provider identity this preset connects as, used by
     * [com.agentx.app.model.ModelConfig.providerId] so several providers can be
     * registered at once without one replacing another. Derived from the saved
     * [setupKind]/[apiProtocol]; nothing new is persisted.
     */
    val providerId: String get() = ModelProviderIds.forPreset(setupKind, apiProtocol)

    /** Normalized API base path (may be empty when the endpoint already has one). */
    val normalizedApiBasePath: String get() = normalizePath(apiBasePath)

    /**
     * Extra headers every request to this preset's endpoint carries, on top of the
     * credential.
     *
     * Part of the saved connection rather than a per-request detail: discovery, the
     * chat probe, the runtime health check and normal completions all read this same
     * map, so a saved preset reconnects with exactly the configuration that
     * connected. Derived from [setupKind], which is what is persisted, so a restart
     * reconstructs it without a second stored copy.
     */
    val requestHeaders: Map<String, String> get() = customEndpointRequestHeaders(setupKind)

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
