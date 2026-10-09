package com.agentx.app.model.preset

import com.agentx.app.model.capability.CapabilitySupport
import com.agentx.app.model.capability.ModelCapability
import com.agentx.app.model.capability.ModelCapabilityDeclaration
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
 * Serializes presets with the JSON codec that already ships with the model layer,
 * so persistence needs no additional dependency.
 *
 * The format is deliberately tolerant: unknown fields are ignored and an entry
 * that cannot be understood is dropped rather than guessed at. A preset never
 * contains a credential, so nothing written here is secret.
 */
object ModelPresetCodec {

    fun encode(preset: ModelPreset): String = JsonCodec.encode(toJson(preset))

    fun decode(text: String): ModelPreset? = runCatching {
        JsonCodec.parse(text).objectOrNull()?.let(::fromJson)
    }.getOrNull()

    fun encodeAll(presets: List<ModelPreset>): String =
        JsonCodec.encode(JsonValue.Arr(presets.map(::toJson)))

    fun decodeAll(text: String): List<ModelPreset> = runCatching {
        JsonCodec.parse(text).arrayOrNull()
            ?.mapNotNull { item -> item.objectOrNull()?.let(::fromJson) }
            .orEmpty()
    }.getOrElse { emptyList() }

    // --- encoding -----------------------------------------------------------

    private fun toJson(preset: ModelPreset): JsonValue.Obj {
        val fields = LinkedHashMap<String, JsonValue>()
        fields["id"] = Json.of(preset.id)
        fields["displayName"] = Json.of(preset.displayName)
        fields["providerType"] = Json.of(preset.providerType.name)
        fields["modelIdentifier"] = Json.of(preset.modelIdentifier)
        fields["apiProtocol"] = Json.of(preset.apiProtocol.name)
        fields["apiBasePath"] = Json.of(preset.apiBasePath)
        preset.credentialRef?.let { fields["credentialRef"] = Json.of(it) }
        fields["startupScript"] = Json.of(preset.startupScript)
        preset.serverPort?.let { fields["serverPort"] = Json.of(it) }
        fields["enabled"] = Json.of(preset.enabled)
        // Only what the user actually states is written, so a preset that declares
        // nothing gains no field and an older build reads the same document back
        // unchanged (unknown fields are already ignored on decode).
        //
        // Each statement is nested under the model id it was made for, so a
        // connection that serves several models persists the difference between
        // them instead of collapsing them into one claim. A document written by an
        // earlier build (a flat capability → support object) is still read: it is a
        // statement about the preset's own model (see declaredCapabilitiesFrom).
        val stated = preset.declaredCapabilitiesByModel.filterValues { !it.isEmpty }
        if (stated.isNotEmpty()) {
            val byModel = LinkedHashMap<String, JsonValue>()
            stated.forEach { (modelId, declaration) ->
                val declared = LinkedHashMap<String, JsonValue>()
                declaration.declared.forEach { (capability, support) ->
                    declared[capability.id] = Json.of(support.name)
                }
                byModel[modelId] = Json.obj(declared)
            }
            fields["declaredCapabilities"] = Json.obj(byModel)
        }
        fields["setupKind"] = Json.of(preset.setupKind)
        fields["createdAtMillis"] = Json.of(preset.createdAtMillis)
        fields["updatedAtMillis"] = Json.of(preset.updatedAtMillis)
        fields["endpoint"] = Json.obj(
            "mode" to Json.of(preset.endpoint.mode.name),
            "explicitUrl" to (preset.endpoint.explicitUrl?.let { Json.of(it) } ?: JsonValue.Null),
        )
        fields["tunnel"] = Json.obj(
            "type" to Json.of(preset.tunnel.type.name),
            "marker" to Json.of(preset.tunnel.marker),
        )
        fields["health"] = Json.obj(
            "path" to (preset.health.path?.let { Json.of(it) } ?: JsonValue.Null),
            "timeoutMillis" to Json.of(preset.health.timeoutMillis),
            "intervalMillis" to Json.of(preset.health.intervalMillis),
            "requireModelInList" to Json.of(preset.health.requireModelInList),
        )
        preset.colab?.let { colab ->
            fields["colab"] = Json.obj("notebookUrl" to Json.of(colab.notebookUrl))
        }
        return JsonValue.Obj(fields)
    }

    // --- decoding -----------------------------------------------------------

    private fun fromJson(json: JsonObject): ModelPreset? {
        val id = json.stringOrNull("id")?.takeIf { it.isNotBlank() } ?: return null
        val displayName = json.stringOrNull("displayName")?.takeIf { it.isNotBlank() } ?: return null
        val providerType = fromName(ModelProviderType.entries, json.stringOrNull("providerType")) ?: return null
        val apiProtocol = fromName(ModelApiProtocol.entries, json.stringOrNull("apiProtocol"))
            ?: ModelApiProtocol.OPENAI_COMPATIBLE

        val endpointJson = json.objectOrNull("endpoint")
        val mode = fromName(EndpointDiscoveryMode.entries, endpointJson?.stringOrNull("mode"))
            ?: EndpointDiscoveryMode.RUNTIME_OUTPUT
        val explicitUrl = endpointJson?.stringOrNull("explicitUrl")?.takeIf { it.isNotBlank() }

        val tunnelJson = json.objectOrNull("tunnel")
        val tunnelType = fromName(TunnelType.entries, tunnelJson?.stringOrNull("type"))
            ?: TunnelType.CLOUDFLARE_QUICK

        val healthJson = json.objectOrNull("health")

        return ModelPreset(
            id = id,
            displayName = displayName,
            providerType = providerType,
            modelIdentifier = json.stringOrNull("modelIdentifier").orEmpty(),
            apiProtocol = apiProtocol,
            apiBasePath = json.stringOrNull("apiBasePath") ?: apiProtocol.defaultApiBasePath,
            credentialRef = json.stringOrNull("credentialRef")?.takeIf { it.isNotBlank() },
            startupScript = json.stringOrNull("startupScript").orEmpty(),
            serverPort = json.numberOrNull("serverPort")?.toInt(),
            endpoint = EndpointConfig(mode = mode, explicitUrl = explicitUrl),
            tunnel = TunnelConfig(
                type = tunnelType,
                marker = tunnelJson?.stringOrNull("marker") ?: TunnelConfig.DEFAULT_MARKER,
            ),
            health = HealthCheckConfig(
                path = healthJson?.stringOrNull("path")?.takeIf { it.isNotBlank() },
                timeoutMillis = healthJson?.numberOrNull("timeoutMillis")?.toLong()
                    ?: HealthCheckConfig.DEFAULT_TIMEOUT_MILLIS,
                intervalMillis = healthJson?.numberOrNull("intervalMillis")?.toLong()
                    ?: HealthCheckConfig.DEFAULT_INTERVAL_MILLIS,
                requireModelInList = healthJson?.booleanOrNull("requireModelInList") ?: true,
            ),
            colab = json.objectOrNull("colab")
                ?.stringOrNull("notebookUrl")
                ?.takeIf { it.isNotBlank() }
                ?.let(::ColabRuntimeConfig),
            enabled = json.booleanOrNull("enabled") ?: true,
            declaredCapabilitiesByModel = declaredCapabilitiesFrom(
                json.objectOrNull("declaredCapabilities"),
                json.stringOrNull("modelIdentifier").orEmpty(),
            ),
            setupKind = json.stringOrNull("setupKind")?.takeIf { it.isNotBlank() } ?: "custom",
            createdAtMillis = json.numberOrNull("createdAtMillis")?.toLong() ?: 0L,
            updatedAtMillis = json.numberOrNull("updatedAtMillis")?.toLong() ?: 0L,
        )
    }

    /**
     * Reads the saved statements, each keyed by the model id it was written for.
     *
     * Two shapes are accepted. The current one nests each statement under its model
     * id. The earlier one is a flat capability → support object, written when a
     * connection could only ever state anything about its own model; it is read as
     * a statement about [modelIdentifier], which is exactly what it meant, so a
     * declaration made before this change survives the upgrade.
     *
     * An absent object, an unknown capability name and an unreadable value are all
     * ignored rather than guessed at, which is what keeps a document written by a
     * newer build from turning into an unintended capability claim here. A
     * statement that survives none of that decoding is dropped — the same as never
     * having declared anything.
     */
    private fun declaredCapabilitiesFrom(
        json: JsonObject?,
        modelIdentifier: String,
    ): Map<String, ModelCapabilityDeclaration> {
        if (json == null) return emptyMap()
        // A flat declaration names capabilities as its keys and supports as its
        // (string) values; a per-model one names model ids as its keys and objects
        // as its values.
        val legacy = json.isNotEmpty() && json.values.all { value -> value.stringOrNull() != null }
        if (legacy) {
            val key = normalizeModelId(modelIdentifier)
            if (key.isEmpty()) return emptyMap()
            val declaration = declarationFrom(json.mapValues { (_, value) -> value.stringOrNull() })
            return if (declaration.isEmpty) emptyMap() else mapOf(key to declaration)
        }
        val byModel = LinkedHashMap<String, ModelCapabilityDeclaration>()
        json.forEach { (rawModelId, value) ->
            val objectValue = value.objectOrNull() ?: return@forEach
            val key = normalizeModelId(rawModelId)
            if (key.isEmpty()) return@forEach
            val declaration = declarationFrom(objectValue.mapValues { (_, raw) -> raw.stringOrNull() })
            if (!declaration.isEmpty) byModel[key] = declaration
        }
        return byModel
    }

    /** One statement, from capability id → support name, ignoring what cannot be read. */
    private fun declarationFrom(values: Map<String, String?>): ModelCapabilityDeclaration {
        val stated = LinkedHashMap<ModelCapability, CapabilitySupport>()
        values.forEach { (rawCapability, rawSupport) ->
            val capability = ModelCapability.fromId(rawCapability) ?: return@forEach
            val support = rawSupport
                ?.let { name -> CapabilitySupport.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
                ?: return@forEach
            stated[capability] = support
        }
        return ModelCapabilityDeclaration.from(stated)
    }

    private fun <T : Enum<T>> fromName(values: List<T>, raw: String?): T? =
        raw?.let { name -> values.firstOrNull { it.name == name } }
}
