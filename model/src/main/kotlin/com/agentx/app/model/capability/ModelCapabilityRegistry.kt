package com.agentx.app.model.capability

import com.agentx.app.model.ModelCapabilities
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.ModelProviderError
import com.agentx.app.model.ModelProviderErrorCode
import com.agentx.app.model.ModelRequest
import com.agentx.app.model.preset.normalizeModelId
import com.agentx.app.model.preset.ModelProviderIds

/**
 * Single authoritative lookup for model capabilities.
 *
 * Hardcoded [ModelCapabilityProfile]s win for known models. Dynamically
 * discovered models remain usable: they resolve to an unknown profile that
 * never advertises tool calling until a definition is registered.
 *
 * A [ModelConfig.capabilities] override is treated as explicit per-connection
 * knowledge and wins over both the registry and the provider default.
 */
interface ModelCapabilityRegistry {
    fun get(providerId: String, modelId: String): ModelCapabilityProfile?

    fun profile(providerId: String, modelId: String): ModelCapabilityProfile

    fun supports(providerId: String, modelId: String, capability: ModelCapability): Boolean

    fun support(providerId: String, modelId: String, capability: ModelCapability): CapabilitySupport

    fun isKnown(providerId: String, modelId: String): Boolean

    fun isLocal(providerId: String, modelId: String): Boolean

    fun models(providerId: String): List<ModelCapabilityProfile>

    fun providers(): List<String>

    fun register(profile: ModelCapabilityProfile)

    /**
     * Registers [profile] without replacing an existing identity.
     *
     * Identity is [ModelCapabilityProfile.providerId] + [ModelCapabilityProfile.modelId].
     * A second call for the same pair merges metadata; it never substitutes a
     * different model and never upgrades unknown support to true.
     */
    fun registerOrUpdate(profile: ModelCapabilityProfile) {
        register(profile)
    }

    /**
     * Runtime flags for a request. Order of precedence:
     * 1. [ModelConfig.capabilities] override on the request
     * 2. A known registry definition
     * 3. Conservative unknown defaults (tool calling off)
     */
    fun capabilitiesFor(config: ModelConfig): ModelCapabilities
}

class InMemoryModelCapabilityRegistry(
    initial: List<ModelCapabilityProfile> = KnownModelCapabilities.ALL,
) : ModelCapabilityRegistry {

    private val lock = Any()
    private val profiles = LinkedHashMap<String, ModelCapabilityProfile>()

    init {
        initial.forEach { registerUnlocked(it) }
    }

    override fun get(providerId: String, modelId: String): ModelCapabilityProfile? = synchronized(lock) {
        profiles[key(providerId, modelId)]
    }

    override fun profile(providerId: String, modelId: String): ModelCapabilityProfile =
        get(providerId, modelId) ?: ModelCapabilityProfile.unknown(providerId.trim(), normalizeModelId(modelId))

    override fun supports(providerId: String, modelId: String, capability: ModelCapability): Boolean =
        support(providerId, modelId, capability).isSupported

    override fun support(providerId: String, modelId: String, capability: ModelCapability): CapabilitySupport =
        profile(providerId, modelId).support(capability)

    override fun isKnown(providerId: String, modelId: String): Boolean = get(providerId, modelId)?.known == true

    override fun isLocal(providerId: String, modelId: String): Boolean = profile(providerId, modelId).local

    override fun models(providerId: String): List<ModelCapabilityProfile> = synchronized(lock) {
        val wanted = providerId.trim()
        profiles.values.filter { it.providerId == wanted }
    }

    override fun providers(): List<String> = synchronized(lock) {
        profiles.values.map { it.providerId }.distinct()
    }

    override fun register(profile: ModelCapabilityProfile) {
        synchronized(lock) { registerUnlocked(profile) }
    }

    override fun registerOrUpdate(profile: ModelCapabilityProfile) {
        synchronized(lock) { registerUnlocked(profile) }
    }

    override fun capabilitiesFor(config: ModelConfig): ModelCapabilities {
        config.capabilities?.let { return it }
        return profile(config.providerId, config.model).toModelCapabilities()
    }

    private fun registerUnlocked(profile: ModelCapabilityProfile) {
        val incoming = profile.withoutInferredCapabilities()
        val identity = key(incoming.providerId, incoming.modelId)
        val existing = profiles[identity]
        profiles[identity] = if (existing == null) incoming else existing.mergeFrom(incoming)
    }

    companion object {
        val DEFAULT: ModelCapabilityRegistry = InMemoryModelCapabilityRegistry()

        fun key(providerId: String, modelId: String): String =
            "${providerId.trim()}::${normalizeModelId(modelId)}"
    }
}

object ModelCapabilityErrors {

    const val CODE: String = "MODEL_CAPABILITY_UNSUPPORTED"

    /** A known model definition exists but is disabled and must not be executed. */
    const val MODEL_DISABLED: String = "MODEL_DISABLED"

    const val DETAIL_CAPABILITY: String = "capability"
    const val DETAIL_MODEL: String = "model"
    const val DETAIL_KNOWN: String = "known"

    fun unsupported(
        providerId: String,
        modelId: String,
        capability: ModelCapability,
        known: Boolean = false,
    ): ModelProviderError = ModelProviderError(
        code = ModelProviderErrorCode.UNSUPPORTED,
        message = "$CODE provider=$providerId model=$modelId capability=${capability.id}",
        providerId = providerId,
        providerErrorType = CODE,
        details = mapOf(
            DETAIL_CAPABILITY to capability.id,
            DETAIL_MODEL to modelId,
            DETAIL_KNOWN to known,
        ),
    )

    fun requireToolCalling(request: ModelRequest, registry: ModelCapabilityRegistry) {
        if (request.tools.isEmpty()) return
        require(request.config, ModelCapability.TOOL_CALLING, registry)
    }

    fun require(
        config: ModelConfig,
        capability: ModelCapability,
        registry: ModelCapabilityRegistry,
    ) {
        if (registry.supports(config.providerId, config.model, capability)) return
        val profile = registry.profile(config.providerId, config.model)
        throw unsupported(config.providerId, config.model, capability, known = profile.known)
    }
}

/** True when this connection is a local, on-device or local-network runtime. */
fun ModelConfig.isLocalRuntime(registry: ModelCapabilityRegistry = InMemoryModelCapabilityRegistry.DEFAULT): Boolean {
    if (capabilities?.local == true) return true
    if (registry.isLocal(providerId, model)) return true
    val providerType = metadata[LOCAL_PROVIDER_TYPE_METADATA]
    if (providerType.equals(LOCAL_PHONE_TYPE, ignoreCase = true)) return true
    return providerId == ModelProviderIds.OPENAI_COMPATIBLE && isLoopbackEndpoint(baseUrl)
}

private const val LOCAL_PROVIDER_TYPE_METADATA: String = "providerType"
private const val LOCAL_PHONE_TYPE: String = "LOCAL_PHONE"

/** True when [baseUrl] points at this device or the local network. */
internal fun isLoopbackEndpoint(baseUrl: String): Boolean {
    val host = runCatching { java.net.URI(baseUrl.trim()).host?.lowercase() }.getOrNull() ?: return false
    return host == "localhost" || host == "127.0.0.1" || host == "::1" || host.endsWith(".local")
}
