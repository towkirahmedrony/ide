package com.agentx.app.model.manager

import com.agentx.app.core.ForgeError
import com.agentx.app.core.ForgeErrorCode
import com.agentx.app.core.ForgeResult
import com.agentx.app.core.failure
import com.agentx.app.core.logging.ForgeLogger
import com.agentx.app.core.logging.ForgeLoggers
import com.agentx.app.core.logging.LogLevel
import com.agentx.app.core.success
import com.agentx.app.core.valueOrNull
import com.agentx.app.model.DefaultModelGateway
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.capability.InMemoryModelCapabilityRegistry
import com.agentx.app.model.capability.ModelCapabilityRegistry
import com.agentx.app.model.connect.ChatCapabilityProbe
import com.agentx.app.model.connect.ModelApiDiscovery
import com.agentx.app.model.connect.ModelConnectOutcome
import com.agentx.app.model.connect.ModelConnectPhase
import com.agentx.app.model.connect.ModelConnectRequest
import com.agentx.app.model.connect.ModelConnectService
import com.agentx.app.model.http.HttpTransport
import com.agentx.app.model.http.UrlConnectionHttpTransport
import com.agentx.app.model.preset.ModelCredentialResolver
import com.agentx.app.model.preset.ModelPreset
import com.agentx.app.model.preset.ModelPresetRepository
import com.agentx.app.model.preset.ModelSecretStore
import com.agentx.app.model.preset.normalizeModelId
import com.agentx.app.model.runtime.ModelEndpoint
import com.agentx.app.model.runtime.ModelHealth
import com.agentx.app.model.runtime.ModelLifecycleState
import com.agentx.app.model.runtime.ModelRunner
import com.agentx.app.model.runtime.ModelRuntimeFailure
import com.agentx.app.model.runtime.ModelRuntimeStatus
import com.agentx.app.model.runtime.RunnerOperationResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Default [ModelManager].
 *
 * Behaviour worth knowing:
 * - Loading presets and probing the selected model happens once at startup and
 *   never launches a remote workload; a probe is a health check, nothing more.
 * - A model that is already usable is never restarted (start short-circuits).
 * - Monitoring runs only while the app is in the foreground. Android cannot
 *   guarantee background network continuity, so on background the state becomes
 *   unknown and it is re-checked when the app returns instead of being claimed.
 * - Every failure path is bounded by [com.agentx.app.model.runtime.ModelConnectionPolicy]
 *   and always ends in a terminal state.
 */
class DefaultModelManager(
    private val repository: ModelPresetRepository,
    private val runners: List<ModelRunner>,
    private val registry: ModelConnectionRegistry,
    private val credentials: ModelCredentialResolver,
    private val secretStore: ModelSecretStore,
    logger: ForgeLogger = ForgeLoggers.create(
        level = LogLevel.WARN,
        baseFields = mapOf("component" to "model-manager"),
    ),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Monitoring can be disabled by configuration; the model still works. */
    private val monitorEnabled: Boolean = true,
    transport: HttpTransport = UrlConnectionHttpTransport(),
    connectServiceFactory: ((ModelManager) -> ModelConnectService)? = null,
    /**
     * The capability store a saved [ModelPreset.declaredCapabilities] declaration
     * is published to. One shared instance backs this manager, the connect
     * service, the gateway and the role resolver, so a declaration stated once is
     * visible to every checkpoint that decides eligibility.
     */
    private val capabilityRegistry: ModelCapabilityRegistry = InMemoryModelCapabilityRegistry(),
) : ModelManager {

    private val connectService: ModelConnectService =
        connectServiceFactory?.invoke(this) ?: ModelConnectService(
            manager = this,
            transport = transport,
            // Every stage of a connect — discovery, the verification chat probe and
            // the provider request beneath it — logs through the one manager logger,
            // so the Developer Log shows one addressable trail per provider.
            discovery = ModelApiDiscovery(transport, logger = logger),
            chatProbe = ChatCapabilityProbe(
                gateway = DefaultModelGateway(),
                // The same provider selection the runtime uses, so verification
                // exercises the protocol the preset will actually chat with, and the
                // trace keeps flowing into the manager's logger.
                providerFactory = { preset ->
                    DefaultModelProviderFactory(transport, logger).create(preset)
                },
                logger = logger,
            ),
            resolveStoredCredential = { preset -> io { credentials.resolve(preset) } },
            logger = logger,
            capabilityRegistry = capabilityRegistry,
        )

    private val log: ForgeLogger = logger

    private val mutableState = MutableStateFlow(ModelManagerState())

    override val state: StateFlow<ModelManagerState> = mutableState.asStateFlow()

    override val credentialsPersistent: Boolean get() = secretStore.persistent

    private var monitorJob: Job? = null
    private var foreground: Boolean = true
    private val persistedStatuses = LinkedHashMap<String, String>()

    /**
     * Credential per preset id, resolved off the calling thread whenever presets
     * are (re)loaded, so [catalogConnections] can build a catalog-only
     * configuration without a blocking secret read. An empty value means "no
     * credential", so the map never holds null. Never logged.
     */
    private val catalogCredentials = ConcurrentHashMap<String, String>()

    init {
        runners.forEach { runner ->
            scope.launch {
                runner.statuses().collect(::onRunnerStatuses)
            }
        }
    }

    // --- presets -----------------------------------------------------------

    override suspend fun refresh() {
        mutableState.update { it.copy(loading = true) }

        val presets = io { repository.list() }
        val activeId = io { repository.activeId() }
        cacheCatalogCredentials(presets)
        // A saved declaration is knowledge that outlives the process, so it is
        // re-published to the shared registry on every load. Nothing is registered
        // for a preset that declares nothing.
        registerPresetCapabilities(presets)
        val active = presets.firstOrNull { it.id == activeId }

        // Restore what was last known, but never claim a live connection that has
        // not been checked in this process.
        val restored = presets.associate { preset ->
            preset.id to restoredStatus(preset)
        }

        mutableState.update {
            it.copy(
                loading = false,
                presets = presets,
                activePresetId = active?.id,
                statuses = it.statuses + restored,
                activeConfig = if (active == null) null else it.activeConfig,
            )
        }

        if (active == null) {
            registry.activePresetId()?.let(registry::disconnect)
            stopMonitor()
            mutableState.update { it.copy(activeConfig = null) }
            return
        }

        val runner = runnerFor(active) ?: return
        val health = io { runner.healthCheck(active) }
        if (health.isReachable) {
            bind(active, runner.status(active.id).endpoint)
        } else if (registry.activePresetId() == active.id) {
            registry.disconnect(active.id)
            stopMonitor()
            mutableState.update { it.copy(activeConfig = null) }
        }
    }

    override suspend fun preset(id: String): ModelPreset? = io { repository.find(id) }

    override suspend fun createPreset(
        preset: ModelPreset,
        credential: String?,
    ): ForgeResult<ModelPreset, ForgeError> {
        val ref = storeCredential(preset.credentialRef, credential)
        val result = io { repository.create(preset.copy(credentialRef = ref ?: preset.credentialRef)) }
        result.valueOrNull()?.let { stored ->
            val status = statusFor(stored, ModelLifecycleState.STOPPED, "Stopped", clock())
            mutableState.update { it.copy(statuses = it.statuses + (stored.id to status)) }
            reloadPresets()
        }
        return result
    }

    override suspend fun updatePreset(
        preset: ModelPreset,
        credential: String?,
        clearCredential: Boolean,
    ): ForgeResult<ModelPreset, ForgeError> {
        val existing = io { repository.find(preset.id) } ?: return failure(notFound(preset.id))

        val ref: String? = when {
            clearCredential -> {
                existing.credentialRef?.let { io { secretStore.remove(it) } }
                null
            }

            !credential.isNullOrBlank() -> storeCredential(existing.credentialRef, credential)

            else -> existing.credentialRef
        }

        val result = io { repository.update(preset.copy(credentialRef = ref)) }
        result.valueOrNull()?.let { stored ->
            // Connection-relevant edits invalidate an existing connection; say so
            // instead of silently keeping a stale endpoint. Only this preset's
            // connection is dropped — a different provider stays connected.
            if (registry.isConnected(stored.id)) {
                val wasActive = registry.activePresetId() == stored.id
                registry.disconnect(stored.id)
                if (wasActive) stopMonitor()
                runnerFor(stored)?.markStale(
                    stored.id,
                    "Configuration changed — start the model again to reconnect",
                    ModelLifecycleState.STOPPED,
                )
                mutableState.update { it.copy(activeConfig = registry.activeConfig()) }
            }
            reloadPresets()
        }
        return result
    }

    override suspend fun deletePreset(id: String): ForgeResult<Unit, ForgeError> {
        val existing = io { repository.find(id) } ?: return failure(notFound(id))

        val result = io { repository.delete(id) }
        if (result is ForgeResult.Failure) return result

        existing.credentialRef?.let { ref -> io { secretStore.remove(ref) } }
        runners.forEach { it.forget(id) }
        releaseConnection(existing)
        mutableState.update { it.copy(statuses = it.statuses - id) }
        reloadPresets()
        return success(Unit)
    }

    // --- lifecycle ---------------------------------------------------------

    override suspend fun connectQuick(
        request: ModelConnectRequest,
        onPhase: (ModelConnectPhase) -> Unit,
    ): ForgeResult<ModelConnectOutcome, ForgeError> = connectService.connect(request, onPhase)

    override suspend fun selectModel(id: String): ForgeResult<ModelRuntimeStatus, ForgeError> =
        withPreset(id) { preset ->
            io { repository.setActiveId(preset.id) }
            mutableState.update { it.copy(activePresetId = preset.id) }

            val runner = runnerFor(preset)
            val current = runner?.status(preset.id)
            // Already connected and healthy: re-point the gateway, never restart.
            if (current != null && current.isUsable && current.endpoint != null) {
                bind(preset, current.endpoint)
                success(current)
            } else {
                startModel(preset.id)
            }
        }

    override suspend fun startModel(id: String): ForgeResult<ModelRuntimeStatus, ForgeError> =
        withPreset(id) { preset ->
            val runner = runnerFor(preset)
                ?: return@withPreset failure(noRunner(preset))

            io { repository.setActiveId(preset.id) }
            mutableState.update { it.copy(activePresetId = preset.id) }

            applyConnection(preset, io { runner.start(preset) })
        }

    override suspend fun reconnectModel(id: String): ForgeResult<ModelRuntimeStatus, ForgeError> =
        withPreset(id) { preset ->
            val runner = runnerFor(preset) ?: return@withPreset failure(noRunner(preset))
            applyConnection(preset, io { runner.reconnect(preset) })
        }

    override suspend fun stopModel(id: String): ForgeResult<ModelRuntimeStatus, ForgeError> =
        withPreset(id) { preset ->
            val runner = runnerFor(preset) ?: return@withPreset failure(noRunner(preset))
            val result = io { runner.stop(preset) }
            releaseConnection(preset)
            success(result.status)
        }

    override suspend fun checkModelHealth(id: String): ForgeResult<ModelHealth, ForgeError> =
        withPreset(id) { preset ->
            val runner = runnerFor(preset) ?: return@withPreset failure(noRunner(preset))
            val health = io { runner.healthCheck(preset) }
            if (health.isReachable) bind(preset, runner.status(preset.id).endpoint) else releaseConnection(preset)
            success(health)
        }

    override fun activeConfig(): ModelConfig? = mutableState.value.activeConfig

    override fun connections(): Map<String, ModelConfig> = registry.connections()

    /**
     * Live connections first, then every other saved provider that already has a
     * configured endpoint, so the model catalog can read its live list even when
     * that provider is not the connection currently in use.
     *
     * Only providers that can be addressed without runtime discovery are added: a
     * Colab/tunnel preset whose endpoint is only known after the runtime prints it
     * is left out, exactly as before. This is deliberately separate from
     * [connections], which the role → model resolver routes on, so resolution
     * semantics do not change.
     */
    override fun catalogConnections(): Map<String, ModelConfig> {
        val result = LinkedHashMap<String, ModelConfig>(registry.connections())
        mutableState.value.presets.forEach { preset ->
            if (!preset.enabled) return@forEach
            val providerId = preset.providerId
            if (result.containsKey(providerId)) return@forEach
            val url = preset.endpoint.explicitUrl?.takeIf { it.isNotBlank() } ?: return@forEach
            result[providerId] = ModelConfig(
                providerId = providerId,
                baseUrl = url.trimEnd('/') + preset.normalizedApiBasePath,
                model = preset.modelIdentifier,
                // The credential is read from the store, never from a logged field.
                apiKey = catalogCredentials[preset.id]?.takeIf { it.isNotBlank() },
                stream = true,
                // A saved connection's own headers travel with it here too, so asking
                // a custom endpoint for its model list cannot 403 where chat succeeds.
                headers = preset.requestHeaders,
                metadata = mapOf(
                    "modelPresetId" to preset.id,
                    "modelPresetName" to preset.displayName,
                    "providerType" to preset.providerType.name,
                    "catalogOnly" to "true",
                ),
            )
        }
        return result
    }

    override fun onRunnerSessionChanged(presetId: String, attached: Boolean) {
        // Purely informational: the Model Runner browser is a control surface for
        // the runtime, never part of the agent's request path.
        mutableState.update { it.copy(runnerSessions = it.runnerSessions + (presetId to attached)) }
    }

    override fun onAppBackground() {
        foreground = false
        val id = mutableState.value.activePresetId ?: return
        val preset = mutableState.value.presets.firstOrNull { it.id == id } ?: return
        val runner = runnerFor(preset) ?: return
        if (!runner.status(id).isUsable) return
        runner.markStale(
            id,
            "Monitoring paused while the app is in the background; not re-checked yet",
        )
    }

    override fun onAppForeground() {
        foreground = true
        val snapshot = mutableState.value
        val id = snapshot.activePresetId ?: return
        val preset = snapshot.presets.firstOrNull { it.id == id } ?: return
        val runner = runnerFor(preset) ?: return

        scope.launch {
            val health = io { runner.healthCheck(preset) }
            if (health.isReachable) {
                bind(preset, runner.status(preset.id).endpoint)
            } else if (registry.activePresetId() == preset.id) {
                releaseConnection(preset)
            }
        }
    }

    override fun close() {
        stopMonitor()
    }

    // --- internals ---------------------------------------------------------

    private suspend fun applyConnection(
        preset: ModelPreset,
        result: RunnerOperationResult,
    ): ForgeResult<ModelRuntimeStatus, ForgeError> {
        val endpoint = result.status.endpoint
        if (result.succeeded && endpoint != null) {
            bind(preset, endpoint)
            return success(result.status)
        }
        // Only drop the connection when it belongs to this preset; another model
        // that is still online must not be disconnected by this failure.
        if (registry.activePresetId() == preset.id) releaseConnection(preset)
        return failure(
            modelFailure(
                code = ForgeErrorCode.MODEL_OPERATION_FAILED,
                message = result.status.message,
                details = mapOf(
                    "presetId" to preset.id,
                    "state" to result.status.state.name,
                    "failure" to result.status.failure.name,
                ),
            ),
        )
    }

    private suspend fun bind(preset: ModelPreset, endpoint: ModelEndpoint?) {
        if (endpoint == null) return
        val credential = io { credentials.resolve(preset) }
        val config = registry.connect(preset, endpoint, credential)
        mutableState.update { it.copy(activeConfig = config, activePresetId = preset.id) }
        startMonitor(preset)
    }

    private fun releaseConnection(preset: ModelPreset) {
        val wasActive = registry.activePresetId() == preset.id
        registry.disconnect(preset.id)
        if (wasActive) stopMonitor()
        // Another provider may still be connected; only its own connection is gone.
        mutableState.update { it.copy(activeConfig = registry.activeConfig()) }
    }

    private fun startMonitor(preset: ModelPreset) {
        if (!monitorEnabled) return
        monitorJob?.cancel()
        monitorJob = scope.launch {
            while (isActive) {
                delay(preset.health.intervalMillis)
                if (mutableState.value.activePresetId != preset.id) return@launch
                // No network work while the app is in the background: Android
                // cannot guarantee it, so the state is re-checked on return.
                if (!foreground) continue

                val runner = runnerFor(preset) ?: return@launch
                if (!runner.status(preset.id).isUsable) return@launch

                val health = io { runner.healthCheck(preset) }
                if (health.isReachable) continue

                // Losing the connection is expected (a Colab runtime can stop);
                // recovery is one bounded sequence, never an endless poll.
                val reconnected = io { runner.reconnect(preset) }
                applyConnection(preset, reconnected)
                if (!reconnected.succeeded) return@launch
            }
        }
    }

    private fun stopMonitor() {
        monitorJob?.cancel()
        monitorJob = null
    }

    private suspend fun reloadPresets() {
        val presets = io { repository.list() }
        val activeId = io { repository.activeId() }
        cacheCatalogCredentials(presets)
        registerPresetCapabilities(presets)
        mutableState.update { it.copy(presets = presets, activePresetId = activeId) }
    }

    /**
     * Publishes every saved capability declaration to the shared
     * [ModelCapabilityRegistry], so the model a role resolves sees what the user
     * stated for it.
     *
     * This is the only place a declaration becomes authoritative, which is what
     * keeps it scoped: the registry entry is keyed by this preset's own
     * `providerId` + `modelId`, so one declared custom model never confers a
     * capability on any other model — not even another model of the same
     * `openai-compatible` provider. An undeclared model is untouched and stays
     * unknown.
     */
    private fun registerPresetCapabilities(presets: List<ModelPreset>) {
        presets.forEach { preset ->
            val declaration = preset.declaredCapabilities
            if (declaration.isEmpty) return@forEach
            val modelId = normalizeModelId(preset.modelIdentifier)
            if (modelId.isBlank()) return@forEach
            val providerId = preset.providerId
            capabilityRegistry.registerOrUpdate(
                declaration.applyTo(capabilityRegistry.profile(providerId, modelId)),
            )
        }
    }

    /**
     * Resolves each preset's credential once per (re)load. The map is read by
     * [catalogConnections] on the calling thread, so the secret store is never hit
     * from there. A removed preset's entry is dropped with it.
     */
    private suspend fun cacheCatalogCredentials(presets: List<ModelPreset>) {
        val ids = presets.map { it.id }.toHashSet()
        catalogCredentials.keys.retainAll(ids)
        presets.forEach { preset ->
            val credential = runCatching { io { credentials.resolve(preset) } }.getOrNull()
            catalogCredentials[preset.id] = credential.orEmpty()
        }
    }

    private fun runnerFor(preset: ModelPreset): ModelRunner? = runners.firstOrNull { it.supports(preset) }

    private fun noRunner(preset: ModelPreset): ForgeError = modelFailure(
        code = ForgeErrorCode.MODEL_RUNNER_UNAVAILABLE,
        message = "No runner handles ${preset.providerType.displayName} models yet",
        details = mapOf("presetId" to preset.id, "providerType" to preset.providerType.name),
    )

    private suspend fun storeCredential(existingRef: String?, credential: String?): String? {
        if (credential.isNullOrBlank()) return existingRef
        val ref = existingRef?.takeIf { it.isNotBlank() } ?: "model-credential-${UUID.randomUUID()}"
        io { secretStore.put(ref, credential) }
        // Never log the secret or the reference value beyond its id.
        log.info("Model credential stored", mapOf("ref" to ref, "encrypted" to secretStore.persistent))
        return ref
    }

    private suspend fun <T> withPreset(
        id: String,
        block: suspend (ModelPreset) -> ForgeResult<T, ForgeError>,
    ): ForgeResult<T, ForgeError> {
        val preset = io { repository.find(id) } ?: return failure(notFound(id))
        return block(preset)
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(ioDispatcher) { block() }

    private suspend fun onRunnerStatuses(snapshot: Map<String, ModelRuntimeStatus>) {
        if (snapshot.isEmpty()) return
        mutableState.update { it.copy(statuses = it.statuses + snapshot) }

        // Remember only the state name — an endpoint URL is a capability and is
        // never persisted.
        snapshot.forEach { (presetId, status) ->
            val name = status.state.name
            if (persistedStatuses[presetId] == name) return@forEach
            persistedStatuses[presetId] = name
            runCatching { io { repository.setLastStatus(presetId, name) } }
        }
    }

    /** Builds the status shown before anything has been checked in this process. */
    private suspend fun restoredStatus(preset: ModelPreset): ModelRuntimeStatus {
        val last = runCatching { io { repository.lastStatus(preset.id) } }.getOrNull()
        val state = when (ModelLifecycleState.entries.firstOrNull { it.name == last }) {
            ModelLifecycleState.ONLINE,
            ModelLifecycleState.DEGRADED,
            ModelLifecycleState.STARTING,
            ModelLifecycleState.CONNECTING,
            ModelLifecycleState.CHECKING,
            ModelLifecycleState.STOPPING,
            -> ModelLifecycleState.UNKNOWN

            ModelLifecycleState.FAILED -> ModelLifecycleState.FAILED
            ModelLifecycleState.DISCONNECTED -> ModelLifecycleState.DISCONNECTED
            ModelLifecycleState.STOPPED -> ModelLifecycleState.STOPPED
            else -> ModelLifecycleState.UNKNOWN
        }
        val message = when (state) {
            ModelLifecycleState.UNKNOWN -> "Not checked since the app started"
            ModelLifecycleState.FAILED -> "The last connection attempt failed"
            ModelLifecycleState.DISCONNECTED -> "Disconnected"
            else -> "Stopped"
        }
        return statusFor(preset, state, message, clock()).copy(
            failure = if (state == ModelLifecycleState.FAILED) ModelRuntimeFailure.UNKNOWN else ModelRuntimeFailure.NONE,
        )
    }
}
