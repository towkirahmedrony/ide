package com.agentx.app.agent.model

import com.agentx.app.agent.catalog.AgentCatalog
import com.agentx.app.agent.domain.AgentDefinition
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.domain.PermissionLevel
import com.agentx.app.agent.testConfig
import com.agentx.app.agent.testDomain
import com.agentx.app.model.ModelConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    /** A connection of [providerId] in that provider family's execution domain. */
    private fun connection(providerId: String, model: String) = ModelConfig(
        providerId = providerId,
        baseUrl = "http://localhost:9/v1",
        model = model,
        connectionKind = testDomain(providerId),
    )

    private fun resolverWith(vararg providers: String): AgentModelResolver {
        val connections = providers.associateWith { provider -> connection(provider, "$provider-model") }
        return AgentModelResolver(
            preferences = AgentModelPreferences.DEFAULT,
            connections = { connections },
        )
    }

    @Test
    fun `main resolves to the local model connection`() {
        val resolved = resolverWith(AgentModelProviders.OPENAI_COMPATIBLE_LOCAL)
            .resolve(AgentCatalog.MAIN, active)
        assertEquals(AgentModelIds.DEVSTRAL_24B, resolved.model)
        assertEquals(AgentModelProviders.OPENAI_COMPATIBLE_LOCAL, resolved.providerId)
    }

    @Test
    fun `coder and debugger resolve to the local model connection`() {
        val resolver = resolverWith(AgentModelProviders.OPENAI_COMPATIBLE_LOCAL)
        assertEquals(AgentModelIds.DEVSTRAL_24B, resolver.resolve(AgentCatalog.CODER, active).model)
        assertEquals(AgentModelIds.DEVSTRAL_24B, resolver.resolve(AgentCatalog.DEBUGGER, active).model)
        assertEquals(
            AgentModelProviders.OPENAI_COMPATIBLE_LOCAL,
            resolver.resolve(AgentCatalog.CODER, active).providerId,
        )
    }

    @Test
    fun `api roles resolve to the FreeLLMAPI connection`() {
        val resolver = resolverWith(AgentModelProviders.FREELMAPI)
        val reviewer = resolver.resolve(AgentCatalog.REVIEWER, active)
        val explorer = resolver.resolve(AgentCatalog.EXPLORER, active)
        val researcher = resolver.resolve(AgentCatalog.RESEARCHER, active)
        assertEquals(AgentModelProviders.FREELMAPI, reviewer.providerId)
        assertEquals(AgentModelIds.FREELLMAPI_GEMINI, reviewer.model)
        assertEquals(AgentModelProviders.FREELMAPI, explorer.providerId)
        assertEquals(AgentModelIds.FREELLMAPI_GROQ, explorer.model)
        assertEquals(AgentModelProviders.FREELMAPI, researcher.providerId)
    }

    @Test
    fun `tester resolves to Groq`() {
        val resolved = resolverWith(AgentModelProviders.GROQ).resolve(AgentCatalog.TESTER, active)
        assertEquals(AgentModelIds.GROQ, resolved.model)
        assertEquals(AgentModelProviders.GROQ, resolved.providerId)
    }

    @Test
    fun `adding an api connection never moves a local role off the local model`() {
        // Both domains are connected at once. MAIN/CODER/DEBUGGER are bound to the
        // local domain, so the presence of the API connection must not change them.
        val resolver = resolverWith(AgentModelProviders.OPENAI_COMPATIBLE_LOCAL, AgentModelProviders.FREELMAPI)
        assertEquals(AgentModelProviders.OPENAI_COMPATIBLE_LOCAL, resolver.resolve(AgentCatalog.MAIN, active).providerId)
        assertEquals(AgentModelProviders.OPENAI_COMPATIBLE_LOCAL, resolver.resolve(AgentCatalog.CODER, active).providerId)
        assertEquals(AgentModelProviders.OPENAI_COMPATIBLE_LOCAL, resolver.resolve(AgentCatalog.DEBUGGER, active).providerId)
    }

    @Test
    fun `a local role never picks up an api connection of another family`() {
        // Only the API gateway is connected; MAIN is bound to LOCAL and must fail
        // rather than silently move onto the remote model.
        val apiOnly = resolverWith(AgentModelProviders.FREELMAPI)
        assertFailsWith<AgentModelResolutionException> { apiOnly.resolve(AgentCatalog.MAIN, active) }
        assertFailsWith<AgentModelResolutionException> { apiOnly.resolve(AgentCatalog.CODER, active) }
    }

    @Test
    fun `an api role never picks up a local connection`() {
        val localOnly = resolverWith(AgentModelProviders.OPENAI_COMPATIBLE_LOCAL)
        assertFailsWith<AgentModelResolutionException> { localOnly.resolve(AgentCatalog.REVIEWER, active) }
    }

    @Test
    fun `two roles resolve different configs simultaneously`() {
        val resolver = resolverWith(AgentModelProviders.OPENAI_COMPATIBLE_LOCAL, AgentModelProviders.FREELMAPI)
        val coder = resolver.resolve(AgentCatalog.CODER, active)
        val explorer = resolver.resolve(AgentCatalog.EXPLORER, active)
        assertNotEquals(coder.providerId, explorer.providerId)
        assertNotEquals(coder.model, explorer.model)
        assertEquals(AgentModelIds.DEVSTRAL_24B, coder.model)
        assertEquals(AgentModelIds.FREELLMAPI_GROQ, explorer.model)
    }

    @Test
    fun `missing role configuration falls back to the active model`() {
        // No role preferences at all: the resolver is transparent.
        val empty = AgentModelResolver()
        assertEquals(active, empty.resolve(AgentCatalog.EXPLORER, active))
        assertEquals(active, empty.resolve(AgentCatalog.CODER, active))
    }

    @Test
    fun `a role whose provider is the active one keeps the active provider`() {
        val preferences = AgentModelPreferences()
            .with(AgentRole.CODER, RoleModelPreference(active.providerId))
        val resolved = AgentModelResolver(preferences).resolve(AgentCatalog.CODER, active)
        assertEquals(active.providerId, resolved.providerId)
        assertEquals(AgentModelIds.DEVSTRAL_24B, resolved.model)
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
        assertEquals(AgentModelIds.DEVSTRAL_24B, AgentCatalog.MAIN.modelPreference)
        assertEquals(AgentModelIds.FREELLMAPI_GROQ, AgentCatalog.EXPLORER.modelPreference)
        assertEquals(AgentModelIds.FREELLMAPI_GEMINI, AgentCatalog.RESEARCHER.modelPreference)
        assertEquals(AgentModelIds.DEVSTRAL_24B, AgentCatalog.CODER.modelPreference)
        assertEquals(AgentModelIds.DEVSTRAL_24B, AgentCatalog.DEBUGGER.modelPreference)
        assertEquals(AgentModelIds.FREELLMAPI_GEMINI, AgentCatalog.REVIEWER.modelPreference)
        assertEquals(AgentModelIds.GROQ, AgentCatalog.TESTER.modelPreference)
    }
}
