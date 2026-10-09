package com.agentx.app.model.capability

import com.agentx.app.model.ModelConfig
import com.agentx.app.model.preset.normalizeModelId

/**
 * The statement this configuration's connection holds for [modelId], or null when it
 * holds none.
 *
 * Reads only the per-model statements, and is therefore the safe lookup for a caller
 * that is *changing* the model: the single [ModelConfig.declaredCapabilities] field
 * belongs to whichever model the configuration currently names, so consulting it
 * while re-pointing the configuration would read one model's statement as another's.
 *
 * Matched on the normalized id, so the id as an endpoint or a user writes it resolves
 * to the same statement, and never by provider or prefix.
 */
fun ModelConfig.statedCapabilitiesFor(modelId: String): ModelCapabilityDeclaration? {
    val key = normalizeModelId(modelId)
    if (key.isEmpty()) return null
    statedCapabilitiesByModel[key]?.takeIf { !it.isEmpty }?.let { return it }
    return statedCapabilitiesByModel.entries
        .firstOrNull { normalizeModelId(it.key) == key && !it.value.isEmpty }
        ?.value
}

/**
 * The capability statement in effect for [modelId] on this configuration, or null
 * when nothing was stated for it.
 *
 * The exact model decides, never the connection. The per-model statements are
 * consulted first, and the single [ModelConfig.declaredCapabilities] field answers
 * only for the model this configuration names — the model it was stated for. That is
 * what lets a role resolve a different model of the same connection and still receive
 * the statement made for *that* model, instead of losing it, or borrowing a statement
 * made for another.
 */
fun ModelConfig.declarationFor(modelId: String = model): ModelCapabilityDeclaration? =
    statedCapabilitiesFor(modelId) ?: declaredCapabilities?.takeIf { statement ->
        // The single statement answers only for the model it was *stated* for — not for
        // whichever model this configuration happens to name now, which is what a
        // rebuild that re-pointed it would otherwise turn it into.
        val scope = declaredCapabilitiesModel ?: return@takeIf false
        normalizeModelId(scope) == normalizeModelId(modelId) && !statement.isEmpty
    }

/**
 * The capability profile in effect for this configuration.
 *
 * One function decides this so the eligibility checker, the role resolver and the
 * gateway can never disagree about what a model is believed to do. Precedence:
 *
 * 1. [ModelConfig.capabilities] — the blunt boolean override, which has always
 *    won and keeps winning;
 * 2. the statement made for *this* model — [declarationFor], from the per-model
 *    map or the single declaration the configuration carries;
 * 3. the registry — a built-in definition, or whatever was registered for this
 *    exact `providerId` + `modelId`.
 *
 * A declaration is layered onto the registry entry with
 * [ModelCapabilityDeclaration.applyTo] rather than replacing it, so stating one
 * capability never downgrades another, and an unstated capability keeps whatever
 * the registry knows — including [CapabilitySupport.UNKNOWN], which is not
 * support.
 *
 * The statements travel on the configuration itself, so the selected model carries
 * its capability to the eligibility check instead of depending on a registry
 * lookup that only succeeds when some other component remembered to publish it.
 * Each statement belongs to the model it was made for: a statement made for one
 * model never answers for another (see `AgentModelResolver.withModel`).
 */
fun ModelConfig.capabilityProfile(registry: ModelCapabilityRegistry): ModelCapabilityProfile {
    capabilities?.let { override -> return override.toCapabilityProfile(providerId, model) }
    declarationFor(model)?.let { declared ->
        return declared.applyTo(registry.profile(providerId, model))
    }
    return registry.profile(providerId, model)
}
