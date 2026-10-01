package com.agentx.app.ui.ide.state

import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.model.AgentModelIds
import com.agentx.app.agent.model.AgentModelPreferences
import com.agentx.app.agent.model.AgentModelProviders
import com.agentx.app.agent.model.AgentModelResolver
import com.agentx.app.agent.model.AgentRoleModelRegistry
import com.agentx.app.agent.model.DefaultAgentRoleModelRepository
import com.agentx.app.agent.model.InMemoryAgentRoleModelStore
import com.agentx.app.agent.model.RoleModelState
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.manager.ModelManagers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Settings → Agent Models writes the same [AgentRoleModelRegistry] the Agent
 * Core's model resolver consumes, so a change made on the screen reaches the
 * model actually selected at run time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentModelsViewModelTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(registry: AgentRoleModelRegistry) = AgentModelsViewModel(
        registry = registry,
        modelManager = ModelManagers.create(monitorEnabled = false),
    )

    private fun config(providerId: String, model: String) = ModelConfig(
        providerId = providerId,
        baseUrl = "https://$providerId.example/v1",
        model = model,
    )

    @Test
    fun `the screen shows the built-in role mapping`() {
        val vm = viewModel(AgentRoleModelRegistry(DefaultAgentRoleModelRepository(InMemoryAgentRoleModelStore())))

        assertEquals(AgentRole.entries.size, vm.rows.size)
        val main = vm.rows.first { it.role == AgentRole.MAIN }
        assertEquals("Gemini", main.providerLabel)
        assertEquals(AgentModelIds.GEMINI, main.model)
        // The built-in default is shown; no provider is connected in this test.
        assertEquals(RoleModelState.NOT_CONFIGURED, main.state)

        val coder = vm.rows.first { it.role == AgentRole.CODER }
        assertEquals("OpenAI-compatible", coder.providerLabel)
        assertEquals(AgentModelIds.QWEN_CODER, coder.model)
    }

    @Test
    fun `saving a role updates the registry the resolver reads and persists it`() {
        val store = InMemoryAgentRoleModelStore()
        val registry = AgentRoleModelRegistry(DefaultAgentRoleModelRepository(store))
        val vm = viewModel(registry)

        vm.save(AgentRole.CODER, AgentModelProviders.OPENAI_COMPATIBLE_LOCAL, "qwen2.5-coder-14b", "preset-1")

        val local = config(AgentModelProviders.OPENAI_COMPATIBLE_LOCAL, "qwen2.5-coder-14b")
        val active = config(AgentModelProviders.GEMINI, "gemini-2.0-flash")
        val resolver = AgentModelResolver(
            preferences = AgentModelPreferences.DEFAULT,
            connections = { mapOf(AgentModelProviders.OPENAI_COMPATIBLE_LOCAL to local) },
            livePreferences = { registry.preferences() },
        )
        assertEquals("qwen2.5-coder-14b", resolver.resolve(AgentRole.CODER, active).model)
        assertEquals(
            AgentModelProviders.OPENAI_COMPATIBLE_LOCAL,
            resolver.resolve(AgentRole.CODER, active).providerId,
        )

        // Persisted, so it survives an app restart.
        val saved = runBlocking { store.loadAll().first { it.role == AgentRole.CODER } }
        assertEquals("qwen2.5-coder-14b", saved.model)
        assertEquals("preset-1", saved.connectionId)

        // The row reflects the saved assignment.
        val coder = vm.rows.first { it.role == AgentRole.CODER }
        assertEquals("qwen2.5-coder-14b", coder.model)
        assertEquals(RoleModelState.NOT_CONFIGURED, coder.state)
    }

    @Test
    fun `resetting a role restores the built-in default`() {
        val registry = AgentRoleModelRegistry(DefaultAgentRoleModelRepository(InMemoryAgentRoleModelStore()))
        val vm = viewModel(registry)

        vm.save(AgentRole.MAIN, AgentModelProviders.GROQ, "llama-3.1-8b-instant", null)
        assertEquals("llama-3.1-8b-instant", vm.rows.first { it.role == AgentRole.MAIN }.model)

        vm.reset(AgentRole.MAIN)
        assertEquals(AgentModelIds.GEMINI, vm.rows.first { it.role == AgentRole.MAIN }.model)
        assertEquals("Gemini", vm.rows.first { it.role == AgentRole.MAIN }.providerLabel)
    }
}
