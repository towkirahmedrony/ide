package com.agentx.app.model.capability

import com.agentx.app.model.ModelConfig

/**
 * The capability profile in effect for this configuration.
 *
 * One function decides this so the eligibility checker, the role resolver and the
 * gateway can never disagree about what a model is believed to do. Precedence:
 *
 * 1. [ModelConfig.capabilities] — the blunt boolean override, which has always
 *    won and keeps winning;
 * 2. [ModelConfig.declaredCapabilities] — the tri-state statement saved with this
 *    model's configuration;
 * 3. the registry — a built-in definition, or whatever was registered for this
 *    exact `providerId` + `modelId`.
 *
 * A declaration is layered onto the registry entry with
 * [ModelCapabilityDeclaration.applyTo] rather than replacing it, so stating one
 * capability never downgrades another, and an unstated capability keeps whatever
 * the registry knows — including [CapabilitySupport.UNKNOWN], which is not
 * support.
 *
 * The declaration travels on the configuration itself, so the selected model
 * carries its capability to the eligibility check instead of depending on a
 * registry lookup that only succeeds when some other component remembered to
 * publish it. It belongs to the model this configuration names: a caller that
 * changes [ModelConfig.model] must drop it (see `AgentModelResolver.withModel`),
 * or one declared model would lend its capability to another.
 */
fun ModelConfig.capabilityProfile(registry: ModelCapabilityRegistry): ModelCapabilityProfile {
    capabilities?.let { override -> return override.toCapabilityProfile(providerId, model) }
    declaredCapabilities?.takeIf { !it.isEmpty }?.let { declared ->
        return declared.applyTo(registry.profile(providerId, model))
    }
    return registry.profile(providerId, model)
}
