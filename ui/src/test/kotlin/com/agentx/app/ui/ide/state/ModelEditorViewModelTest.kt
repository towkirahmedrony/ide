package com.agentx.app.ui.ide.state

import com.agentx.app.core.ForgeError
import com.agentx.app.core.ForgeErrorCode
import com.agentx.app.core.ForgeResult
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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Add/Edit model: what the picker offers must be the provider's live catalog. The
 * built-in compatibility list fills in only when discovery cannot answer, and a
 * failed refresh must never destroy the list the picker already had.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelEditorViewModelTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Catalog registry whose snapshot and refresh outcome the test controls. */
    private class FakeCatalogRegistry(
        var current: ModelCatalogSnapshot?,
        var result: ForgeResult<ModelCatalogSnapshot, ForgeError>,
    ) : ModelCatalogRegistry {
        var refreshes: Int = 0

        override fun providers(): List<String> = listOf("gemini")
        override fun catalog(providerId: String): ModelCatalog? = null
        override fun snapshot(providerId: String): ModelCatalogSnapshot? = current
        override fun availableModels(providerId: String): List<CatalogModel> = current?.availableModels().orEmpty()

        override suspend fun refresh(providerId: String, force: Boolean): ForgeResult<ModelCatalogSnapshot, ForgeError> {
            refreshes++
            return result
        }

        override suspend fun refreshAll(force: Boolean): Map<String, ModelCatalogSnapshot> =
            current?.let { mapOf("gemini" to it) }.orEmpty()

        override suspend fun restore() = Unit
    }

    private fun snapshot(vararg ids: String) = ModelCatalogSnapshot(
        providerId = "gemini",
        models = ids.map { CatalogModel(id = it) },
        fetchedAtMillis = 1L,
        source = CatalogSource.REMOTE,
    )

    private fun geminiPreset(modelId: String) = ModelPreset(
        id = "gemini-preset",
        displayName = "Gemini",
        providerType = ModelProviderType.REMOTE_OPENAI_COMPATIBLE,
        modelIdentifier = modelId,
        apiProtocol = ModelApiProtocol.GEMINI_NATIVE,
        apiBasePath = "/v1beta",
        endpoint = EndpointConfig(EndpointDiscoveryMode.CONFIGURED_ENDPOINT, "https://generativelanguage.googleapis.com"),
        setupKind = ModelSetupKind.GEMINI.id,
    )

    private fun manager(presets: List<ModelPreset> = emptyList()): ModelManager = ModelManagers.create(
        presetStore = InMemoryModelPresetStore(presets),
        monitorEnabled = false,
        ioDispatcher = Dispatchers.Unconfined,
    )

    /** A new model form switched to the Gemini API, so the picker loads its list. */
    private fun newGeminiForm(registry: FakeCatalogRegistry): ModelEditorViewModel {
        val viewModel = ModelEditorViewModel(
            manager = manager(),
            presetId = null,
            catalog = registry,
        )
        viewModel.selectConnectionType(ModelConnectionType.API)
        return viewModel
    }

    @Test
    fun `a successful gemini discovery fills the picker with the live catalog`() {
        val live = snapshot("gemini-3.1-flash", "gemini-3.5-flash")
        val viewModel = newGeminiForm(FakeCatalogRegistry(live, ForgeResult.Success(live)))

        assertEquals(listOf("gemini-3.5-flash", "gemini-3.1-flash"), viewModel.state.models.map { it.id })
        assertTrue(viewModel.state.modelsFromCatalog)
        // The built-in compatibility list is never mixed into a live catalog.
        assertTrue(viewModel.state.models.none { it.id == "gemini-3.5-flash-lite" })
    }

    @Test
    fun `a failed refresh keeps the list the picker already had`() {
        val live = snapshot("gemini-3.1-flash")
        val registry = FakeCatalogRegistry(live, ForgeResult.Success(live))
        val viewModel = newGeminiForm(registry)
        assertEquals(listOf("gemini-3.1-flash"), viewModel.state.models.map { it.id })

        // The provider is later unreachable: the last live list survives.
        registry.result = ForgeResult.Failure(
            ForgeError(ForgeErrorCode.MODEL_CATALOG_UNAVAILABLE, "the provider is offline"),
        )
        viewModel.retryCatalog()

        assertEquals(listOf("gemini-3.1-flash"), viewModel.state.models.map { it.id })
        assertTrue(viewModel.state.modelsFromCatalog)
        assertEquals(2, registry.refreshes)
    }

    @Test
    fun `refreshing the provider updates the picker without a restart`() {
        val first = snapshot("gemini-3.5-flash")
        val registry = FakeCatalogRegistry(first, ForgeResult.Success(first))
        val viewModel = newGeminiForm(registry)
        assertEquals(listOf("gemini-3.5-flash"), viewModel.state.models.map { it.id })

        val second = snapshot("gemini-3.1-flash", "gemini-3.5-flash")
        registry.current = second
        registry.result = ForgeResult.Success(second)
        viewModel.retryCatalog()

        assertEquals(listOf("gemini-3.5-flash", "gemini-3.1-flash"), viewModel.state.models.map { it.id })
    }

    @Test
    fun `a saved model the provider dropped is replaced from the live catalog`() {
        val live = snapshot("gemini-3.1-flash", "gemini-3.5-flash")
        val registry = FakeCatalogRegistry(live, ForgeResult.Success(live))

        val viewModel = ModelEditorViewModel(
            manager = manager(listOf(geminiPreset("gemini-legacy-flash"))),
            presetId = "gemini-preset",
            catalog = registry,
        )

        // The catalog's preferred model is present, so the dropped saved model is
        // re-pointed at one the provider still lists.
        assertEquals("gemini-3.5-flash", viewModel.state.form.modelId)
        assertTrue(viewModel.state.models.none { it.id == "gemini-legacy-flash" })
    }
}
