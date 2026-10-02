package com.agentx.app.ui.ide.state

import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.model.RoleModelSelection
import com.agentx.app.model.catalog.CatalogModel
import com.agentx.app.model.catalog.CatalogSource
import com.agentx.app.model.catalog.ModelCatalog
import com.agentx.app.model.catalog.ModelCatalogRegistry
import com.agentx.app.model.catalog.ModelCatalogSnapshot
import com.agentx.app.model.connect.KnownModelProviders
import com.agentx.app.model.connect.ModelSetupKind
import com.agentx.app.model.preset.EndpointConfig
import com.agentx.app.model.preset.EndpointDiscoveryMode
import com.agentx.app.model.preset.HealthCheckConfig
import com.agentx.app.model.preset.ModelApiProtocol
import com.agentx.app.model.preset.ModelPreset
import com.agentx.app.model.preset.ModelProviderType
import com.agentx.app.model.preset.TunnelConfig
import com.agentx.app.model.preset.TunnelType
import com.agentx.app.core.ForgeError
import com.agentx.app.core.ForgeResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Add/Edit model form: what the two flows produce, what they reject, and
 * what they deliberately leave alone.
 */
class ModelSetupFormTest {

    private fun localForm(
        url: String = "https://abc123.ngrok-free.app",
        model: String = "qwen2.5-coder-7b",
        name: String = "Local coder",
    ) = ModelSetupForm(
        connectionType = ModelConnectionType.LOCAL,
        name = name,
        modelId = model,
        serverUrl = url,
    )

    private fun apiForm(
        provider: ModelSetupKind = ModelSetupKind.GEMINI,
        model: String = "gemini-2.0-flash",
        name: String = "Gemini",
        key: String = "test-key",
    ) = ModelSetupForm(
        connectionType = ModelConnectionType.API,
        apiProvider = provider,
        name = name,
        modelId = model,
        credential = key,
    )

    // --- Local flow ---------------------------------------------------------

    @Test
    fun `a local model keeps its server URL and works without an API key`() {
        val preset = localForm().toPreset(existing = null)

        assertEquals("Local coder", preset.displayName)
        assertEquals("qwen2.5-coder-7b", preset.modelIdentifier)
        assertEquals("https://abc123.ngrok-free.app", preset.endpoint.explicitUrl)
        assertEquals(EndpointDiscoveryMode.CONFIGURED_ENDPOINT, preset.endpoint.mode)
        assertEquals(ModelSetupKind.CUSTOM.id, preset.setupKind)
        assertEquals(ModelProviderType.REMOTE_OPENAI_COMPATIBLE, preset.providerType)
        assertNull(preset.credentialRef)
        assertTrue(preset.validate().isEmpty(), preset.validate().toString())
        // No credential is required for a local endpoint.
        assertTrue(localForm().issues().none { it.field == ModelSetupField.API_KEY })
    }

    @Test
    fun `a tunnel host without a scheme is normalized to https`() {
        val preset = localForm(url = "abc123.ngrok-free.app").toPreset(existing = null)

        assertEquals("https://abc123.ngrok-free.app", preset.endpoint.explicitUrl)
        assertEquals("https://abc123.ngrok-free.app", localForm(url = "abc123.ngrok-free.app").normalizedServerUrl)
    }

    @Test
    fun `a loopback endpoint stays http and keeps the provider base path`() {
        val form = localForm(url = "http://127.0.0.1:8080")
        val preset = form.toPreset(existing = null)

        assertEquals("http://127.0.0.1:8080", preset.endpoint.explicitUrl)
        assertEquals(ModelProviderType.LOCAL_PHONE, preset.providerType)
        assertEquals(ModelApiProtocol.OPENAI_COMPATIBLE, preset.apiProtocol)
        // "/v1" is preserved, so chat goes to <endpoint>/v1/chat/completions.
        assertEquals("/v1", preset.apiBasePath)
        assertTrue(preset.validate().isEmpty(), preset.validate().toString())
    }

    @Test
    fun `the saved endpoint is loaded back into the form`() {
        val preset = localForm(url = "https://local.example.com/v1").toPreset(existing = null).copy(id = "p1")

        val form = ModelSetupForm.from(preset)

        assertEquals("p1", form.presetId)
        assertEquals(ModelConnectionType.LOCAL, form.connectionType)
        assertEquals("Local coder", form.name)
        assertEquals("qwen2.5-coder-7b", form.modelId)
        assertEquals(preset.endpoint.explicitUrl, form.serverUrl)
        assertTrue(form.isEditing)
    }

    @Test
    fun `a required server URL and a broken URL are both rejected`() {
        assertTrue(localForm(url = "").issues().any { it.field == ModelSetupField.SERVER_URL })
        assertEquals(
            "A server URL is required",
            localForm(url = "").issueFor(ModelSetupField.SERVER_URL),
        )
        // The URL rules come from the provider abstraction, not from this screen.
        assertNotNull(localForm(url = "https://host with space").serverUrlProblem)
        assertNotNull(localForm(url = "ftp://host").serverUrlProblem)
    }

    @Test
    fun `a name and a model id are required`() {
        assertNotNull(localForm(name = " ").issueFor(ModelSetupField.NAME))
        assertNotNull(localForm(model = " ").issueFor(ModelSetupField.MODEL))
    }

    // --- API flow -----------------------------------------------------------

    @Test
    fun `an API model is saved with the provider identity and its own endpoint`() {
        val preset = apiForm(ModelSetupKind.GROQ, model = "llama-3.3-70b-versatile", name = "Groq").toPreset(null)

        assertEquals(ModelSetupKind.GROQ.id, preset.setupKind)
        assertEquals("groq", preset.providerId)
        assertEquals(ModelProviderType.REMOTE_OPENAI_COMPATIBLE, preset.providerType)
        assertEquals(KnownModelProviders.groq.rootUrl, preset.endpoint.explicitUrl)
        assertEquals("/v1", preset.apiBasePath)
        assertTrue(preset.validate().isEmpty(), preset.validate().toString())
    }

    @Test
    fun `a Gemini model keeps the provider root URL and its own API version path`() {
        val preset = apiForm().toPreset(null)

        assertEquals("gemini", preset.providerId)
        assertEquals(KnownModelProviders.gemini.rootUrl, preset.endpoint.explicitUrl)
        // The native API version comes from the provider catalogue, and the base URL
        // is that root plus this path — `.../v1beta`, so a completion is
        // `.../v1beta/models/<model>:generateContent`. A chat path is never appended
        // to the OpenAI-compatible surface.
        assertEquals("/v1beta", preset.apiBasePath)
        assertEquals(ModelApiProtocol.GEMINI_NATIVE, preset.apiProtocol)
        assertTrue(preset.validate().isEmpty(), preset.validate().toString())
    }

    @Test
    fun `an API model needs a key unless one is already stored`() {
        assertNotNull(apiForm(key = "").issueFor(ModelSetupField.API_KEY))
        assertNull(apiForm(key = "").copy(hasStoredCredential = true).issueFor(ModelSetupField.API_KEY))
    }

    @Test
    fun `switching provider switches the model list source`() {
        val gemini = apiForm(provider = ModelSetupKind.GEMINI)
        val groq = apiForm(provider = ModelSetupKind.GROQ)

        assertEquals("gemini", gemini.providerId)
        assertEquals("groq", groq.providerId)
        assertEquals(KnownModelProviders.gemini.suggestedModels, ModelChoices.suggestions("gemini"))
        assertEquals(KnownModelProviders.groq.suggestedModels, ModelChoices.suggestions("groq"))
    }

    // --- Editing ------------------------------------------------------------

    @Test
    fun `editing keeps identity and the settings the form no longer shows`() {
        val saved = ModelPreset(
            id = "p1",
            displayName = "Old name",
            providerType = ModelProviderType.REMOTE_OPENAI_COMPATIBLE,
            modelIdentifier = "qwen2.5-coder-7b",
            apiProtocol = ModelApiProtocol.OLLAMA,
            apiBasePath = "/v1",
            startupScript = "python serve.py --port 8080",
            serverPort = 8080,
            endpoint = EndpointConfig(EndpointDiscoveryMode.RUNTIME_OUTPUT, null),
            tunnel = TunnelConfig(TunnelType.CLOUDFLARE_QUICK, "READY="),
            health = HealthCheckConfig(path = "/health", timeoutMillis = 12_000),
            enabled = false,
            setupKind = ModelSetupKind.CUSTOM.id,
            credentialRef = "models.secret.p1",
        )
        val form = ModelSetupForm.from(saved).copy(name = "New name", serverUrl = "https://new.example.com")

        val edited = form.toPreset(existing = saved)

        assertEquals("p1", edited.id)
        assertEquals("New name", edited.displayName)
        assertEquals("https://new.example.com", edited.endpoint.explicitUrl)
        assertEquals(EndpointDiscoveryMode.CONFIGURED_ENDPOINT, edited.endpoint.mode)
        // The hidden capabilities survive an edit.
        assertEquals(ModelApiProtocol.OLLAMA, edited.apiProtocol)
        assertEquals("python serve.py --port 8080", edited.startupScript)
        assertEquals(8080, edited.serverPort)
        assertEquals("READY=", edited.tunnel.marker)
        assertEquals("/health", edited.health.path)
        assertEquals(12_000L, edited.health.timeoutMillis)
        assertFalse(edited.enabled)
        assertEquals("models.secret.p1", edited.credentialRef)
        assertEquals(saved.createdAtMillis, edited.createdAtMillis)
    }

    @Test
    fun `an endpoint discovered by the runtime is not asked for again`() {
        val saved = ModelPreset(
            id = "p1",
            displayName = "Colab",
            providerType = ModelProviderType.GOOGLE_COLAB,
            modelIdentifier = "qwen",
            endpoint = EndpointConfig(EndpointDiscoveryMode.RUNTIME_OUTPUT, null),
            setupKind = ModelSetupKind.CUSTOM.id,
        )
        val form = ModelSetupForm.from(saved)

        assertTrue(form.inheritsEndpointDiscovery)
        assertFalse(form.serverUrlRequired)
        assertTrue(form.issues().none { it.field == ModelSetupField.SERVER_URL })
        // Its discovery mode is left exactly as it was.
        assertEquals(EndpointDiscoveryMode.RUNTIME_OUTPUT, form.toPreset(saved).endpoint.mode)
    }

    @Test
    fun `a stored credential is never required again and is never echoed`() {
        val saved = ModelPreset(
            id = "p2",
            displayName = "Gemini",
            providerType = ModelProviderType.REMOTE_OPENAI_COMPATIBLE,
            modelIdentifier = "gemini-2.0-flash",
            credentialRef = "models.secret.p2",
            setupKind = ModelSetupKind.GEMINI.id,
        )
        val form = ModelSetupForm.from(saved)

        assertEquals("", form.credential)
        assertTrue(form.hasStoredCredential)
        assertNull(form.issueFor(ModelSetupField.API_KEY))
        assertEquals("models.secret.p2", form.toPreset(saved).credentialRef)
        // Removing it clears the reference and re-requires a key.
        val cleared = form.copy(clearCredential = true, hasStoredCredential = false)
        assertNull(cleared.toPreset(saved).credentialRef)
        assertNotNull(cleared.issueFor(ModelSetupField.API_KEY))
    }

    // --- Duplicates ---------------------------------------------------------

    @Test
    fun `adding the same connection again targets the saved model`() {
        val saved = localForm().toPreset(existing = null).copy(id = "p1", credentialRef = "models.secret.p1")

        val same = ModelSetupForm.equivalent(listOf(saved), localForm())
        assertNotNull(same)
        assertEquals("p1", same.id)
        // The connect request then updates that preset instead of adding a second.
        assertEquals("p1", localForm().toConnectRequest(same).presetId)
    }

    @Test
    fun `a different endpoint is a different model`() {
        val saved = localForm().toPreset(existing = null).copy(id = "p1")

        assertNull(ModelSetupForm.equivalent(listOf(saved), localForm(url = "https://other.example.com")))
        assertNull(ModelSetupForm.equivalent(listOf(saved), localForm(model = "llama-3.1-8b")))
    }

    @Test
    fun `the same API model is not added twice`() {
        val saved = apiForm(ModelSetupKind.GROQ, model = "llama-3.3-70b-versatile").toPreset(null)
            .copy(id = "p9")

        assertNotNull(
            ModelSetupForm.equivalent(
                listOf(saved),
                apiForm(ModelSetupKind.GROQ, model = "llama-3.3-70b-versatile"),
            ),
        )
        assertNull(
            ModelSetupForm.equivalent(
                listOf(saved),
                apiForm(ModelSetupKind.GROQ, model = "llama-3.1-8b-instant"),
            ),
        )
    }

    // --- Provider catalog ---------------------------------------------------

    private fun registry(
        models: List<CatalogModel>,
        unavailable: List<CatalogModel> = emptyList(),
    ): ModelCatalogRegistry =
        object : ModelCatalogRegistry {
            private val snapshot = ModelCatalogSnapshot(
                providerId = "groq",
                models = models + unavailable,
                fetchedAtMillis = 1L,
                source = CatalogSource.REMOTE,
            )
            override fun providers(): List<String> = listOf("groq")
            override fun catalog(providerId: String): ModelCatalog? = null
            override fun snapshot(providerId: String): ModelCatalogSnapshot? = snapshot
            override fun availableModels(providerId: String): List<CatalogModel> = models
            override suspend fun refresh(providerId: String, force: Boolean): ForgeResult<ModelCatalogSnapshot, ForgeError> =
                ForgeResult.Success(snapshot)

            override suspend fun refreshAll(force: Boolean): Map<String, ModelCatalogSnapshot> =
                mapOf("groq" to snapshot)

            override suspend fun restore() = Unit
        }

    @Test
    fun `the provider catalog wins and unavailable models are never offered`() {
        val catalog = registry(
            models = listOf(
                CatalogModel(id = "llama-3.3-70b-versatile"),
                CatalogModel(id = "llama-3.1-8b-instant"),
            ),
            unavailable = listOf(CatalogModel(id = "retired-model", available = false)),
        )

        val offered = ModelChoices.offered(catalog, "groq")

        assertEquals(
            listOf("llama-3.1-8b-instant", "llama-3.3-70b-versatile"),
            ModelChoices.catalogChoices(catalog, "groq").map { it.id },
        )
        assertTrue(offered.none { it.id == "retired-model" })
        // Only the live catalog is offered, in catalog order and never duplicated.
        assertEquals(listOf("llama-3.1-8b-instant", "llama-3.3-70b-versatile"), offered.map { it.id })
        assertEquals(offered.distinct(), offered)
        // The provider's compatibility list is not appended to a live catalog.
        assertTrue(offered.none { it.id == "mixtral-8x7b-32768" })
    }

    @Test
    fun `the built-in fallback list is not offered once discovery succeeded`() {
        val catalog = registry(models = listOf(CatalogModel(id = "gemini-3.1-flash")))

        val offered = ModelChoices.offered(catalog, "gemini")

        assertEquals(listOf("gemini-3.1-flash"), offered.map { it.id })
        assertTrue(offered.none { it.id in KnownModelProviders.gemini.suggestedModels })
    }

    @Test
    fun `a discovered display name is shown while the id is what gets saved`() {
        val catalog = registry(
            models = listOf(
                CatalogModel(id = "gemini-9-ultra-preview", displayName = "Gemini 9 Ultra (preview)"),
            ),
        )

        val choices = ModelChoices.offered(catalog, "gemini")

        val preview = choices.single { it.id == "gemini-9-ultra-preview" }
        assertEquals("Gemini 9 Ultra (preview)", preview.label)
        // The picker labels by display name, but the id is what the form holds.
        assertEquals("Gemini 9 Ultra (preview)", ModelChoices.labelFor(choices, "gemini-9-ultra-preview"))
    }

    @Test
    fun `a saved model stays selectable when discovery cannot list it`() {
        val choices = ModelChoices.offered(null, "gemini")

        // Nothing was discovered, so the built-in compatibility list is offered...
        assertTrue(choices.map { it.id }.containsAll(KnownModelProviders.gemini.suggestedModels))
        assertTrue(ModelChoices.catalogChoices(null, "gemini").isEmpty())
        // ...while a model that is already saved keeps its own label.
        assertEquals("gemini-1.5-pro", ModelChoices.labelFor(choices, "gemini-1.5-pro"))
        assertEquals("gemini-1.5-pro", ModelChoices.labelFor(emptyList(), "gemini-1.5-pro"))
    }

    // --- Detail presentation ------------------------------------------------

    @Test
    fun `roles that use this model are listed`() {
        val preset = localForm().toPreset(null).copy(id = "p1")
        val selections = mapOf(
            AgentRole.MAIN to RoleModelSelection(AgentRole.MAIN, preset.providerId, preset.modelIdentifier, "p1", true),
            AgentRole.CODER to RoleModelSelection(AgentRole.CODER, preset.providerId, "other-model", null, true),
            AgentRole.REVIEWER to RoleModelSelection(AgentRole.REVIEWER, "gemini", preset.modelIdentifier, null, true),
        )

        val roles = ModelDetailPresentation.assignedRoles(preset) { role ->
            selections[role] ?: RoleModelSelection(
                role = role,
                providerId = null,
                model = null,
                connectionId = null,
                explicit = false,
            )
        }

        assertEquals(listOf(AgentRole.MAIN), roles)
    }

    @Test
    fun `usage copy distinguishes local endpoints from remote quotas`() {
        assertEquals(
            "Local endpoint · outside remote API quotas",
            ModelUsageSummary(local = true).line(),
        )
        assertNull(ModelUsageSummary(local = false).line())
        val line = ModelUsageSummary(
            local = false,
            requestCount = 12,
            totalTokens = 3_400,
            requestsPerMinute = 30,
        ).line()
        assertNotNull(line)
        assertTrue(line.contains("12 requests"), line)
        assertTrue(line.contains("30 rpm"), line)
    }
}
