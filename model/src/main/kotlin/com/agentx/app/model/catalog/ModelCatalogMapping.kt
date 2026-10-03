package com.agentx.app.model.catalog

import com.agentx.app.model.ModelCapabilities
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
        deprecated = deprecated,
        // A model is offered unless the provider said it is inactive or deprecated.
        available = (available != false) && deprecated != true,
        local = local,
        providerOwnedBy = providerOwnedBy,
        createdAtMillis = createdAtMillis,
    )

/**
 * Identity-only registration of discovered models into the capability registry.
 *
 * Catalog listing is not capability proof: a discovered model enters the registry
 * with unknown support, so it stays usable for plain chat without ever being
 * treated as tool-capable. Called from a successful refresh and from a restore of
 * a persisted snapshot, so both paths register exactly the same way.
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
            ).toCapabilityProfile(providerId),
        )
    }
}
