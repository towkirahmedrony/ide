package com.agentx.app.model.connect

import com.agentx.app.model.preset.DEFAULT_CREDENTIAL_HEADER
import com.agentx.app.model.preset.DEFAULT_CREDENTIAL_SCHEME
import com.agentx.app.model.preset.GEMINI_API_KEY_HEADER
import com.agentx.app.model.preset.ModelApiProtocol
import com.agentx.app.model.preset.ModelProviderType
import com.agentx.app.model.preset.normalizeModelId

/**
 * Catalog of well-known remote APIs that speak OpenAI-compatible chat.
 *
 * These are UI shortcuts only: they still produce a normal [com.agentx.app.model.preset.ModelPreset]
 * and talk through the existing Model Gateway. No vendor SDK is added.
 */
enum class ModelSetupKind(
    val id: String,
    val displayName: String,
    val description: String,
    val requiresApiKey: Boolean,
    val showsEndpointField: Boolean,
) {
    CUSTOM(
        id = "custom",
        displayName = "Local / Colab / ngrok",
        description = "A local, Colab, ngrok or Cloudflare endpoint you already run.",
        requiresApiKey = false,
        showsEndpointField = true,
    ),
    GEMINI(
        id = "gemini",
        displayName = "Google Gemini",
        description = "Google Gemini via the OpenAI-compatible API.",
        requiresApiKey = true,
        showsEndpointField = false,
    ),
    GROQ(
        id = "groq",
        displayName = "Groq",
        description = "Groq OpenAI-compatible inference.",
        requiresApiKey = true,
        showsEndpointField = false,
    ),
    ;

    companion object {
        fun fromId(raw: String?): ModelSetupKind =
            entries.firstOrNull { it.id.equals(raw, ignoreCase = true) } ?: CUSTOM
    }
}

/**
 * How a provider's model list is authenticated.
 *
 * A hosted compatible API usually accepts the chat credential as a bearer token,
 * but a provider's own API may document a dedicated key header instead. Keeping
 * the key in a header — never in a query string — also keeps it out of any URL
 * the app reports or logs.
 */
enum class ModelListAuth(val headerName: String, val scheme: String) {
    BEARER(
        headerName = DEFAULT_CREDENTIAL_HEADER,
        scheme = DEFAULT_CREDENTIAL_SCHEME,
    ),
    API_KEY_HEADER(
        headerName = GEMINI_API_KEY_HEADER,
        scheme = "",
    ),
    ;

    companion object {
        /**
         * The auth a protocol's own endpoints use.
         *
         * Derived rather than declared per provider, so the model list, the chat
         * probe and the runtime health check can never disagree about which header
         * carries the credential.
         */
        fun forProtocol(protocol: ModelApiProtocol): ModelListAuth =
            if (protocol.credentialHeader.equals(BEARER.headerName, ignoreCase = true)) {
                BEARER
            } else {
                API_KEY_HEADER
            }
    }
}

data class KnownProviderSpec(
    val kind: ModelSetupKind,
    val rootUrl: String,
    val apiBasePath: String,
    val protocol: ModelApiProtocol = ModelApiProtocol.OPENAI_COMPATIBLE,
    val providerType: ModelProviderType = ModelProviderType.REMOTE_OPENAI_COMPATIBLE,
    /**
     * Host-relative path of the provider's own model list, when that is not
     * `<rootUrl><apiBasePath>/models`.
     *
     * Gemini is the case this exists for: its model list is served by the Gemini
     * API (`/v1beta/models`), not by the OpenAI-compatible surface the chat calls
     * use, so asking the compatible surface for `/models` answers 404. The path is
     * relative to the provider's own host, so a preset pointed at a proxy still
     * lists models through that proxy.
     */
    val modelListPath: String? = null,
    /**
     * How the model list is authenticated; chat authentication is unchanged.
     *
     * Defaults to the auth the provider's own [protocol] documents, so a provider
     * declares its wire contract once instead of repeating its key header here.
     */
    val modelListAuth: ModelListAuth = ModelListAuth.forProtocol(protocol),
    /** Models offered when /models cannot be listed. Never an API secret. */
    val suggestedModels: List<String> = emptyList(),
    val preferredModel: String? = null,
) {

    /** The model list URL for this provider, on [baseUrl]'s host. */
    fun modelListUrlFor(baseUrl: String = rootUrl): String =
        modelListPath?.let { originOf(baseUrl) + it }
            ?: (rootUrl.trimEnd('/') + apiBasePath + MODEL_LIST_PATH)

    companion object {
        const val MODEL_LIST_PATH: String = "/models"
    }
}

/**
 * The host and path of [url], for diagnostics.
 *
 * Never includes the query string or any credential: it exists so a wrong
 * request path can be reported without leaking a key.
 */
internal fun diagnosticPath(url: String): String = runCatching {
    val uri = java.net.URI(url.trim())
    val path = uri.path?.takeIf { it.isNotBlank() } ?: "/"
    "${uri.host ?: ""}$path"
}.getOrElse { url.substringAfter("://", url) }

/** The scheme, host and port of [url], without any path, query or fragment. */
internal fun originOf(url: String): String {
    val trimmed = url.trim()
    return runCatching {
        val uri = java.net.URI(trimmed)
        val scheme = uri.scheme ?: return@runCatching trimmed.trimEnd('/')
        val host = uri.host ?: return@runCatching trimmed.trimEnd('/')
        if (uri.port > 0) "$scheme://$host:${uri.port}" else "$scheme://$host"
    }.getOrDefault(trimmed.trimEnd('/'))
}

object KnownModelProviders {

    val gemini: KnownProviderSpec = KnownProviderSpec(
        kind = ModelSetupKind.GEMINI,
        // Gemini's own API root. The OpenAI-compatible surface is a separate
        // endpoint (`.../v1beta/openai`) that this provider never builds a path on:
        // chat is `models/<model>:generateContent` and the list is `/v1beta/models`,
        // both on the native API, both with the key in the documented header.
        rootUrl = "https://generativelanguage.googleapis.com",
        apiBasePath = "/v1beta",
        protocol = ModelApiProtocol.GEMINI_NATIVE,
        modelListPath = "/v1beta/models",
        /**
         * Compatibility list only. It is used when the account's own model list
         * cannot be read (no key yet, offline, rate limited) so the app stays
         * usable. The provider's live list is authoritative and is what the picker
         * offers, so newly released models need no code change. Retired entries are
         * not kept here.
         */
        suggestedModels = listOf(
            "gemini-3.5-flash",
            "gemini-3.5-flash-lite",
        ),
        preferredModel = "gemini-3.5-flash",
    )

    val groq: KnownProviderSpec = KnownProviderSpec(
        kind = ModelSetupKind.GROQ,
        rootUrl = "https://api.groq.com/openai",
        apiBasePath = "/v1",
        suggestedModels = listOf(
            "llama-3.3-70b-versatile",
            "llama-3.1-8b-instant",
            "mixtral-8x7b-32768",
            "gemma2-9b-it",
        ),
        preferredModel = "llama-3.3-70b-versatile",
    )

    fun spec(kind: ModelSetupKind): KnownProviderSpec? = when (kind) {
        ModelSetupKind.CUSTOM -> null
        ModelSetupKind.GEMINI -> gemini
        ModelSetupKind.GROQ -> groq
    }
}

/**
 * Picks a model id from a discovered list.
 *
 * - one id → that id
 * - a preferred id that is present → that id
 * - a single non-utility model → that id
 * - otherwise null, so the UI can ask
 */
fun selectDiscoveredModel(
    ids: List<String>,
    preferred: String? = null,
    catalogPreferred: String? = null,
): String? {
    // Both sides are normalized, so a list that says `models/gemini-3.1-flash`
    // still matches a saved preset that says `gemini-3.1-flash`.
    val unique = ids.map(::normalizeModelId).filter { it.isNotEmpty() }.distinct()
    if (unique.isEmpty()) return null
    preferred?.takeIf { it.isNotBlank() }?.let { want ->
        unique.firstOrNull { it == normalizeModelId(want) }?.let { return it }
    }
    catalogPreferred?.takeIf { it.isNotBlank() }?.let { want ->
        unique.firstOrNull { it == normalizeModelId(want) }?.let { return it }
    }
    if (unique.size == 1) return unique.single()

    val usable = unique.filterNot { isUtilityModel(it) }
    if (usable.size == 1) return usable.single()

    val clearlyInstruct = usable.filter { id ->
        val lower = id.lowercase()
        lower.contains("instruct") || lower.contains("coder") || lower.contains("-chat")
    }
    if (clearlyInstruct.size == 1) return clearlyInstruct.single()

    catalogPreferred?.let { want ->
        usable.firstOrNull { it.startsWith(normalizeModelId(want)) }?.let { return it }
    }
    return null
}

internal fun isUtilityModel(id: String): Boolean {
    val lower = id.lowercase()
    return listOf("embed", "whisper", "tts", "moderation", "audio", "dall-e", "davinci", "babbage", "image")
        .any { lower.contains(it) }
}
