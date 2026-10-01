package com.agentx.app.agent.model

import com.agentx.app.agent.domain.AgentRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The role evaluation consumes the catalog-backed [ProviderModelOption]. A model
 * the provider no longer lists must surface as unavailable so a saved role
 * assignment is reported honestly instead of being quietly swapped.
 */
class RoleModelCatalogEvaluationTest {

    private fun selection(model: String?) = RoleModelSelection(
        role = AgentRole.EXPLORER,
        providerId = "groq",
        model = model,
        connectionId = "preset-1",
        explicit = true,
    )

    @Test
    fun `an available catalog model is connected`() {
        val option = ProviderModelOption(
            providerId = "groq",
            providerLabel = "Groq",
            models = listOf("llama-3.3-70b-versatile"),
            connected = true,
        )

        val status = RoleModelEvaluation.evaluate(selection("llama-3.3-70b-versatile"), listOf(option))

        assertEquals(RoleModelState.CONNECTED, status.state)
    }

    @Test
    fun `a model the catalog removed is marked unavailable`() {
        val option = ProviderModelOption(
            providerId = "groq",
            providerLabel = "Groq",
            models = listOf("llama-3.3-70b-versatile"),
            unavailableModels = listOf("llama-3.1-8b-instant"),
            connected = true,
        )

        val status = RoleModelEvaluation.evaluate(selection("llama-3.1-8b-instant"), listOf(option))

        assertEquals(RoleModelState.MODEL_UNAVAILABLE, status.state)
        assertTrue(status.message.contains("no longer lists"), status.message)
    }

    @Test
    fun `an unconnected provider is reported as not configured`() {
        val option = ProviderModelOption(
            providerId = "groq",
            providerLabel = "Groq",
            models = listOf("llama-3.3-70b-versatile"),
            connected = false,
        )

        val status = RoleModelEvaluation.evaluate(selection("llama-3.3-70b-versatile"), listOf(option))

        assertEquals(RoleModelState.NOT_CONFIGURED, status.state)
    }
}
