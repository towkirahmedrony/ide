package com.agentx.app.model.discovery

import com.agentx.app.model.ModelCapabilities
import com.agentx.app.model.ModelDescriptor
import com.agentx.app.model.capability.CapabilityProvenance
import com.agentx.app.model.capability.CapabilitySupport
import com.agentx.app.model.capability.ModelCapabilityProfile
import com.agentx.app.model.connect.DiscoveryFailureKind

/**
 * One model a provider reports, normalized into AgentX's canonical shape.
 *
 * The provider abstraction reports; it never decides. A provider does not choose
 * a role, does not assign a model and does not touch a saved configuration, so
 * this type carries identity and provider-reported metadata only.
 *
 * Nothing here is invented. A field is null when the provider did not report it:
 * an unknown context window stays unknown, and an unknown deprecation stays
 * unknown rather than being guessed from a model name.
 *
 * Capability knowledge does not live here beyond what the provider itself
 * stated, and even that is carried as a tri-state so a bare listing never looks
 * capable. Authoritative capability knowledge lives in the
 * `ModelCapabilityRegistry` (hardcoded overlays, narrow provider-reported
 * evidence, plus an explicit per-connection override), so a discovered model can
 * never gain tool calling just by being listed. An OpenAI-compatible protocol
 * says nothing about a model's tool calling, so [toolCalling] stays UNKNOWN
 * unless a provider actually reports it.
 */
data class DiscoveredModel(
    /** The id that is sent to the provider's chat endpoint. */
    val modelId: String,
    val displayName: String? = null,
    val contextWindowTokens: Int? = null,
    val maxOutputTokens: Int? = null,
    /**
     * True when the provider explicitly reports the model as deprecated or
     * retired. Null means the provider did not say, which is not the same as
     * "current".
     */
    val deprecated: Boolean? = null,
    /**
     * Provider-reported availability. Null means the provider did not say, which
     * is not the same as unavailable.
     */
    val available: Boolean? = null,
    /** True when the provider is a local / on-device runtime. */
    val local: Boolean = false,
    val providerOwnedBy: String? = null,
    val createdAtMillis: Long? = null,
    /**
     * Non-secret provider metadata worth keeping for diagnostics — for example
     * Gemini's `supportedGenerationMethods`, which is what decides whether an
     * entry is a text-chat model at all. Never a credential.
     */
    val providerMetadata: Map<String, String> = emptyMap(),
    /**
     * Streaming support the provider's own metadata attests, otherwise
     * [CapabilitySupport.UNKNOWN]. Gemini reports `streamGenerateContent` in
     * `supportedGenerationMethods`, which is exactly this kind of evidence; a
     * provider that does not report it leaves the value UNKNOWN. It is never
     * [CapabilitySupport.UNSUPPORTED], because a list that omits a method is not
     * proof that the model cannot stream.
     */
    val streaming: CapabilitySupport = CapabilitySupport.UNKNOWN,
) {
    init {
        require(modelId.isNotBlank()) { "modelId must not be blank" }
    }

    /**
     * Combines two reports of the same model, keeping every field either of them
     * knew.
     *
     * A paginated list can repeat a model — the second page often carries less
     * detail than the first — so the later entry must fill gaps rather than erase
     * what was already read. Nothing is invented: a field neither entry reported
     * stays null.
     */
    fun mergeWith(other: DiscoveredModel): DiscoveredModel = DiscoveredModel(
        modelId = modelId,
        displayName = displayName ?: other.displayName,
        contextWindowTokens = contextWindowTokens ?: other.contextWindowTokens,
        maxOutputTokens = maxOutputTokens ?: other.maxOutputTokens,
        deprecated = deprecated ?: other.deprecated,
        available = available ?: other.available,
        local = local || other.local,
        providerOwnedBy = providerOwnedBy ?: other.providerOwnedBy,
        createdAtMillis = createdAtMillis ?: other.createdAtMillis,
        providerMetadata = other.providerMetadata + providerMetadata,
        // Provider-attested streaming survives a merge; two reports that both
        // stayed silent leave it unknown.
        streaming = if (streaming == CapabilitySupport.UNKNOWN) other.streaming else streaming,
    )

    /** The canonical descriptor shape `ModelProvider.listModels` reports. */
    fun toModelDescriptor(providerId: String): ModelDescriptor = ModelDescriptor(
        id = modelId,
        label = displayName?.takeIf { it.isNotBlank() } ?: modelId,
        providerId = providerId,
        contextWindow = contextWindowTokens,
        capabilities = ModelCapabilities(
            streaming = streaming.isSupported,
            contextWindowTokens = contextWindowTokens,
            maxOutputTokens = maxOutputTokens,
            local = local,
        ),
    )
}

/**
 * What one discovery attempt ended in.
 *
 * The three cases are kept apart on purpose: "this provider has no model list",
 * "the model list could not be read" and "here are the models" need different
 * handling, and collapsing them into an empty list is how a built-in fallback
 * gets mistaken for a discovered catalog.
 */
sealed interface ModelDiscoveryOutcome {

    /**
     * The provider answered with a model list. [models] are the entries this
     * runtime can drive; [reportedCount] is everything the provider listed before
     * filtering, and [rejected] records why the rest were dropped.
     *
     * [reportedCount] may be greater than zero while [models] is empty: the list
     * was read, but nothing in it can run text generation.
     */
    data class Discovered(
        val models: List<DiscoveredModel>,
        val reportedCount: Int = models.size,
        val rejected: List<String> = emptyList(),
        /** True when the provider had more pages than this build reads. */
        val truncated: Boolean = false,
        /** The HTTP status the list was served with, when the provider saw one. */
        val httpStatus: Int? = null,
    ) : ModelDiscoveryOutcome {
        init {
            require(reportedCount >= 0) { "reportedCount must not be negative" }
        }
    }

    /**
     * The provider exposes no model list at all. This is a capability of the
     * provider, not a failure: a local runtime without a list endpoint is still
     * fully usable with manually configured models.
     */
    data class Unavailable(
        val reason: String,
        val message: String,
    ) : ModelDiscoveryOutcome

    /**
     * The provider has a model list but it could not be read. The caller keeps
     * its last known catalog; nothing is erased and nothing is substituted.
     */
    data class Failed(
        val kind: DiscoveryFailureKind,
        val message: String,
        val httpStatus: Int? = null,
    ) : ModelDiscoveryOutcome

    companion object {
        /** The provider has no model-list endpoint. */
        const val REASON_NO_MODEL_LIST: String = "no-model-list"

        /** The provider is not configured, so there is nothing to ask. */
        const val REASON_NOT_CONNECTED: String = "not-connected"
    }
}

/**
 * A provider's own model-list lookup.
 *
 * Binding the connection into the callable rather than into the provider keeps
 * the provider stateless: it is constructed once per connection by the existing
 * provider factory and asked for whatever that connection can list.
 */
fun interface ModelDiscovery {
    suspend fun discover(): ModelDiscoveryOutcome
}

/**
 * The Part 1 capability record a discovered model maps onto.
 *
 * Discovery is identity, not capability proof, so every support flag stays
 * [com.agentx.app.model.capability.CapabilitySupport.UNKNOWN] until the hardcoded
 * overlay or an explicit connection override says otherwise.
 */
fun DiscoveredModel.toCapabilityProfile(providerId: String): ModelCapabilityProfile =
    ModelCapabilityProfile.discovered(
        providerId = providerId,
        modelId = modelId,
        displayName = displayName,
        maxContextTokens = contextWindowTokens,
        maxOutputTokens = maxOutputTokens,
        local = local,
        streaming = streaming,
        provenance = if (streaming == CapabilitySupport.UNKNOWN) {
            CapabilityProvenance.DISCOVERED
        } else {
            CapabilityProvenance.PROVIDER_REPORTED
        },
    )
