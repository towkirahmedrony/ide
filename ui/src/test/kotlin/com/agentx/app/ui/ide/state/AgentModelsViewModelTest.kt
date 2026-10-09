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
import com.agentx.app.core.ForgeError
import com.agentx.app.core.ForgeErrorCode
import com.agentx.app.core.ForgeResult
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.catalog.CatalogModel
import com.agentx.app.model.catalog.CatalogSource
import com.agentx.app.model.catalog.ModelCatalog
import com.agentx.app.model.catalog.ModelCatalogRegistry
import com.agentx.app.model.catalog.ModelCatalogSnapshot
import com.agentx.app.model.connect.ModelSetupKind
import com.agentx.app.model.manager.ModelManager
import com.agentx.app.model.manager.ModelManagers
import com.agentx.app.model.preset.EndpointConfig
import com.agentx.app.model.preset.EndpointDiscoveryMode
import com.agentx.app.model.preset.InMemoryModelPresetStore
import com.agentx.app.model.preset.ModelApiProtocol
import com.agentx.app.model.preset.ModelPreset
import com.agentx.app.model.preset.ModelProviderType
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
        assertEquals("OpenAI-compatible", main.providerLabel)
        assertEquals(AgentModelIds.DEVSTRAL_24B, main.model)
        // The built-in default is shown; no provider is connected in this test.
        assertEquals(RoleModelState.NOT_CONFIGURED, main.state)

        val coder = vm.rows.first { it.role == AgentRole.CODER }
        assertEquals("OpenAI-compatible", coder.providerLabel)
        assertEquals(AgentModelIds.DEVSTRAL_24B, coder.model)
    }

    @Test
    fun `saving a role updates the registry the resolver reads and persists it`() {
        val store = InMemoryAgentRoleModelStore()
        val registry = AgentRoleModelRegistry(DefaultAgentRoleModelRepository(store))
        val vm = viewModel(registry)

        vm.save(AgentRole.CODER, AgentModelProviders.OPENAI_COMPATIBLE_LOCAL, "qwen2.5-coder-14b", "preset-1")

        val local = config(AgentModelProviders.OPENAI_COMPATIBLE_LOCAL, "qwen2.5-coder-14b")
        val active = config(AgentModelProviders.GEMINI, "gemini-3.5-flash")
        val resolver = AgentModelResolver(
            preferences = AgentModelPreferences.DEFAULT,
            // The saved connection identity ("preset-1") is the map key: a named
            // assignment is addressed by its own identity, exactly as the Model
            // Manager publishes connections (P0-1).
            connections = { mapOf("preset-1" to local) },
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

        // The row reflects the saved assignment — and reports it truthfully. The
        // connection it names ("preset-1") is not one this app can currently address, so
        // the assignment is *stale*, not absent: it must not claim to be connected, and
        // its identity is preserved so the user can repair it deliberately.
        val coder = vm.rows.first { it.role == AgentRole.CODER }
        assertEquals("qwen2.5-coder-14b", coder.model)
        assertEquals(RoleModelState.CONNECTION_MISSING, coder.state)
        assertTrue(coder.state != RoleModelState.CONNECTED)
    }

    // --- live catalog -------------------------------------------------------

    /** A catalog registry whose snapshot the test can change between refreshes. */
    private class FakeCatalogRegistry(var current: ModelCatalogSnapshot?) : ModelCatalogRegistry {
        var refreshes: Int = 0

        override fun providers(): List<String> = listOf(AgentModelProviders.GEMINI)
        override fun catalog(providerId: String): ModelCatalog? = null
        override fun snapshot(providerId: String): ModelCatalogSnapshot? = current
        override fun availableModels(providerId: String): List<CatalogModel> = current?.availableModels().orEmpty()

        override suspend fun refresh(providerId: String, force: Boolean): ForgeResult<ModelCatalogSnapshot, ForgeError> {
            refreshes++
            val snapshot = current
                ?: return ForgeResult.Failure(
                    ForgeError(ForgeErrorCode.MODEL_CATALOG_UNAVAILABLE, "no catalog"),
                )
            return ForgeResult.Success(snapshot)
        }

        override suspend fun refreshAll(force: Boolean): Map<String, ModelCatalogSnapshot> =
            current?.let { mapOf(AgentModelProviders.GEMINI to it) }.orEmpty()

        override suspend fun restore() = Unit
    }

    private fun geminiSnapshot(
        vararg ids: String,
        unavailable: List<String> = emptyList(),
    ) = ModelCatalogSnapshot(
        providerId = AgentModelProviders.GEMINI,
        models = ids.map { CatalogModel(id = it) } + unavailable.map { CatalogModel(id = it, available = false) },
        fetchedAtMillis = 1L,
        source = CatalogSource.REMOTE,
    )

    private fun geminiPreset(modelId: String = "gemini-3.5-flash") = ModelPreset(
        id = "gemini-preset",
        displayName = "Gemini",
        providerType = ModelProviderType.REMOTE_OPENAI_COMPATIBLE,
        modelIdentifier = modelId,
        apiProtocol = ModelApiProtocol.GEMINI_NATIVE,
        apiBasePath = "/v1beta",
        endpoint = EndpointConfig(EndpointDiscoveryMode.CONFIGURED_ENDPOINT, "https://generativelanguage.googleapis.com"),
        setupKind = ModelSetupKind.GEMINI.id,
    )

    /** A manager with one saved Gemini preset; nothing is connected. */
    private fun managerWithGemini(): ModelManager = ModelManagers.create(
        presetStore = InMemoryModelPresetStore(listOf(geminiPreset())),
        monitorEnabled = false,
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun viewModelWithCatalog(catalog: ModelCatalogRegistry): AgentModelsViewModel = AgentModelsViewModel(
        registry = AgentRoleModelRegistry(DefaultAgentRoleModelRepository(InMemoryAgentRoleModelStore())),
        modelManager = managerWithGemini(),
        catalog = catalog,
    )

    @Test
    fun `the live catalog is offered and the built-in fallback list is not`() {
        val vm = viewModelWithCatalog(FakeCatalogRegistry(geminiSnapshot("gemini-3.1-flash", "gemini-3.5-flash")))

        vm.refresh()

        val gemini = vm.options.single { it.providerId == AgentModelProviders.GEMINI }
        assertEquals(listOf("gemini-3.1-flash", "gemini-3.5-flash"), gemini.models)
        assertTrue(gemini.models.none { it == "gemini-3.5-flash-lite" })
    }

    @Test
    fun `a saved model the live catalog dropped is reported unavailable`() {
        val vm = viewModelWithCatalog(
            FakeCatalogRegistry(geminiSnapshot("gemini-3.1-flash", unavailable = listOf("gemini-3.5-flash"))),
        )

        vm.refresh()

        val gemini = vm.options.single { it.providerId == AgentModelProviders.GEMINI }
        assertEquals(listOf("gemini-3.1-flash"), gemini.models)
        assertEquals(listOf("gemini-3.5-flash"), gemini.unavailableModels)
    }

    @Test
    fun `refreshing the catalog updates what the picker offers`() {
        val catalog = FakeCatalogRegistry(geminiSnapshot("gemini-3.5-flash"))
        val vm = viewModelWithCatalog(catalog)
        vm.refresh()
        assertEquals(
            listOf("gemini-3.5-flash"),
            vm.options.single { it.providerId == AgentModelProviders.GEMINI }.models,
        )

        catalog.current = geminiSnapshot("gemini-3.1-flash", "gemini-3.5-flash")
        vm.refreshCatalog()

        assertEquals(
            listOf("gemini-3.1-flash", "gemini-3.5-flash"),
            vm.options.single { it.providerId == AgentModelProviders.GEMINI }.models,
        )
    }

    @Test
    fun `resetting a role restores the built-in default`() {
        val registry = AgentRoleModelRegistry(DefaultAgentRoleModelRepository(InMemoryAgentRoleModelStore()))
        val vm = viewModel(registry)

        vm.save(AgentRole.MAIN, AgentModelProviders.GROQ, "llama-3.1-8b-instant", null)
        assertEquals("llama-3.1-8b-instant", vm.rows.first { it.role == AgentRole.MAIN }.model)

        vm.reset(AgentRole.MAIN)
        assertEquals(AgentModelIds.DEVSTRAL_24B, vm.rows.first { it.role == AgentRole.MAIN }.model)
        assertEquals("OpenAI-compatible", vm.rows.first { it.role == AgentRole.MAIN }.providerLabel)
    }

    /**
     * The screen's own save, with the statement the switch produces. This is the producer
     * the runtime was missing: [AgentModelsViewModel.save] always accepted the statement,
     * but nothing on the screen ever passed it, so a role could not be told that the model
     * it was assigned calls tools — and `declared=false` was permanent.
     */
    @Test
    fun `a statement made for a role's model is saved and reported back`() {
        val store = InMemoryAgentRoleModelStore()
        val registry = AgentRoleModelRegistry(DefaultAgentRoleModelRepository(store))
        val vm = viewModel(registry)

        vm.save(
            role = AgentRole.MAIN,
            providerId = AgentModelProviders.FREELMAPI,
            model = "gemini-3.5-flash-lite",
            connectionId = "gateway-preset",
            declaresToolCalling = true,
        )

        // The exact persisted value, read back from the store the app reloads at startup.
        assertEquals(true, registry.override(AgentRole.MAIN)?.declaresToolCalling)
        assertTrue(vm.rows.first { it.role == AgentRole.MAIN }.declaresToolCalling)

        // Reopening the screen reloads from the same store and still reports it, so the
        // editor does not open showing "off" and withdraw a statement the user made.
        val reopened = viewModel(AgentRoleModelRegistry(DefaultAgentRoleModelRepository(store)))
        reopened.refresh()
        assertTrue(reopened.rows.first { it.role == AgentRole.MAIN }.declaresToolCalling)
    }

    @Test
    fun `switching the assigned model does not carry the statement to the new model`() {
        val store = InMemoryAgentRoleModelStore()
        val registry = AgentRoleModelRegistry(DefaultAgentRoleModelRepository(store))
        val vm = viewModel(registry)

        vm.save(AgentRole.MAIN, AgentModelProviders.FREELMAPI, "gemini-3.5-flash-lite", "gateway-preset", true)
        // The user picks another model of the same gateway; the switch is off for it.
        vm.save(AgentRole.MAIN, AgentModelProviders.FREELMAPI, "glm-5.3", "gateway-preset", false)

        assertEquals("glm-5.3", registry.override(AgentRole.MAIN)?.model)
        assertEquals(false, registry.override(AgentRole.MAIN)?.declaresToolCalling)
        assertFalse(vm.rows.first { it.role == AgentRole.MAIN }.declaresToolCalling)
    }
}
