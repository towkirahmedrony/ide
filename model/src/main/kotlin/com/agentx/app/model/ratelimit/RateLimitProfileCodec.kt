package com.agentx.app.model.ratelimit

import com.agentx.app.model.json.Json
import com.agentx.app.model.json.JsonCodec
import com.agentx.app.model.json.JsonObject
import com.agentx.app.model.json.JsonValue
import com.agentx.app.model.json.arrayOrNull
import com.agentx.app.model.json.booleanOrNull
import com.agentx.app.model.json.numberOrNull
import com.agentx.app.model.json.objectOrNull
import com.agentx.app.model.json.stringOrNull

/**
 * Serializes configured rate-limit profiles with the JSON codec the model layer
 * already ships, so persistence needs no additional dependency and no second file
 * format.
 *
 * A profile is configuration, not a credential: it names a provider, a model and a
 * ceiling. Nothing secret can reach this format, and there is no field for a key,
 * a header or an endpoint.
 *
 * Decoding is deliberately tolerant — unknown fields are ignored and an entry that
 * cannot be understood is dropped rather than guessed at — because a preferences
 * file can hold data written by an older build. An unreadable entry must not become
 * a fabricated limit: a dropped profile simply means the provider keeps the
 * behaviour it has with no configured limit at all.
 */
object RateLimitProfileCodec {

    fun encodeAll(profiles: List<RateLimitProfile>): String =
        JsonCodec.encode(JsonValue.Arr(profiles.map(::toJson)))

    fun decodeAll(text: String): List<RateLimitProfile> = runCatching {
        JsonCodec.parse(text).arrayOrNull()
            ?.mapNotNull { item -> item.objectOrNull()?.let(::fromJson) }
            .orEmpty()
    }.getOrElse { emptyList() }

    private fun toJson(profile: RateLimitProfile): JsonValue.Obj {
        val fields = LinkedHashMap<String, JsonValue>()
        fields["providerId"] = Json.of(profile.providerId)
        profile.modelId?.let { fields["modelId"] = Json.of(it) }
        profile.accountId?.let { fields["accountId"] = Json.of(it) }
        profile.requestsPerMinute?.let { fields["requestsPerMinute"] = Json.of(it) }
        profile.requestsPerHour?.let { fields["requestsPerHour"] = Json.of(it) }
        profile.requestsPerDay?.let { fields["requestsPerDay"] = Json.of(it) }
        profile.tokensPerMinute?.let { fields["tokensPerMinute"] = Json.of(it) }
        profile.inputTokensPerMinute?.let { fields["inputTokensPerMinute"] = Json.of(it) }
        profile.outputTokensPerMinute?.let { fields["outputTokensPerMinute"] = Json.of(it) }
        profile.tokensPerDay?.let { fields["tokensPerDay"] = Json.of(it) }
        profile.maxConcurrentRequests?.let { fields["maxConcurrentRequests"] = Json.of(it) }
        fields["enabled"] = Json.of(profile.enabled)
        fields["safetyMargin"] = Json.of(profile.safetyMargin)
        fields["source"] = Json.of(profile.source.name)
        profile.reference?.let { fields["reference"] = Json.of(it) }
        fields["updatedAtMillis"] = Json.of(profile.updatedAtMillis)
        return JsonValue.Obj(fields)
    }

    private fun fromJson(fields: JsonObject): RateLimitProfile? {
        val providerId = fields.stringOrNull("providerId")?.takeIf { it.isNotBlank() } ?: return null
        val source = fields.stringOrNull("source")
            ?.let { name -> RateLimitSource.entries.firstOrNull { it.name == name } }
            ?: RateLimitSource.UNKNOWN
        // A stored margin outside the allowed range is a corrupt record, not a
        // policy: the entry is rejected rather than silently clamped, so a bad write
        // cannot quietly weaken the configured headroom.
        val margin = fields.numberOrNull("safetyMargin") ?: 0.0
        if (margin < 0.0 || margin > RateLimitProfile.MAX_SAFETY_MARGIN) return null
        return runCatching {
            RateLimitProfile(
                providerId = providerId,
                modelId = fields.stringOrNull("modelId"),
                accountId = fields.stringOrNull("accountId"),
                requestsPerMinute = fields.numberOrNull("requestsPerMinute")?.toInt(),
                requestsPerHour = fields.numberOrNull("requestsPerHour")?.toInt(),
                requestsPerDay = fields.numberOrNull("requestsPerDay")?.toInt(),
                tokensPerMinute = fields.numberOrNull("tokensPerMinute")?.toLong(),
                inputTokensPerMinute = fields.numberOrNull("inputTokensPerMinute")?.toLong(),
                outputTokensPerMinute = fields.numberOrNull("outputTokensPerMinute")?.toLong(),
                tokensPerDay = fields.numberOrNull("tokensPerDay")?.toLong(),
                maxConcurrentRequests = fields.numberOrNull("maxConcurrentRequests")?.toInt(),
                enabled = fields.booleanOrNull("enabled") ?: true,
                safetyMargin = margin,
                source = source,
                reference = fields.stringOrNull("reference"),
                updatedAtMillis = fields.numberOrNull("updatedAtMillis")?.toLong() ?: 0L,
            )
        }.getOrNull()
    }
}
