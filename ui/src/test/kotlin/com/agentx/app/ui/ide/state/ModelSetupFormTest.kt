package com.agentx.app.ui.ide.state

import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.model.RoleModelSelection
import com.agentx.app.model.capability.CapabilitySupport
import com.agentx.app.model.capability.ModelCapabilityDeclaration
import com.agentx.app.model.catalog.CatalogModel
import com.agentx.app.model.catalog.CatalogSource
import com.agentx.app.model.catalog.ModelCatalog
import com.agentx.app.model.catalog.ModelCatalogRegistry
import com.agentx.app.model.catalog.ModelCatalogSnapshot
import com.agentx.app.model.connect.KnownModelProviders
import com.agentx.app.model.connect.ModelSetupKind
import com.agentx.app.model.manager.ModelConnectionKind
import com.agentx.app.model.manager.connectionKind
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
        model: String = "gemini-3.5-flash",
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

    // --- Local credentials and protocol -------------------------------------

    @Test
    fun `a local server may carry an optional api key, which is submitted but never stored in the preset`() {
        val form = localForm(url = "armored-fantasy-stuffing.ngrok-free.dev").copy(
            credential = "sk-local-secret",
        )

        val preset = form.toPreset(existing = null)

        assertEquals("sk-local-secret", form.toConnectRequest().credential)
        // The secret is never part of the saved configuration; the credential store
        // owns it and the preset keeps only a reference.
        assertNull(preset.credentialRef)
        assertFalse(preset.toString().contains("sk-local-secret"))
        // Optional for Local: a blank key is never a blocking issue.
        assertTrue(form.issues().none { it.field == ModelSetupField.API_KEY })
    }

    @Test
    fun `an explicit protocol is submitted while auto leaves detection to decide`() {
        val compatible = localForm(url = "127.0.0.1:8000")
            .copy(protocol = ModelProtocolChoice.OPENAI_COMPATIBLE)
        assertEquals(ModelApiProtocol.OPENAI_COMPATIBLE, compatible.toConnectRequest().apiProtocol)
        assertEquals(ModelApiProtocol.OPENAI_COMPATIBLE, compatible.toPreset(existing = null).apiProtocol)

        val ollama = localForm(url = "127.0.0.1:11434").copy(protocol = ModelProtocolChoice.OLLAMA)
        assertEquals(ModelApiProtocol.OLLAMA, ollama.toConnectRequest().apiProtocol)
        assertEquals(ModelApiProtocol.OLLAMA, ollama.toPreset(existing = null).apiProtocol)

        val auto = localForm(url = "127.0.0.1:8000").copy(protocol = ModelProtocolChoice.AUTO)
        assertNull(auto.toConnectRequest().apiProtocol, "auto means detection decides")
        assertEquals(ModelApiProtocol.OPENAI_COMPATIBLE, auto.toPreset(existing = null).apiProtocol)
    }

    @Test
    fun `the saved protocol is loaded back into the form`() {
        val preset = localForm(url = "http://127.0.0.1:11434")
            .toPreset(existing = null)
            .copy(id = "p2", apiProtocol = ModelApiProtocol.OLLAMA)

        assertEquals(ModelProtocolChoice.OLLAMA, ModelSetupForm.from(preset).protocol)
    }

    @Test
    fun `an opaque custom model id is kept exactly as typed`() {
        val id = "hf.co/unsloth/Devstral-Small-2-24B-Instruct-2512-GGUF:Q3_K_M"
        val form = localForm(url = "armored-fantasy-stuffing.ngrok-free.dev").copy(modelId = id)

        assertEquals(id, form.toPreset(existing = null).modelIdentifier)
        assertEquals(id, form.toConnectRequest().modelIdentifier)
        assertTrue(form.issues().none { it.field == ModelSetupField.MODEL })
    }

    @Test
    fun `a local display name stays a label, never the endpoint or the model`() {
        val preset = localForm(url = "armored-fantasy-stuffing.ngrok-free.dev", name = "geminj")
            .toPreset(existing = null)

        assertEquals("geminj", preset.displayName)
        assertFalse(assertNotNull(preset.endpoint.explicitUrl).contains("geminj"))
        assertFalse(preset.modelIdentifier.contains("geminj"))
        assertFalse(preset.providerId.contains("geminj"))
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
    fun `a FreeLLMAPI connection is API AI, addressed at its shipped gateway`() {
        val form = apiForm(ModelSetupKind.FREELLMAPI, model = "gemini-2.5-flash", name = "FreeLLMAPI")

        // API AI, never Local AI, even though both speak OpenAI-compatible.
        assertEquals(ModelConnectionType.API, form.connectionType)
        assertEquals("freellmapi", form.providerId)
        assertEquals(ModelConnectionKind.API, form.setupKind.connectionKind)
        // The address field is shown and starts at the shipped gateway, so it can be
        // edited without the user having to know the URL.
        assertTrue(form.showsServerUrl)
        assertEquals("https://agentx-vgtx.onrender.com/v1", form.effectiveServerUrl)
        assertFalse(form.serverUrlRequired)
        assertTrue(form.issues().isEmpty(), form.issues().toString())

        val preset = form.toPreset(null)
        assertEquals("freellmapi", preset.providerId)
        assertEquals(ModelProviderType.REMOTE_OPENAI_COMPATIBLE, preset.providerType)
        // The existing OpenAI-compatible protocol, not a new one.
        assertEquals(ModelApiProtocol.OPENAI_COMPATIBLE, preset.apiProtocol)
        assertEquals("https://agentx-vgtx.onrender.com/v1", preset.endpoint.explicitUrl)
        assertEquals("", preset.apiBasePath)
        assertEquals("gemini-2.5-flash", preset.modelIdentifier)
        assertTrue(preset.validate().isEmpty(), preset.validate().toString())

        // The connection test probes the gateway's address, with the provider identity.
        val request = form.toConnectRequest()
        assertEquals("freellmapi", request.setupKind.id)
        assertEquals("https://agentx-vgtx.onrender.com/v1", request.endpoint)
    }

    @Test
    fun `a FreeLLMAPI connection can be pointed at another gateway`() {
        val form = apiForm(ModelSetupKind.FREELLMAPI, model = "gemini-2.5-flash")
            .copy(serverUrl = "https://my-gateway.example.com/v1")

        assertEquals("https://my-gateway.example.com/v1", form.effectiveServerUrl)
        assertEquals("https://my-gateway.example.com/v1", form.toConnectRequest().endpoint)
        // Used exactly as typed: no second /v1 is appended to the edited address.
        val preset = form.toPreset(null)
        assertEquals("https://my-gateway.example.com/v1", preset.endpoint.explicitUrl)
        assertEquals("", preset.apiBasePath)
        assertTrue(preset.validate().isEmpty(), preset.validate().toString())
    }

    @Test
    fun `a saved FreeLLMAPI gateway is loaded back into the form`() {
        val saved = ModelPreset(
            id = "p1",
            displayName = "FreeLLMAPI",
            providerType = ModelProviderType.REMOTE_OPENAI_COMPATIBLE,
            modelIdentifier = "gemini-2.5-flash",
            apiProtocol = ModelApiProtocol.OPENAI_COMPATIBLE,
            apiBasePath = "",
            credentialRef = "models.secret.p1",
            endpoint = EndpointConfig(EndpointDiscoveryMode.CONFIGURED_ENDPOINT, "https://gateway.example.com/v1"),
            setupKind = ModelSetupKind.FREELLMAPI.id,
        )

        val form = ModelSetupForm.from(saved)

        assertEquals(ModelSetupKind.FREELLMAPI, form.setupKind)
        assertEquals(ModelConnectionType.API, form.connectionType)
        assertEquals("https://gateway.example.com/v1", form.effectiveServerUrl)
        assertEquals("https://gateway.example.com/v1", form.toPreset(saved).endpoint.explicitUrl)
    }

    @Test
    fun `an API model needs a key unless one is already stored`() {
        assertNotNull(apiForm(key = "").issueFor(ModelSetupField.API_KEY))
        assertNull(apiForm(key = "").copy(hasStoredCredential = true).issueFor(ModelSetupField.API_KEY))
    }

    @Test
    fun `a FreeLLMAPI connection needs a key and offers its catalogue models`() {
        assertNotNull(apiForm(ModelSetupKind.FREELLMAPI, key = "").issueFor(ModelSetupField.API_KEY))
        assertEquals(
            KnownModelProviders.freeLlmApi.suggestedModels,
            ModelChoices.suggestions("freellmapi"),
        )
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
            modelIdentifier = "gemini-3.5-flash",
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
    fun `searching the full catalog preserves the exact discovered id`() {
        val catalog = registry(
            models = listOf(
                CatalogModel(id = "openai/gpt-oss-120b", displayName = "GPT OSS 120B"),
                CatalogModel(id = "deepseek-v4-pro", displayName = "DeepSeek V4 Pro"),
                CatalogModel(id = "gemini-2.5-flash"),
            ),
        )
        val choices = ModelChoices.offered(catalog, "groq")

        // Matching is on the id and on the human label, and an id is never rewritten.
        assertEquals(listOf("openai/gpt-oss-120b"), ModelChoices.search(choices, "gpt-oss-120b").map { it.id })
        assertEquals(listOf("deepseek-v4-pro"), ModelChoices.search(choices, "DeepSeek V4").map { it.id })
        assertEquals(listOf("gemini-2.5-flash"), ModelChoices.search(choices, "GEMINI-2.5").map { it.id })
        // A blank query keeps the complete catalog.
        assertEquals(choices, ModelChoices.search(choices, "  "))
        // A query that matches nothing is empty, never a fallback to a preset list.
        assertTrue(ModelChoices.search(choices, "no-such-model").isEmpty())
    }

    @Test
    fun `featured ids are only the candidates the catalog actually returned`() {
        val live = listOf(
            CatalogModel(id = "gemini-3.8-flash", displayName = "Gemini 3.8 Flash"),
            CatalogModel(id = "openai/gpt-oss-120b", displayName = "GPT OSS 120B"),
            CatalogModel(id = "some-unlisted-model"),
        )
        val choices = ModelChoices.catalogChoices(live)

        val featured = ModelChoices.markRecommended(choices, FREELLMAPI_RECOMMENDED_MODELS)

        // Returned candidates are featured and presented first ...
        assertEquals(
            listOf("gemini-3.8-flash", "openai/gpt-oss-120b"),
            featured.filter { it.recommended }.map { it.id },
        )
        // ... everything else keeps a place, and no candidate is invented.
        assertTrue(featured.none { it.id == "deepseek-v4-pro" })
        assertTrue(featured.none { it.id == "glm-5.3" })
        assertEquals(choices.size, featured.size)
        // The featured section can never contain a model the endpoint did not list.
        assertTrue(featured.filter { it.recommended }.all { it.id in choices.map { c -> c.id } })
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

    // --- stating tool calling where no catalogue speaks for the connection ----

    /**
     * The reported incident: a gateway model discovered through FreeLLMAPI stays
     * `toolCalling = UNKNOWN`, so a role that needs tools cannot use it. The
     * gateway is addressed by its own endpoint, so its model capabilities are the
     * one thing only the user can state — and the form must let them.
     */
    @Test
    fun `a FreeLLMAPI connection can state tool calling for one model`() {
        val form = apiForm(ModelSetupKind.FREELLMAPI, model = "qwen3.6-27b", name = "Qwen")
            .copy(declaresToolCalling = true)

        assertTrue(form.declarableCapabilities)
        val declared = assertNotNull(form.declaredCapabilities())
        assertEquals(CapabilitySupport.SUPPORTED, declared.toolCalling)
        assertEquals(CapabilitySupport.SUPPORTED, declared.streaming)
        // Nothing else is claimed on the user's behalf.
        assertEquals(CapabilitySupport.UNKNOWN, declared.vision)
        assertEquals(CapabilitySupport.UNKNOWN, declared.structuredOutput)
        assertEquals(CapabilitySupport.UNKNOWN, declared.reasoning)

        // The statement travels on both paths this connection uses: the saved preset
        // and the connect request that verifies it.
        assertEquals(declared, form.toPreset(null).declaredCapabilities)
        assertEquals(declared, form.toConnectRequest().declaredCapabilities)
        // And the id it was stated for is unchanged on both.
        assertEquals("qwen3.6-27b", form.toPreset(null).modelIdentifier)
        assertEquals("qwen3.6-27b", form.toConnectRequest().modelIdentifier)
        assertEquals("freellmapi", form.toPreset(null).providerId)
    }

    @Test
    fun `a FreeLLMAPI connection that states nothing withdraws an earlier statement`() {
        val existing = apiForm(ModelSetupKind.FREELLMAPI, model = "qwen3.6-27b")
            .toPreset(null)
            .copy(id = "p1", declaredCapabilities = ModelCapabilityDeclaration.toolEnabledEndpoint())

        // Off is a statement too. It must produce an *empty* declaration rather than
        // null: null means "leave the preset alone", which would keep a claim the
        // user has just withdrawn.
        val form = ModelSetupForm.from(existing)
        assertFalse(form.declaresToolCalling)
        val withdrawal = assertNotNull(form.declaredCapabilities())
        assertTrue(withdrawal.isEmpty)
        assertTrue(form.toPreset(existing).declaredCapabilities.isEmpty)
    }

    @Test
    fun `a statement saved for a gateway model is loaded back into the form`() {
        val saved = apiForm(ModelSetupKind.FREELLMAPI, model = "qwen3.6-27b")
            .toPreset(null)
            .copy(id = "p1", declaredCapabilities = ModelCapabilityDeclaration.toolEnabledEndpoint())

        val form = ModelSetupForm.from(saved)

        // Only a stated SUPPORTED reads back as on, so the switch shows the user's
        // own statement rather than one nobody made.
        assertTrue(form.declaresToolCalling)
        assertEquals(saved.declaredCapabilities, form.declaredCapabilities())
    }

    /**
     * The guarantee on the other side: a provider whose catalogue does state its
     * capabilities is never overridden by the form.
     */
    @Test
    fun `a catalogue provider is never declared over`() {
        val gemini = apiForm(ModelSetupKind.GEMINI).copy(declaresToolCalling = true)
        assertFalse(gemini.declarableCapabilities)
        assertNull(gemini.declaredCapabilities())

        val groq = apiForm(ModelSetupKind.GROQ, model = "llama-3.3-70b-versatile")
            .copy(declaresToolCalling = true)
        assertFalse(groq.declarableCapabilities)
        assertNull(groq.declaredCapabilities())

        // Editing a Gemini preset that already carries a statement leaves that
        // statement exactly as it was, rather than reading it back as this form's.
        val existing = apiForm(ModelSetupKind.GEMINI).toPreset(null)
            .copy(id = "p1", declaredCapabilities = ModelCapabilityDeclaration.toolEnabledEndpoint())
        val untouched = ModelSetupForm.from(existing).toPreset(existing)
        assertEquals(existing.declaredCapabilities, untouched.declaredCapabilities)
    }

    @Test
    fun `declarability follows the endpoint addressed connections`() {
        assertTrue(localForm().declarableCapabilities)
        assertTrue(apiForm(ModelSetupKind.FREELLMAPI).declarableCapabilities)
        assertFalse(apiForm(ModelSetupKind.GEMINI).declarableCapabilities)
        assertFalse(apiForm(ModelSetupKind.GROQ).declarableCapabilities)

        // Off by default everywhere: nothing is claimed on the user's behalf.
        assertTrue(localForm().declaredCapabilities()!!.isEmpty)
        assertTrue(apiForm(ModelSetupKind.FREELLMAPI).declaredCapabilities()!!.isEmpty)
    }
}
