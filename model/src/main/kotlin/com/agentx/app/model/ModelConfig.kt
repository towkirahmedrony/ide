package com.agentx.app.model

import com.agentx.app.model.json.JsonObject

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
    val metadata: Map<String, String> = emptyMap(),
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
