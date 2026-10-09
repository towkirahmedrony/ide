package com.agentx.app.agent.model

import com.agentx.app.model.ModelConfig
import com.agentx.app.model.capability.ModelCapabilityDeclaration
import com.agentx.app.model.capability.statedCapabilitiesFor
import com.agentx.app.model.preset.stating

/**
 * [config] carrying the user's own statement that [modelId] calls tools, or [config]
 * unchanged when the connection already states something about that model.
 *
 * This is the one rule that turns a role assignment's declaration
 * ([RoleModelConfig.declaresToolCalling], reaching the runtime as
 * [RoleModelPreference.declaresToolCalling]) into something the eligibility check can
 * read. Settings and the runtime both go through it, so the screen judging an assignment
 * and the resolver running it cannot disagree about whether a declared model can run.
 *
 * The connection's own statement always wins: a statement saved against the connection for
 * this exact model is the user's considered answer about that connection and is left
 * exactly as it is. Only when the connection says nothing about this model does the
 * assignment's statement apply.
 *
 * It is scoped to the one model it was made for — recorded in
 * [ModelConfig.statedCapabilitiesByModel] under that model's id, and as
 * [ModelConfig.declaredCapabilitiesModel] — so it is never inherited by another model of
 * the same connection, by another connection, or by a later rebuild. Nothing is claimed
 * beyond what the user stated: a discovered model is never marked capable on its own.
 *
 * Pure: it records the statement on the configuration and nothing else, so a caller that
 * only *judges* an assignment (Settings) cannot change what another consumer believes. A
 * caller that owns the shared capability registry publishes it there as well, so every
 * consumer resolving this `(providerId, model)` — notably the gateway, which must know to
 * send tools — sees the same statement (see `AgentModelResolver.withAssignedStatement`).
 */
fun ModelConfig.statingToolCalling(modelId: String): ModelConfig {
    if (modelId.isBlank()) return this
    // A statement already in effect for this model is the connection's own: keep it.
    if (statedCapabilitiesFor(modelId) != null) return this
    val statement = ModelCapabilityDeclaration.toolEnabledEndpoint()
    return copy(
        declaredCapabilities = statement,
        declaredCapabilitiesModel = modelId,
        statedCapabilitiesByModel = statedCapabilitiesByModel.stating(modelId, statement),
    )
}
