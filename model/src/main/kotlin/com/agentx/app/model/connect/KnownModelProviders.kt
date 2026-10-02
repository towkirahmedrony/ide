package com.agentx.app.model.connect

import com.agentx.app.model.preset.ModelApiProtocol
import com.agentx.app.model.preset.ModelProviderType

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

data class KnownProviderSpec(
    val kind: ModelSetupKind,
    val rootUrl: String,
    val apiBasePath: String,
    val protocol: ModelApiProtocol = ModelApiProtocol.OPENAI_COMPATIBLE,
    val providerType: ModelProviderType = ModelProviderType.REMOTE_OPENAI_COMPATIBLE,
    /** Models offered when /models cannot be listed. Never an API secret. */
    val suggestedModels: List<String> = emptyList(),
    val preferredModel: String? = null,
)

object KnownModelProviders {

    val gemini: KnownProviderSpec = KnownProviderSpec(
        kind = ModelSetupKind.GEMINI,
        rootUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        apiBasePath = "",
        /**
         * Compatibility list only. It is used when the account's own model list
         * cannot be read (no key yet, offline, rate limited) so the app stays
         * usable. The provider's live list is authoritative and is what the picker
         * offers, so newly released models need no code change. Retired entries are
         * not kept here.
         */
        suggestedModels = listOf(
            "gemini-2.0-flash",
            "gemini-2.0-flash-lite",
        ),
        preferredModel = "gemini-2.0-flash",
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
    val unique = ids.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    if (unique.isEmpty()) return null
    preferred?.takeIf { it.isNotBlank() }?.let { want ->
        unique.firstOrNull { it == want }?.let { return it }
    }
    catalogPreferred?.takeIf { it.isNotBlank() }?.let { want ->
        unique.firstOrNull { it == want }?.let { return it }
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
        usable.firstOrNull { it.startsWith(want) }?.let { return it }
    }
    return null
}

internal fun isUtilityModel(id: String): Boolean {
    val lower = id.lowercase()
    return listOf("embed", "whisper", "tts", "moderation", "audio", "dall-e", "davinci", "babbage", "image")
        .any { lower.contains(it) }
}
