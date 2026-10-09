package com.agentx.app.ui.ide.state

import com.agentx.app.core.ForgeError
import com.agentx.app.core.ForgeErrorCode
import com.agentx.app.core.ForgeResult
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.capability.ModelCapabilityDeclaration
import com.agentx.app.model.catalog.CatalogModel
import com.agentx.app.model.catalog.CatalogSource
import com.agentx.app.model.catalog.ModelCatalog
import com.agentx.app.model.catalog.ModelCatalogRegistry
import com.agentx.app.model.catalog.ModelCatalogSnapshot
import com.agentx.app.model.catalog.ModelCatalogState
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    /**
     * Catalog registry whose snapshot, saved-catalog refresh and draft-connection
     * preview outcomes the test controls. A provider the user has not saved has no
     * cached catalog ([current] null), so the picker reaches [preview] instead.
     */
    private class FakeCatalogRegistry(
        var current: ModelCatalogSnapshot?,
        var result: ForgeResult<ModelCatalogSnapshot, ForgeError>,
        var previewResult: ForgeResult<ModelCatalogSnapshot, ForgeError> = ForgeResult.Failure(
            ForgeError(ForgeErrorCode.MODEL_CATALOG_UNAVAILABLE, "no draft catalog"),
        ),
    ) : ModelCatalogRegistry {
        var refreshes: Int = 0
        var previews: Int = 0
        var lastPreviewConnection: ModelConfig? = null

        override fun providers(): List<String> = listOf("gemini")
        override fun catalog(providerId: String): ModelCatalog? = null
        override fun snapshot(providerId: String): ModelCatalogSnapshot? = current
        override fun availableModels(providerId: String): List<CatalogModel> = current?.availableModels().orEmpty()

        override suspend fun refresh(providerId: String, force: Boolean): ForgeResult<ModelCatalogSnapshot, ForgeError> {
            refreshes++
            return result
        }

        override suspend fun preview(
            providerId: String,
            connection: ModelConfig,
        ): ForgeResult<ModelCatalogSnapshot, ForgeError> {
            previews++
            lastPreviewConnection = connection
            return previewResult
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

    private fun freeLlmSnapshot(vararg ids: String) = ModelCatalogSnapshot(
        providerId = "freellmapi",
        models = ids.map { CatalogModel(id = it) },
        fetchedAtMillis = 1L,
        source = CatalogSource.REMOTE,
    )

    private fun notConnected() = ForgeResult.Failure(
        ForgeError(ForgeErrorCode.MODEL_CATALOG_UNAVAILABLE, "not connected"),
    )

    /**
     * A new model form switched to FreeLLMAPI. No connection is saved for it, so
     * the picker has no cached catalog and discovery runs against the draft
     * endpoint the form carries.
     */
    private fun newFreeLlmApiForm(registry: FakeCatalogRegistry): ModelEditorViewModel {
        val viewModel = ModelEditorViewModel(
            manager = manager(),
            presetId = null,
            catalog = registry,
        )
        viewModel.selectConnectionType(ModelConnectionType.API)
        viewModel.selectProvider(ModelSetupKind.FREELLMAPI)
        return viewModel
    }

    @Test
    fun `a successful gemini discovery fills the picker with the live catalog`() {
        val live = snapshot("gemini-3.1-flash", "gemini-3.5-flash")
        val viewModel = newGeminiForm(FakeCatalogRegistry(live, ForgeResult.Success(live)))

        // The picker lists the live catalog in ascending id order.
        assertEquals(listOf("gemini-3.1-flash", "gemini-3.5-flash"), viewModel.state.models.map { it.id })
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

        // The refreshed catalog replaces the old list, again in ascending id order.
        assertEquals(listOf("gemini-3.1-flash", "gemini-3.5-flash"), viewModel.state.models.map { it.id })
    }

    // --- FreeLLMAPI: catalog-driven before the connection is saved ----------

    @Test
    fun `a new FreeLLMAPI connection lists the endpoint catalog before it is saved`() {
        val live = freeLlmSnapshot(
            "gemini-2.5-flash",
            "openai/gpt-oss-20b",
            "deepseek-v4-pro",
            "glm-5.3",
            "brand-new-gateway-model",
        )
        val registry = FakeCatalogRegistry(
            current = null,
            result = notConnected(),
            previewResult = ForgeResult.Success(live),
        )

        val viewModel = newFreeLlmApiForm(registry)

        assertTrue(viewModel.state.modelsFromCatalog)
        // The complete discovered catalog is available, not the four-item preset list.
        assertEquals(
            listOf(
                "brand-new-gateway-model",
                "deepseek-v4-pro",
                "gemini-2.5-flash",
                "glm-5.3",
                "openai/gpt-oss-20b",
            ),
            viewModel.state.models.map { it.id }.sorted(),
        )
        assertTrue(viewModel.state.models.size > 4)
        assertEquals(1, registry.previews)
        // Discovery ran against the form's own endpoint, not a hardcoded address.
        assertEquals("https://agentx-vgtx.onrender.com/v1", registry.lastPreviewConnection?.baseUrl)
        // A live list is never mixed with the built-in compatibility list.
        assertTrue(viewModel.state.models.none { it.id == "llama-3.3-70b-versatile" })
    }

    @Test
    fun `featured FreeLLMAPI candidates appear only when the endpoint returns them`() {
        val live = freeLlmSnapshot("gemini-3.8-flash", "openai/gpt-oss-120b", "some-gateway-model")
        val registry = FakeCatalogRegistry(
            current = null,
            result = notConnected(),
            previewResult = ForgeResult.Success(live),
        )

        val byId = newFreeLlmApiForm(registry).state.models.associateBy { it.id }

        assertTrue(byId.getValue("gemini-3.8-flash").recommended)
        assertTrue(byId.getValue("openai/gpt-oss-120b").recommended)
        assertFalse(byId.getValue("some-gateway-model").recommended)
        // A candidate the endpoint did not return is never fabricated.
        assertNull(byId["glm-5.3"])
        assertNull(byId["deepseek-v4-pro"])
        assertNull(byId["devstral-2"])
    }

    @Test
    fun `a FreeLLMAPI discovery failure shows an error and never presents the built-in list`() {
        val registry = FakeCatalogRegistry(
            current = null,
            result = notConnected(),
            previewResult = ForgeResult.Failure(
                ForgeError(ForgeErrorCode.MODEL_CATALOG_UNAVAILABLE, "The model list endpoint returned HTTP 401."),
            ),
        )

        val viewModel = newFreeLlmApiForm(registry)

        assertNotNull(viewModel.state.catalogError)
        assertTrue(viewModel.state.catalogState is ModelCatalogState.Failed)
        assertFalse(viewModel.state.modelsFromCatalog)
        // The outdated built-in list is not offered as if it were the endpoint's.
        assertTrue(viewModel.state.models.isEmpty())
        assertFalse(viewModel.state.hasModelList)
    }

    @Test
    fun `an empty FreeLLMAPI catalog is an empty state, not the four presets`() {
        val registry = FakeCatalogRegistry(
            current = null,
            result = notConnected(),
            previewResult = ForgeResult.Success(freeLlmSnapshot()),
        )

        val viewModel = newFreeLlmApiForm(registry)

        val state = assertIs<ModelCatalogState.Discovered>(viewModel.state.catalogState)
        assertEquals(0, state.modelCount)
        assertFalse(viewModel.state.modelsFromCatalog)
        assertTrue(viewModel.state.models.isEmpty())
        assertNull(viewModel.state.catalogError)
    }

    @Test
    fun `refreshing the FreeLLMAPI catalog keeps the selected model`() {
        val live = freeLlmSnapshot("gemini-3.8-flash", "deepseek-v4-pro")
        val registry = FakeCatalogRegistry(
            current = null,
            result = notConnected(),
            previewResult = ForgeResult.Success(live),
        )
        val viewModel = newFreeLlmApiForm(registry)
        viewModel.selectModel("deepseek-v4-pro")

        viewModel.retryCatalog()

        // The exact selected id survives a refresh that still lists it, and is never
        // switched to a default or another model.
        assertEquals("deepseek-v4-pro", viewModel.state.form.modelId)
        assertEquals(2, registry.previews)
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

    // --- a manually entered id, and what the pre-save preview runs against ----

    private fun freeLlmPreset(modelId: String) = ModelPreset(
        id = "freellmapi-preset",
        displayName = "FreeLLMAPI",
        providerType = ModelProviderType.REMOTE_OPENAI_COMPATIBLE,
        modelIdentifier = modelId,
        apiProtocol = ModelApiProtocol.OPENAI_COMPATIBLE,
        apiBasePath = "",
        endpoint = EndpointConfig(EndpointDiscoveryMode.CONFIGURED_ENDPOINT, "https://agentx-vgtx.onrender.com/v1"),
        setupKind = ModelSetupKind.FREELLMAPI.id,
    )

    /**
     * A model id the endpoint's own list does not carry — the incident's
     * `qwen3.6-27b` shape. It must stay exactly what the user typed: it is the id
     * that gets saved and the id connect verifies, and the picker must never
     * invent a row (or a substitute) for a model the gateway did not return.
     */
    @Test
    fun `a manually typed gateway model id is kept and is what gets saved`() {
        val live = freeLlmSnapshot("gemini-2.5-flash", "glm-5.3")
        val registry = FakeCatalogRegistry(live, ForgeResult.Success(live))
        val viewModel = ModelEditorViewModel(
            manager = manager(listOf(freeLlmPreset("gemini-2.5-flash"))),
            presetId = "freellmapi-preset",
            catalog = registry,
        )
        assertEquals("gemini-2.5-flash", viewModel.state.form.modelId)

        viewModel.toggleManualModel(true)
        viewModel.selectModel("qwen3.6-27b")
        viewModel.retryCatalog()

        // Survives the refresh exactly as typed...
        assertEquals("qwen3.6-27b", viewModel.state.form.modelId)
        // ...because it is what the connection saves and verifies.
        assertEquals("qwen3.6-27b", viewModel.state.form.toPreset(null).modelIdentifier)
        assertEquals("qwen3.6-27b", viewModel.state.form.toConnectRequest().modelIdentifier)
        // ...and the endpoint's own list is never padded with it.
        assertTrue(viewModel.state.models.none { it.id == "qwen3.6-27b" })
        assertEquals(listOf("gemini-2.5-flash", "glm-5.3"), viewModel.state.models.map { it.id }.sorted())
    }

    /**
     * The switch states a fact about one model, so it has to follow the selection.
     * Otherwise a user could believe a model was declared that never was — or that a
     * model they did declare was not, which is the state the incident was reported from.
     */
    @Test
    fun `selecting a model shows that model's own statement`() {
        val existing = freeLlmPreset("gemini-2.5-flash")
            .stating("gemini-3.5-flash-lite", ModelCapabilityDeclaration.toolEnabledEndpoint())
        val live = freeLlmSnapshot("gemini-2.5-flash", "gemini-3.5-flash-lite")
        val viewModel = ModelEditorViewModel(
            manager = manager(listOf(existing)),
            presetId = "freellmapi-preset",
            catalog = FakeCatalogRegistry(live, ForgeResult.Success(live)),
        )

        // The connection's own model was never stated, so the switch is off for it.
        assertFalse(viewModel.state.form.declaresToolCalling)

        // Selecting the model the user did state shows it as on...
        viewModel.selectModel("gemini-3.5-flash-lite")
        assertTrue(viewModel.state.form.declaresToolCalling)

        // ...and selecting a model nothing is stated for shows off, rather than carrying
        // the previous model's answer over to it.
        viewModel.selectModel("gemini-2.5-flash")
        assertFalse(viewModel.state.form.declaresToolCalling)
    }

    /**
     * The pre-save preview is addressed at the form's own endpoint and carries the
     * form's own model: the same provider identity, address and id the runtime will
     * chat through, so the picker, the save and the verification cannot disagree.
     */
    @Test
    fun `the pre-save preview discovers against the form's own endpoint and model`() {
        val live = freeLlmSnapshot("gemini-2.5-flash", "glm-5.3")
        val registry = FakeCatalogRegistry(null, notConnected(), ForgeResult.Success(live))
        val viewModel = newFreeLlmApiForm(registry)
        viewModel.selectModel("qwen3.6-27b")

        viewModel.retryCatalog()

        val draft = assertNotNull(registry.lastPreviewConnection)
        assertEquals("https://agentx-vgtx.onrender.com/v1", draft.baseUrl)
        assertEquals("qwen3.6-27b", draft.model)
        assertEquals("freellmapi", draft.providerId)
        // The endpoint answered, so the picker shows its list and no error.
        assertTrue(viewModel.state.models.any { it.id == "gemini-2.5-flash" })
        assertNull(viewModel.state.catalogError)
        // A preview is read-only: nothing was saved by it.
        assertFalse(viewModel.state.saved)
    }
}
