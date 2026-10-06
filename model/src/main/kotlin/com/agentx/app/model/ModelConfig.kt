package com.agentx.app.model

import com.agentx.app.model.capability.ModelCapabilityDeclaration
import com.agentx.app.model.json.JsonObject
import com.agentx.app.model.manager.ModelConnectionKind

/**
 * Provider-independent generation settings shared by local and remote models.
 * [extra] is an escape hatch for provider-independent knobs not modeled here.
 */
data class ModelGenerationSettings(
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
    val topP: Double? = null,
    val stop: List<String> = emptyList(),
    val extra: JsonObject = emptyMap(),
)

/**
 * Configuration for one model endpoint.
 *
 * Nothing here is provider-specific: the same shape targets a local runtime or
 * a remote API. [baseUrl] is always configurable, [model] is never hardcoded,
 * and [apiKey] is optional because local models often need no authentication.
 */
data class ModelConfig(
    /** Identifier used by the gateway to resolve the provider. */
    val providerId: String,
    /** Root URL of the endpoint (for example `http://localhost:11434/v1`). */
    val baseUrl: String,
    /** Model identifier sent to the endpoint. */
    val model: String,
    /** Optional credential; never logged. */
    val apiKey: String? = null,
    /** Whether responses should be requested as a stream. */
    val stream: Boolean = false,
    val generation: ModelGenerationSettings = ModelGenerationSettings(),
    val timeoutMillis: Long? = null,
    /** Additional HTTP headers; never logged. */
    val headers: Map<String, String> = emptyMap(),
    /** Explicit capability override; when set it wins over the provider's. */
    val capabilities: ModelCapabilities? = null,
    /**
     * What the saved configuration of *this* model states it can do.
     *
     * Null means "nothing stated", which leaves resolution to the registry exactly
     * as before. It is carried on the configuration so the selected model reaches
     * the eligibility check with its own capability instead of being reconstructed
     * from `providerId` + `model` alone and losing it. It is scoped to the model
     * named above and must not be carried across a model change.
     */
    val declaredCapabilities: ModelCapabilityDeclaration? = null,
    val metadata: Map<String, String> = emptyMap(),
    /**
     * Identity of the persisted connection this configuration belongs to.
     *
     * Deliberately separate from [providerId], which names a provider
     * *family/protocol* (`gemini`, `groq`, `openai-compatible`, …). Several
     * independent connections can share one family — two custom OpenAI-compatible
     * endpoints, or a custom endpoint and an Ollama server — so the family alone
     * cannot address a connection. This is the stable, persisted connection (model
     * preset) id, and it is what the gateway routes on. It defaults to
     * [providerId] so a configuration built without a saved preset (tests, a
     * preview, an ad-hoc request) keeps working unchanged.
     */
    val connectionId: String = providerId,

    /**
     * The execution domain this connection belongs to: [ModelConnectionKind.LOCAL_CUSTOM]
     * for a local model endpoint the user runs, or [ModelConnectionKind.API] for a
     * hosted remote provider (Gemini, Groq, FreeLLMAPI, …).
     *
     * Deliberately separate from [providerId], which names a provider *family/protocol*.
     * A local OpenAI-compatible endpoint and a remote FreeLLMAPI OpenAI-compatible
     * endpoint share one protocol but belong to different execution domains, so the
     * role resolver routes on this together with [connectionId] rather than on the
     * provider family alone. Null means "unspecified" and preserves the behaviour of
     * configurations built before the domain existed (tests, ad-hoc requests).
     */
    val connectionKind: ModelConnectionKind? = null,
) {
    /** Returns every configuration problem found; an empty list means valid. */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (providerId.isBlank()) errors += "providerId must not be blank"
        if (baseUrl.isBlank()) {
            errors += "baseUrl must not be blank"
        } else if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            errors += "baseUrl must start with http:// or https://"
        }
        if (model.isBlank()) errors += "model must not be blank"
        generation.temperature?.let { if (it !in 0.0..2.0) errors += "temperature must be between 0 and 2" }
        generation.topP?.let { if (it !in 0.0..1.0) errors += "topP must be between 0 and 1" }
        generation.maxOutputTokens?.let { if (it <= 0) errors += "maxOutputTokens must be positive" }
        timeoutMillis?.let { if (it <= 0) errors += "timeoutMillis must be positive" }
        return errors
    }
}
