package com.agentx.app.model.discovery

import com.agentx.app.model.connect.normalizeModelId
import com.agentx.app.model.json.JsonCodec
import com.agentx.app.model.json.JsonObject
import com.agentx.app.model.json.arrayOrNull
import com.agentx.app.model.json.booleanOrNull
import com.agentx.app.model.json.numberOrNull
import com.agentx.app.model.json.objectOrNull
import com.agentx.app.model.json.stringOrNull
import com.agentx.app.model.preset.ModelProviderIds

/**
 * Tolerant reader for a provider's model list.
 *
 * One implementation, used by both the providers that own discovery and by the
 * catalog's own fallback request, so every provider is normalized exactly once
 * and a provider and its catalog can never disagree about what a list means.
 *
 * Two shapes are accepted, because providers answer with either: the
 * OpenAI-compatible `data` array and the provider's own `models` array (Gemini's
 * list, where each entry names the model as `models/<id>`). A missing field stays
 * null — nothing about a model is invented, and an unreadable entry is dropped
 * with a reason rather than guessed at.
 */
object ModelListParsing {

    /** The generation method that means "this model does text chat". */
    const val GENERATE_CONTENT_METHOD: String = "generateContent"

    /** How many rejection reasons are reported, so a log stays readable. */
    const val REJECTION_SAMPLE_SIZE: Int = 3

    /**
     * Gemini families that are not text-agent models: embeddings, image and video
     * generation, speech/audio synthesis, transcription, music, and question
     * answering. Applied only when the provider reports no generation methods, and
     * only to Gemini. A model the API reports as text-generating is never dropped
     * on its name, so a newly released text family stays usable.
     */
    val GEMINI_NON_TEXT_MARKERS: List<String> = listOf(
        "embedding",
        "imagen",
        "image",
        "veo",
        "tts",
        "transcribe",
        "speech",
        "audio",
        "lyria",
        "rerank",
        "guard",
        "moderation",
        "aqa",
    )

    /** Families the AgentX text runtime cannot drive on any provider. */
    private val NON_TEXT_MARKERS: List<String> = listOf(
        "whisper",
        "tts",
        "playai-tts",
        "embed",
        "guard",
        "moderation",
        "rerank",
        "speech",
        "dall-e",
    )

    /** A parsed model list, with everything a diagnostic log needs. */
    data class ParsedList(
        val models: List<DiscoveredModel>,
        val reportedCount: Int,
        val rejected: List<String>,
    )

    /**
     * Parses a model list [body] for [providerId], keeping only the entries this
     * runtime can drive. Returns null when [body] is not a model list at all.
     */
    fun parse(body: String, providerId: String): ParsedList? {
        val root = runCatching { JsonCodec.parse(body).objectOrNull() }.getOrNull() ?: return null
        val entries = root.arrayOrNull("data") ?: root.arrayOrNull("models") ?: return null
        // Identity is the model id, so a repeated entry collapses to one model: a
        // provider that lists the same id twice must not produce two catalog entries.
        val accepted = LinkedHashMap<String, DiscoveredModel>()
        val rejected = mutableListOf<String>()
        entries.forEach { item ->
            val entry = item.objectOrNull() ?: return@forEach
            when (val parsed = parseModel(entry, providerId)) {
                is ParsedEntry.Accepted -> accepted[parsed.model.modelId] = parsed.model
                is ParsedEntry.Rejected -> rejected += "${parsed.id}: ${parsed.reason}"
            }
        }
        return ParsedList(models = accepted.values.toList(), reportedCount = entries.size, rejected = rejected)
    }

    /** The `nextPageToken` of a paginated list, or null when there is no next page. */
    fun nextPageToken(body: String): String? =
        runCatching { JsonCodec.parse(body).objectOrNull() }
            .getOrNull()
            ?.stringOrNull("nextPageToken")
            ?.takeIf { it.isNotBlank() }

    /**
     * Keeps models the text agent runtime can drive.
     *
     * Provider-reported generation methods win when present: they are the
     * provider's own capability metadata and the runtime needs text generation.
     * Without them the identifier is filtered against the known non-text families.
     */
    fun isRunnableTextModel(providerId: String, id: String, methods: List<String>): Boolean {
        // The provider's own generation methods are proof, so they decide alone: a
        // model that reports text generation is never dropped on its name, which
        // keeps a newly released family (whose name nothing recognises yet) usable.
        if (methods.isNotEmpty()) {
            return methods.any { it.equals(GENERATE_CONTENT_METHOD, ignoreCase = true) }
        }
        if (!isUsableTextModel(id)) return false
        if (providerId == ModelProviderIds.GEMINI) {
            val lower = id.lowercase()
            return GEMINI_NON_TEXT_MARKERS.none { lower.contains(it) }
        }
        return true
    }

    /** The documented reason a model is rejected when no methods were reported. */
    fun nonTextReason(methods: List<String>): String = if (methods.isEmpty()) {
        "not a text-generation model"
    } else {
        "does not report $GENERATE_CONTENT_METHOD (${methods.joinToString("/")})"
    }

    private fun parseModel(model: JsonObject, providerId: String): ParsedEntry {
        val rawId = model.stringOrNull("id") ?: model.stringOrNull("name")
        // Gemini reports `models/<id>`; the id sent to chat is the bare one.
        val id = rawId?.let(::normalizeModelId).orEmpty()
        if (id.isEmpty()) return ParsedEntry.Rejected(UNNAMED_MODEL, "the entry has no id or name")

        val methods = stringList(model, "supportedGenerationMethods", "supported_generation_methods")
        if (!isRunnableTextModel(providerId, id, methods)) {
            return ParsedEntry.Rejected(id, nonTextReason(methods))
        }

        val metadata = LinkedHashMap<String, String>()
        if (methods.isNotEmpty()) metadata[SUPPORTED_METHODS_KEY] = methods.joinToString(",")
        model.stringOrNull("baseModelId")?.takeIf { it.isNotBlank() }?.let { metadata["baseModelId"] = it }
        model.stringOrNull("version")?.takeIf { it.isNotBlank() }?.let { metadata["version"] = it }
        model.stringOrNull("description")?.takeIf { it.isNotBlank() }?.let { metadata["description"] = it }

        return ParsedEntry.Accepted(
            DiscoveredModel(
                modelId = id,
                displayName = (model.stringOrNull("displayName") ?: model.stringOrNull("display_name"))
                    ?.takeIf { it.isNotBlank() },
                contextWindowTokens = firstInt(
                    model,
                    "context_window",
                    "context_window_tokens",
                    "max_context",
                    "inputTokenLimit",
                    "input_token_limit",
                ),
                maxOutputTokens = firstInt(
                    model,
                    "max_output_tokens",
                    "max_completion_tokens",
                    "outputTokenLimit",
                    "output_token_limit",
                ),
                deprecated = deprecationOf(model),
                available = model.booleanOrNull("active"),
                providerOwnedBy = model.stringOrNull("owned_by")?.takeIf { it.isNotBlank() },
                createdAtMillis = model.numberOrNull("created")?.toLong(),
                providerMetadata = metadata,
            ),
        )
    }

    /** A provider-reported deprecation, or null when the provider did not say. */
    private fun deprecationOf(model: JsonObject): Boolean? {
        model.booleanOrNull("deprecated")?.let { return it }
        model.booleanOrNull("retired")?.let { return it }
        return when (val status = model.stringOrNull("status")?.lowercase()) {
            null -> null
            else -> when {
                status.contains("deprecat") || status.contains("retired") || status.contains("shutdown") -> true
                status.contains("active") || status.contains("available") -> false
                else -> null
            }
        }
    }

    private fun stringList(model: JsonObject, vararg keys: String): List<String> {
        keys.forEach { key ->
            val array = model.arrayOrNull(key) ?: return@forEach
            return array.mapNotNull { it.stringOrNull() }
        }
        return emptyList()
    }

    private fun firstInt(model: JsonObject, vararg keys: String): Int? {
        keys.forEach { key -> model.numberOrNull(key)?.let { return it.toInt() } }
        return null
    }

    /**
     * Groq's list also returns speech, guard and embedding models that the AgentX
     * text runtime cannot drive. Those are never exposed as choices.
     */
    fun isUsableTextModel(id: String): Boolean {
        val lower = id.lowercase()
        return NON_TEXT_MARKERS.none { lower.contains(it) }
    }

    private const val UNNAMED_MODEL: String = "(unnamed)"

    /** Key under which the provider's own generation methods are preserved. */
    const val SUPPORTED_METHODS_KEY: String = "supportedGenerationMethods"

    private sealed interface ParsedEntry {
        data class Accepted(val model: DiscoveredModel) : ParsedEntry

        data class Rejected(val id: String, val reason: String) : ParsedEntry
    }
}
