package com.agentx.app.agent.model

import com.agentx.app.agent.catalog.AgentCatalog
import com.agentx.app.agent.domain.AgentErrorCode
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.testConfig
import com.agentx.app.agent.testDomain
import com.agentx.app.model.ModelCapabilities
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.capability.CapabilitySupport
import com.agentx.app.model.capability.InMemoryModelCapabilityRegistry
import com.agentx.app.model.capability.ModelCapability
import com.agentx.app.model.capability.ModelCapabilityErrors
import com.agentx.app.model.preset.ModelProviderIds
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentRoleCapabilityValidationTest {

    private val resolver = AgentModelResolver(AgentModelPreferences.EMPTY)

    private fun config(
        providerId: String,
        model: String,
        capabilities: ModelCapabilities? = null,
        baseUrl: String = "https://$providerId.example/v1",
    ) = ModelConfig(
        providerId = providerId,
        baseUrl = baseUrl,
        model = model,
        capabilities = capabilities,
        connectionKind = testDomain(providerId),
    )

    @Test
    fun `only the roles that act on the workspace require tool calling`() {
        // Action roles mutate the workspace or repository, so they genuinely need
        // tool calling; the local Devstral roles are among them.
        val actionRoles = setOf(
            AgentRole.MAIN,
            AgentRole.CODER,
            AgentRole.DEBUGGER,
            AgentRole.TESTER,
            AgentRole.FAST_CODER,
            AgentRole.DOCS,
            AgentRole.COMMIT_PR,
        )
        actionRoles.forEach { role ->
            val required = AgentRoleRequirements.required(role)
            assertTrue(ModelCapability.TOOL_CALLING in required, role.name)
            assertTrue(ModelCapability.STREAMING in required, role.name)
            assertTrue(AgentRoleRequirements.requiresToolCalling(role), role.name)
        }

        // Analysis roles answer through an ordinary model response and must not be
        // rejected for an unverified tool capability.
        val analysisRoles = setOf(
            AgentRole.EXPLORER,
            AgentRole.RESEARCHER,
            AgentRole.REVIEWER,
            AgentRole.PLANNER,
            AgentRole.SECURITY_REVIEWER,
        )
        analysisRoles.forEach { role ->
            val required = AgentRoleRequirements.required(role)
            assertFalse(ModelCapability.TOOL_CALLING in required, role.name)
            assertTrue(ModelCapability.TEXT_GENERATION in required, role.name)
            assertFalse(AgentRoleRequirements.requiresToolCalling(role), role.name)
        }

        // Every role is classified as exactly one of the two.
        assertEquals(AgentRole.entries.size, actionRoles.size + analysisRoles.size)
    }

    @Test
    fun `an analysis role is eligible on a remote model whose tool capability is unknown`() = runBlocking {
        // The gateway declares no tool calling for its models. A Reviewer only needs
        // text generation, so it still resolves.
        val unknownTools = config(ModelProviderIds.FREELMAPI, "some-new-gateway-model")
        val result = AgentModelResolver().resolveForRole(AgentRole.REVIEWER, unknownTools)

        assertTrue(result.eligible, result.errorOrNull()?.message.orEmpty())
        assertFalse(result.eligibility.profile.supports(ModelCapability.TOOL_CALLING))
    }

    @Test
    fun `a known tool capable model satisfies a tool enabled role`() {
        val selected = config(ModelProviderIds.GEMINI, "gemini-3.5-flash")
        assertTrue(resolver.canSatisfy(AgentRole.MAIN, selected))
        assertTrue(resolver.validate(AgentRole.CODER, config(ModelProviderIds.OPENAI_COMPATIBLE, "qwen2.5-coder-14b")).isSuccess)
        assertTrue(resolver.canSatisfy(AgentRole.EXPLORER, config(ModelProviderIds.GROQ, "llama-3.3-70b-versatile")))
    }

    @Test
    fun `an unknown model cannot be selected for a tool enabled role`() {
        val unknown = config(ModelProviderIds.GROQ, "allam-2-7b")
        val result = resolver.validate(AgentRole.CODER, unknown)
        assertTrue(result.isFailure)
        val error = assertFailsWith<AgentModelResolutionException> {
            resolver.resolveChecked(AgentRole.CODER, unknown)
        }.error
        assertEquals(AgentErrorCode.MODEL_CAPABILITY_UNSUPPORTED, error.code)
        assertEquals(AgentRole.CODER, error.role)
        assertTrue(error.message.contains(ModelCapabilityErrors.CODE))
        assertTrue(error.message.contains("allam-2-7b"))
        assertTrue(error.message.contains("toolCalling"))
        assertEquals("allam-2-7b", error.details["model"])
        assertEquals("false", error.details["known"])
        assertFalse(resolver.canSatisfy(AgentRole.MAIN, unknown))
    }

    @Test
    fun `resolve still falls back without validating capabilities`() {
        val unknown = config(ModelProviderIds.GROQ, "allam-2-7b")
        val resolved = resolver.resolve(AgentRole.MAIN, unknown)
        assertEquals(unknown, resolved)
    }

    @Test
    fun `existing resolver selection remains compatible`() {
        val connections = mapOf(
            AgentModelProviders.OPENAI_COMPATIBLE to config(
                AgentModelProviders.OPENAI_COMPATIBLE,
                AgentModelIds.DEVSTRAL_24B,
                baseUrl = "http://localhost:11434/v1",
            ),
            AgentModelProviders.FREELMAPI to config(
                AgentModelProviders.FREELMAPI,
                AgentModelIds.FREELLMAPI_GROQ,
            ),
            AgentModelProviders.GROQ to config(AgentModelProviders.GROQ, "llama-3.3-70b-versatile"),
        )
        val live = AgentModelResolver(
            preferences = AgentModelPreferences.DEFAULT,
            connections = { connections },
        )
        val active = config(AgentModelProviders.GROQ, "llama-3.3-70b-versatile")
        // Local roles stay on the local model; the API role lands on the API gateway.
        assertEquals(AgentModelIds.DEVSTRAL_24B, live.resolve(AgentCatalog.MAIN, active).model)
        assertEquals(AgentModelProviders.OPENAI_COMPATIBLE, live.resolve(AgentCatalog.MAIN, active).providerId)
        assertEquals(AgentModelIds.DEVSTRAL_24B, live.resolve(AgentCatalog.CODER, active).model)
        assertEquals(AgentModelProviders.OPENAI_COMPATIBLE, live.resolve(AgentCatalog.CODER, active).providerId)
        assertEquals(AgentModelIds.FREELLMAPI_GROQ, live.resolve(AgentCatalog.EXPLORER, active).model)
        assertEquals(AgentModelProviders.FREELMAPI, live.resolve(AgentCatalog.EXPLORER, active).providerId)
        assertEquals(active, AgentModelResolver().resolve(AgentCatalog.CODER, active))
        assertEquals(testConfig(), AgentModelResolver().resolve(AgentCatalog.MAIN, testConfig()))
    }

    @Test
    fun `local qwen continues to satisfy coder and debugger`() {
        val local = config(
            AgentModelProviders.OPENAI_COMPATIBLE_LOCAL,
            AgentModelIds.QWEN_CODER,
            baseUrl = "http://localhost:11434/v1",
        )
        assertTrue(resolver.canSatisfy(AgentRole.CODER, local))
        assertTrue(resolver.canSatisfy(AgentRole.DEBUGGER, local))
        assertEquals(local, resolver.resolveChecked(AgentRole.CODER, local))
    }

    @Test
    fun `provider identities stay distinct after resolution`() {
        val connections = mapOf(
            AgentModelProviders.GEMINI to config(AgentModelProviders.GEMINI, "gemini-3.5-flash"),
            AgentModelProviders.GROQ to config(AgentModelProviders.GROQ, "openai/gpt-oss-20b"),
            AgentModelProviders.OPENAI_COMPATIBLE to config(
                AgentModelProviders.OPENAI_COMPATIBLE,
                "devstral-24b",
                baseUrl = "http://127.0.0.1:8080/v1",
            ),
            AgentModelProviders.FREELMAPI to config(AgentModelProviders.FREELMAPI, "gemini-3.5-flash"),
            AgentModelProviders.CEREBRAS to config(AgentModelProviders.CEREBRAS, "cerebras"),
            AgentModelProviders.MISTRAL to config(AgentModelProviders.MISTRAL, "mistral"),
            AgentModelProviders.OPENROUTER to config(AgentModelProviders.OPENROUTER, "openrouter"),
            AgentModelProviders.CLOUDFLARE to config(AgentModelProviders.CLOUDFLARE, "cloudflare"),
            AgentModelProviders.NVIDIA_NIM to config(AgentModelProviders.NVIDIA_NIM, "nvidia-nim"),
        )
        val live = AgentModelResolver(
            preferences = AgentModelPreferences.DEFAULT,
            connections = { connections },
        )
        val active = connections.getValue(AgentModelProviders.GROQ)
        val main = live.resolveChecked(AgentRole.MAIN, active)
        val explorer = live.resolveChecked(AgentRole.EXPLORER, active)
        val tester = live.resolveChecked(AgentRole.TESTER, active)
        assertEquals(AgentModelProviders.OPENAI_COMPATIBLE, main.providerId)
        assertEquals(AgentModelProviders.FREELMAPI, explorer.providerId)
        assertEquals(AgentModelProviders.GROQ, tester.providerId)
        assertTrue(setOf(main.providerId, explorer.providerId, tester.providerId).size == 3)
        assertFalse(live.canSatisfy(AgentRole.MAIN, connections.getValue(AgentModelProviders.CEREBRAS)))
    }

    @Test
    fun `capabilities are scoped to the resolved provider and model identity`() {
        // The same model id under two provider identities is two capability records:
        // the local Devstral definition must not make a gateway model of the same
        // name tool-capable, and the gateway identity must not weaken the local one.
        val registry = InMemoryModelCapabilityRegistry()
        assertEquals(
            CapabilitySupport.SUPPORTED,
            registry.support(ModelProviderIds.OPENAI_COMPATIBLE, "devstral-24b", ModelCapability.TOOL_CALLING),
        )
        assertEquals(
            CapabilitySupport.UNKNOWN,
            registry.support(ModelProviderIds.FREELMAPI, "devstral-24b", ModelCapability.TOOL_CALLING),
        )

        // The local identity still satisfies a tool-requiring role; the gateway
        // identity does not, so no capability leaked across the two connections.
        assertTrue(
            resolver.canSatisfy(
                AgentRole.CODER,
                config(ModelProviderIds.OPENAI_COMPATIBLE, "devstral-24b", baseUrl = "http://localhost:11434/v1"),
            ),
        )
        assertFalse(resolver.canSatisfy(AgentRole.CODER, config(ModelProviderIds.FREELMAPI, "devstral-24b")))
    }

    @Test
    fun `an explicit capability override is required to use an otherwise unknown model`() {
        val override = config(
            ModelProviderIds.GROQ,
            "allam-2-7b",
            capabilities = ModelCapabilities(toolCalling = true, streaming = true),
        )
        assertTrue(resolver.canSatisfy(AgentRole.CODER, override))
        assertEquals(override, resolver.resolveChecked(AgentRole.CODER, override))
    }
}
