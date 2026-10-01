package com.agentx.app.agent.model

import com.agentx.app.agent.catalog.AgentCatalog
import com.agentx.app.agent.domain.AgentDefinition
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.domain.PermissionLevel
import com.agentx.app.agent.testConfig
import com.agentx.app.model.ModelConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * The resolver is pure: these tests need no gateway, provider or coroutine.
 * Runtime wiring (a role actually receiving its config) is covered by
 * `AgentRoleModelWiringTest`.
 */
class AgentModelResolverTest {

    /** The active model every role falls back to when its provider is absent. */
    private val active = testConfig()

    private fun connection(providerId: String, model: String) = ModelConfig(
        providerId = providerId,
        baseUrl = "http://localhost:9/v1",
        model = model,
    )

    private fun resolverWith(vararg providers: String): AgentModelResolver {
        val connections = providers.associateWith { provider -> connection(provider, "$provider-model") }
        return AgentModelResolver(
            preferences = AgentModelPreferences.DEFAULT,
            connections = { connections },
        )
    }

    @Test
    fun `main resolves its configured model preference`() {
        val resolved = resolverWith(AgentModelProviders.GEMINI).resolve(AgentCatalog.MAIN, active)
        assertEquals(AgentModelIds.GEMINI, resolved.model)
        assertEquals(AgentModelProviders.GEMINI, resolved.providerId)
    }

    @Test
    fun `explorer resolves independently from main`() {
        val resolver = resolverWith(AgentModelProviders.GEMINI, AgentModelProviders.GROQ)
        val main = resolver.resolve(AgentCatalog.MAIN, active)
        val explorer = resolver.resolve(AgentCatalog.EXPLORER, active)
        assertNotEquals(main, explorer)
        assertEquals(AgentModelIds.GROQ, explorer.model)
        assertEquals(AgentModelProviders.GROQ, explorer.providerId)
    }

    @Test
    fun `researcher resolves independently`() {
        val resolved = resolverWith(AgentModelProviders.GEMINI).resolve(AgentCatalog.RESEARCHER, active)
        assertEquals(AgentModelIds.GEMINI, resolved.model)
    }

    @Test
    fun `coder resolves independently`() {
        val resolved = resolverWith(AgentModelProviders.OPENAI_COMPATIBLE_LOCAL)
            .resolve(AgentCatalog.CODER, active)
        assertEquals(AgentModelIds.QWEN_CODER, resolved.model)
        assertEquals(AgentModelProviders.OPENAI_COMPATIBLE_LOCAL, resolved.providerId)
    }

    @Test
    fun `debugger resolves independently`() {
        val resolved = resolverWith(AgentModelProviders.OPENAI_COMPATIBLE_LOCAL)
            .resolve(AgentCatalog.DEBUGGER, active)
        assertEquals(AgentModelIds.QWEN_CODER, resolved.model)
    }

    @Test
    fun `reviewer resolves independently`() {
        val resolved = resolverWith(AgentModelProviders.GROQ).resolve(AgentCatalog.REVIEWER, active)
        assertEquals(AgentModelIds.GROQ, resolved.model)
        assertEquals(AgentModelProviders.GROQ, resolved.providerId)
    }

    @Test
    fun `tester resolves independently`() {
        val resolved = resolverWith(AgentModelProviders.GROQ).resolve(AgentCatalog.TESTER, active)
        assertEquals(AgentModelIds.GROQ, resolved.model)
    }

    @Test
    fun `two roles resolve different configs simultaneously`() {
        val resolver = resolverWith(AgentModelProviders.OPENAI_COMPATIBLE_LOCAL, AgentModelProviders.GROQ)
        val coder = resolver.resolve(AgentCatalog.CODER, active)
        val tester = resolver.resolve(AgentCatalog.TESTER, active)
        assertNotEquals(coder.providerId, tester.providerId)
        assertNotEquals(coder.model, tester.model)
        assertEquals(AgentModelIds.QWEN_CODER, coder.model)
        assertEquals(AgentModelIds.GROQ, tester.model)
    }

    @Test
    fun `missing role configuration falls back to the active model`() {
        // No role preferences at all.
        val empty = AgentModelResolver()
        assertEquals(active, empty.resolve(AgentCatalog.EXPLORER, active))
        assertEquals(active, empty.resolve(AgentCatalog.CODER, active))

        // Preferences exist, but the provider is not connected.
        val unconnected = AgentModelResolver(AgentModelPreferences.DEFAULT)
        assertEquals(active, unconnected.resolve(AgentCatalog.EXPLORER, active))
        assertEquals(active, unconnected.resolve(AgentCatalog.MAIN, active))
    }

    @Test
    fun `a role whose provider is the active one keeps the active provider`() {
        val preferences = AgentModelPreferences()
            .with(AgentRole.CODER, RoleModelPreference(active.providerId))
        val resolved = AgentModelResolver(preferences).resolve(AgentCatalog.CODER, active)
        assertEquals(active.providerId, resolved.providerId)
        assertEquals(AgentModelIds.QWEN_CODER, resolved.model)
    }

    @Test
    fun `a role preference model is used when no definition preference is supplied`() {
        val preferences = AgentModelPreferences()
            .with(AgentRole.CODER, RoleModelPreference(active.providerId, model = "coder-alt"))
        val resolved = AgentModelResolver(preferences)
            .resolve(AgentRole.CODER, preferredModel = null, default = active)
        assertEquals("coder-alt", resolved.model)
        assertEquals(active.providerId, resolved.providerId)
    }

    @Test
    fun `a role preference model overrides the definition default`() {
        // The role mapping — what Settings writes and the registry overlays — is the
        // user's choice and wins over the agent definition's built-in model.
        val preferences = AgentModelPreferences()
            .with(AgentRole.CODER, RoleModelPreference(active.providerId, model = "coder-alt"))
        val resolved = AgentModelResolver(preferences).resolve(AgentCatalog.CODER, active)
        assertEquals("coder-alt", resolved.model)
    }

    @Test
    fun `existing definitions without a model preference remain valid`() {
        val legacy = AgentDefinition(
            role = AgentRole.CODER,
            name = "Legacy Coder",
            systemInstructions = "Legacy",
            allowedTools = emptyList(),
            permissionLevel = PermissionLevel.READ_ONLY,
            isReadOnly = true,
            maxSteps = 1,
        )
        assertNull(legacy.modelPreference)
        assertEquals(active, AgentModelResolver().resolve(legacy, active))

        val preferences = AgentModelPreferences()
            .with(AgentRole.CODER, RoleModelPreference(active.providerId, model = "legacy-model"))
        assertEquals("legacy-model", AgentModelResolver(preferences).resolve(legacy, active).model)
    }

    @Test
    fun `catalog declares the target models for every role`() {
        assertEquals(AgentModelIds.GEMINI, AgentCatalog.MAIN.modelPreference)
        assertEquals(AgentModelIds.GROQ, AgentCatalog.EXPLORER.modelPreference)
        assertEquals(AgentModelIds.GEMINI, AgentCatalog.RESEARCHER.modelPreference)
        assertEquals(AgentModelIds.QWEN_CODER, AgentCatalog.CODER.modelPreference)
        assertEquals(AgentModelIds.QWEN_CODER, AgentCatalog.DEBUGGER.modelPreference)
        assertEquals(AgentModelIds.GROQ, AgentCatalog.REVIEWER.modelPreference)
        assertEquals(AgentModelIds.GROQ, AgentCatalog.TESTER.modelPreference)
    }
}
