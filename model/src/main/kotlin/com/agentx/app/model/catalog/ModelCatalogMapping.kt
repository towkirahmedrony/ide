package com.agentx.app.model.catalog

import com.agentx.app.model.ModelCapabilities
import com.agentx.app.model.capability.CapabilitySupport
import com.agentx.app.model.capability.ModelCapabilityRegistry
import com.agentx.app.model.discovery.DiscoveredModel
import com.agentx.app.model.discovery.toCapabilityProfile

/**
 * Bridges a provider's normalized descriptor into the catalog entry the picker and
 * the persisted snapshot use.
 *
 * [capabilitiesFor] is supplied by the catalog rather than inferred here, so the
 * Part 1 capability registry stays the only thing that decides whether a model
 * advertises tool calling or streaming. A provider that reported nothing stays
 * unknown: an earlier `active` flag or deprecation is preserved as reported, and a
 * missing one is not filled in.
 */
internal fun DiscoveredModel.toCatalogModel(capabilitiesFor: (String) -> ModelCapabilities): CatalogModel =
    CatalogModel(
        id = modelId,
        displayName = displayName?.takeIf { it.isNotBlank() },
        contextWindowTokens = contextWindowTokens,
        maxOutputTokens = maxOutputTokens,
        capabilities = capabilitiesFor(modelId).copy(local = local),
        // The provider's own stream attestation is carried separately from the
        // display hint above, so it survives persistence and is the only
        // streaming claim the registry is allowed to trust.
        providerAttestedStreaming = streaming.isSupported,
        deprecated = deprecated,
        // A model is offered unless the provider said it is inactive or deprecated.
        available = (available != false) && deprecated != true,
        local = local,
        providerOwnedBy = providerOwnedBy,
        createdAtMillis = createdAtMillis,
    )

/**
 * Registration of discovered models into the capability registry.
 *
 * Catalog listing is not capability proof: a discovered model enters the registry
 * with unknown tool calling, so it stays usable for plain chat without ever being
 * treated as tool-capable. The single exception is streaming, and only when the
 * provider's own metadata attested it ([CatalogModel.providerAttestedStreaming]) —
 * never inferred from a provider family or a model name. Called from a successful
 * refresh and from a restore of a persisted snapshot, so both paths register
 * exactly the same way and a restart keeps the same evidence.
 */
internal fun registerDiscoveredModels(
    registry: ModelCapabilityRegistry,
    providerId: String,
    models: List<CatalogModel>,
) {
    models.forEach { model ->
        registry.registerOrUpdate(
            DiscoveredModel(
                modelId = model.id,
                displayName = model.displayName,
                contextWindowTokens = model.contextWindowTokens,
                maxOutputTokens = model.maxOutputTokens,
                deprecated = model.deprecated,
                local = model.local,
                streaming = if (model.providerAttestedStreaming) {
                    CapabilitySupport.SUPPORTED
                } else {
                    CapabilitySupport.UNKNOWN
                },
            ).toCapabilityProfile(providerId),
        )
    }
}
