package com.agentx.app.agent.model

import com.agentx.app.agent.catalog.AgentCatalog
import com.agentx.app.agent.domain.AgentErrorCode
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.testConfig
import com.agentx.app.agent.testDomain
import com.agentx.app.model.ModelCapabilities
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.capability.ModelCapability
import com.agentx.app.model.capability.ModelCapabilityErrors
import com.agentx.app.model.preset.ModelProviderIds
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
    fun `every role requires tool calling and streaming`() {
        AgentRole.entries.forEach { role ->
            val required = AgentRoleRequirements.required(role)
            assertTrue(ModelCapability.TOOL_CALLING in required, role.name)
            assertTrue(ModelCapability.STREAMING in required, role.name)
        }
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
        val result = resolver.validate(AgentRole.EXPLORER, unknown)
        assertTrue(result.isFailure)
        val error = assertFailsWith<AgentModelResolutionException> {
            resolver.resolveChecked(AgentRole.EXPLORER, unknown)
        }.error
        assertEquals(AgentErrorCode.MODEL_CAPABILITY_UNSUPPORTED, error.code)
        assertEquals(AgentRole.EXPLORER, error.role)
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
    fun `an explicit capability override is required to use an otherwise unknown model`() {
        val override = config(
            ModelProviderIds.GROQ,
            "allam-2-7b",
            capabilities = ModelCapabilities(toolCalling = true, streaming = true),
        )
        assertTrue(resolver.canSatisfy(AgentRole.REVIEWER, override))
        assertEquals(override, resolver.resolveChecked(AgentRole.REVIEWER, override))
    }
}
