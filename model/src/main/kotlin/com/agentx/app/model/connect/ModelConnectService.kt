package com.agentx.app.model.connect

import com.agentx.app.core.ForgeError
import com.agentx.app.core.ForgeErrorCode
import com.agentx.app.core.ForgeResult
import com.agentx.app.core.errorOrNull
import com.agentx.app.core.failure
import com.agentx.app.core.logging.ForgeLogger
import com.agentx.app.core.success
import com.agentx.app.core.valueOrNull
import com.agentx.app.model.capability.InMemoryModelCapabilityRegistry
import com.agentx.app.model.capability.ModelCapabilityProfile
import com.agentx.app.model.capability.ModelCapabilityRegistry
import com.agentx.app.model.diagnostics.ApiOperation
import com.agentx.app.model.diagnostics.ApiTrace
import com.agentx.app.model.diagnostics.configuredFlag
import com.agentx.app.model.diagnostics.sanitizeForLog
import com.agentx.app.model.http.HttpTransport
import com.agentx.app.model.http.UrlConnectionHttpTransport
import com.agentx.app.model.manager.ModelManager
import com.agentx.app.model.preset.ModelProviderIds
import com.agentx.app.model.preset.EndpointConfig
import com.agentx.app.model.preset.EndpointDiscoveryMode
import com.agentx.app.model.preset.HealthCheckConfig
import com.agentx.app.model.preset.ModelApiProtocol
import com.agentx.app.model.preset.ModelPreset
import com.agentx.app.model.preset.ModelProviderType
import com.agentx.app.model.preset.TunnelConfig
import com.agentx.app.model.preset.TunnelType
import com.agentx.app.model.runtime.ModelRuntimeStatus
import kotlin.coroutines.cancellation.CancellationException

enum class ModelConnectPhase(val displayName: String) {
    CONNECTING("Connecting…"),
    DISCOVERING("Discovering API…"),
    DETECTING_MODEL("Detecting model…"),
    TESTING("Testing…"),
    CONNECTED("Connected"),
}

data class ModelConnectRequest(
    val presetId: String? = null,
    val displayName: String,
    val setupKind: ModelSetupKind = ModelSetupKind.CUSTOM,
    val endpoint: String = "",
    val credential: String? = null,
    val clearCredential: Boolean = false,
    /** Friendly override; blank means auto-detect. */
    val modelIdentifier: String = "",
    val apiProtocol: ModelApiProtocol? = null,
    val apiBasePath: String? = null,
    val providerType: ModelProviderType? = null,
    val healthPath: String? = null,
    val healthTimeoutMillis: Long? = null,
    val serverPort: Int? = null,
    val enabled: Boolean = true,
    val startupScript: String = "",
    val tunnelType: TunnelType? = null,
    val tunnelMarker: String? = null,
    val colabNotebookUrl: String? = null,
    val endpointMode: EndpointDiscoveryMode? = null,
)

sealed interface ModelConnectOutcome {
    data class Connected(
        val preset: ModelPreset,
        val status: ModelRuntimeStatus,
    ) : ModelConnectOutcome

    data class NeedsModelChoice(
        val models: List<String>,
        val message: String = "Several models were found. Choose one to continue.",
    ) : ModelConnectOutcome
}

/**
 * One-tap connect: normalize → discover → pick a model → chat-probe → save → start.
 *
 * UI and ViewModels talk only to [ModelManager]; this type is the manager's
 * implementation detail. It never contacts HTTP itself except through
 * [ModelApiDiscovery] and [ChatCapabilityProbe] (gateway).
 */
class ModelConnectService(
    private val manager: ModelManager,
    transport: HttpTransport = UrlConnectionHttpTransport(),
    private val discovery: ModelApiDiscovery = ModelApiDiscovery(transport),
    private val chatProbe: ChatCapabilityProbe = ChatCapabilityProbe(),
    private val resolveStoredCredential: suspend (ModelPreset) -> String? = { null },
    /** Structured Developer Log sink for the connect orchestration; null disables it. */
    private val logger: ForgeLogger? = null,
    private val capabilityRegistry: ModelCapabilityRegistry = InMemoryModelCapabilityRegistry(),
) {

    suspend fun connect(
        request: ModelConnectRequest,
        onPhase: (ModelConnectPhase) -> Unit = {},
    ): ForgeResult<ModelConnectOutcome, ForgeError> {
        // The provider identity the user asked for (`gemini`, `groq`, `custom`),
        // so one search in the Developer Log follows a whole connect attempt.
        val trace = ApiTrace.create(logger, request.setupKind.id, ApiOperation.CONNECT)
        trace.stage(
            "START",
            "operation" to "connect",
            "setupKind" to request.setupKind.id,
            "provider" to request.setupKind.id,
            "preset" to (request.presetId ?: "new"),
            "displayName" to request.displayName.ifBlank { "-" },
            "endpoint" to (request.endpoint.takeIf { it.isNotBlank() }?.let(::diagnosticPath) ?: "-"),
            "modelOverride" to request.modelIdentifier.ifBlank { "-" },
            "protocol" to (request.apiProtocol?.name ?: "auto"),
            "hasCredential" to configuredFlag(!request.credential.isNullOrBlank()),
        )
        // Every phase is logged next to the existing callback, so the UI progress
        // and the Developer Log tell the same story.
        val reportPhase: (ModelConnectPhase) -> Unit = { phase ->
            trace.stage("PHASE", "phase" to phase.name)
            onPhase(phase)
        }
        // A terminal failure line for the orchestration itself; the discovery,
        // provider and probe traces beneath it already say which stage failed.
        fun fail(message: String, kind: DiscoveryFailureKind): ForgeResult<ModelConnectOutcome, ForgeError> {
            trace.failure(
                "ERROR",
                "stage" to "connect",
                "kind" to kind.name,
                "message" to sanitizeForLog(message),
            )
            return failure(
                ForgeError(
                    code = ForgeErrorCode.MODEL_OPERATION_FAILED,
                    message = message,
                    details = mapOf("kind" to kind.name),
                ),
            )
        }

        reportPhase(ModelConnectPhase.CONNECTING)
        val existing = request.presetId?.let { manager.preset(it) }

        val prepared = prepare(request, existing)
            ?: return failure(
                ForgeError(
                    code = ForgeErrorCode.MODEL_PRESET_INVALID,
                    message = "Name must not be blank",
                    details = mapOf("errors" to listOf("Name must not be blank")),
                ),
            )

        val inputError = validateInput(request, prepared.setupKind)
        if (inputError != null) {
            return failure(
                ForgeError(
                    code = ForgeErrorCode.MODEL_PRESET_INVALID,
                    message = inputError,
                    details = mapOf("errors" to listOf(inputError)),
                ),
            )
        }

        val credential = credentialForProbe(request, existing)

        reportPhase(ModelConnectPhase.DISCOVERING)
        val discovered = try {
            discover(request, prepared, credential)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return fail("The model endpoint could not be reached.", DiscoveryFailureKind.UNKNOWN)
        }

        val api = when (discovered) {
            is DiscoveryResult.Failed -> return fail(discovered.message, discovered.kind)
            is DiscoveryResult.NeedsModelChoice -> {
                if (request.modelIdentifier.isBlank()) {
                    registerDiscoveredModels(
                        providerId = ModelProviderIds.forPreset(request.setupKind.id, discovered.api.protocol),
                        modelIds = discovered.modelIds,
                        catalogFallback = discovered.api.catalogFallback,
                        local = isLocalEndpoint(discovered.api.rootUrl),
                    )
                    trace.stage(
                        "COMPLETE",
                        "outcome" to "needs-choice",
                        "models" to discovered.modelIds.size,
                        "selected" to "none",
                    )
                    return success(ModelConnectOutcome.NeedsModelChoice(discovered.modelIds))
                }
                discovered.api.copy(selectedModelId = request.modelIdentifier.trim())
            }
            is DiscoveryResult.Found -> discovered.api
        }

        trace.stage(
            "DISCOVERED",
            "rootUrl" to diagnosticPath(api.rootUrl),
            "basePath" to api.apiBasePath.ifBlank { "-" },
            "protocol" to api.protocol.name,
            "catalog" to api.modelIds.size,
            "selected" to (api.selectedModelId ?: "none"),
            "fallback" to api.catalogFallback,
        )
        reportPhase(ModelConnectPhase.DETECTING_MODEL)
        val modelId = request.modelIdentifier.trim().ifBlank { api.selectedModelId.orEmpty() }
        if (modelId.isBlank()) {
            return if (api.modelIds.size > 1) {
                success(ModelConnectOutcome.NeedsModelChoice(api.modelIds))
            } else {
                fail(
                    "The endpoint is reachable but no model id could be detected. Set one under Advanced.",
                    DiscoveryFailureKind.UNSUPPORTED,
                )
            }
        }

        val protocol = request.apiProtocol ?: api.protocol
        val apiBase = request.apiBasePath?.takeIf { it.isNotBlank() } ?: api.apiBasePath
        val providerType = request.providerType ?: inferProviderType(request, api.rootUrl)

        val draft = buildPreset(
            request = request,
            existing = existing,
            displayName = prepared.displayName,
            rootUrl = api.rootUrl,
            apiBasePath = apiBase,
            protocol = protocol,
            providerType = providerType,
            modelId = modelId,
            listedModels = api.modelIds,
            catalogFallback = api.catalogFallback,
        )

        reportPhase(ModelConnectPhase.TESTING)
        val probe = try {
            chatProbe.verify(
                preset = draft,
                rootUrl = api.rootUrl,
                apiBasePath = apiBase,
                modelId = modelId,
                credential = credential,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return fail("The chat endpoint could not be verified.", DiscoveryFailureKind.UNKNOWN)
        }
        trace.stage(
            "VERIFIED",
            "model" to modelId,
            "ok" to probe.succeeded,
            "status" to (probe.httpStatus ?: "-"),
            "kind" to (probe.kind?.name ?: "-"),
        )
        if (!probe.succeeded) {
            return fail(probe.message, probe.kind ?: DiscoveryFailureKind.UNKNOWN)
        }

        // Where a model list becomes a saved preset: the catalog count, the id
        // actually stored and whether the id came from the live list or the
        // built-in fallback is exactly what the UI picker is built from.
        registerConnectedModels(
            providerId = storedProviderId(draft),
            listedModels = api.modelIds,
            selectedModelId = modelId,
            catalogFallback = api.catalogFallback,
            local = isLocalEndpoint(api.rootUrl),
        )
        val stored = persist(draft, request, existing) ?: return fail(
            "The model could not be saved.",
            DiscoveryFailureKind.UNKNOWN,
        )
        trace.stage(
            "SAVED",
            "preset" to stored.id,
            "provider" to stored.providerId,
            "model" to stored.modelIdentifier,
            "setupKind" to stored.setupKind,
            "catalogFallback" to api.catalogFallback,
            "listedModels" to api.modelIds.size,
            "requiresModelInList" to stored.health.requireModelInList,
        )

        val started = manager.selectModel(stored.id)
        val status = started.valueOrNull()
            ?: return fail(
                started.errorOrNull()?.message ?: "The model could not be started.",
                DiscoveryFailureKind.UNKNOWN,
            )
        if (!status.isUsable) {
            return fail(status.message, DiscoveryFailureKind.UNKNOWN)
        }

        reportPhase(ModelConnectPhase.CONNECTED)
        trace.stage(
            "COMPLETE",
            "outcome" to "connected",
            "preset" to stored.id,
            "model" to stored.modelIdentifier,
            "state" to status.state.name,
            "connections" to manager.connections().size,
        )
        return success(ModelConnectOutcome.Connected(stored, status))
    }

    private data class Prepared(
        val displayName: String,
        val setupKind: ModelSetupKind,
    )

    private fun prepare(request: ModelConnectRequest, existing: ModelPreset?): Prepared? {
        val name = request.displayName.trim().ifBlank {
            existing?.displayName?.takeIf { it.isNotBlank() }
                ?: request.setupKind.displayName.takeIf { request.setupKind != ModelSetupKind.CUSTOM }
                ?: ""
        }
        if (name.isBlank()) return null
        return Prepared(name, request.setupKind)
    }

    private fun validateInput(request: ModelConnectRequest, kind: ModelSetupKind): String? {
        if (kind.requiresApiKey && request.credential.isNullOrBlank() && request.presetId == null) {
            return "An API key is required for ${kind.displayName}."
        }
        if (kind.requiresApiKey && request.credential.isNullOrBlank() && request.clearCredential) {
            return "An API key is required for ${kind.displayName}."
        }
        if (kind.showsEndpointField && request.endpoint.isBlank()) {
            return "Endpoint is required."
        }
        if (kind.showsEndpointField) {
            when (val outcome = EndpointResolver.resolve(request.endpoint)) {
                is EndpointResolver.Outcome.Invalid -> return outcome.reason
                is EndpointResolver.Outcome.Ok -> Unit
            }
        }
        return null
    }

    private suspend fun discover(
        request: ModelConnectRequest,
        prepared: Prepared,
        credential: String?,
    ): DiscoveryResult {
        val preferred = request.modelIdentifier.trim().ifBlank { null }
        val spec = KnownModelProviders.spec(prepared.setupKind)
        return if (spec != null) {
            discovery.discoverKnown(spec, credential, preferred)
        } else {
            discovery.discover(request.endpoint, credential, preferred)
        }
    }

    private fun inferProviderType(request: ModelConnectRequest, rootUrl: String): ModelProviderType {
        request.providerType?.let { return it }
        val host = rootUrl.substringAfter("://").substringBefore('/').substringBefore(':')
        return EndpointResolver.inferProviderType(host)
    }

    private suspend fun credentialForProbe(request: ModelConnectRequest, existing: ModelPreset?): String? {
        if (request.clearCredential) return null
        request.credential?.takeIf { it.isNotBlank() }?.let { return it }
        val stored = existing ?: return null
        return resolveStoredCredential(stored)
    }

    private fun buildPreset(
        request: ModelConnectRequest,
        existing: ModelPreset?,
        displayName: String,
        rootUrl: String,
        apiBasePath: String,
        protocol: ModelApiProtocol,
        providerType: ModelProviderType,
        modelId: String,
        listedModels: List<String>,
        catalogFallback: Boolean,
    ): ModelPreset {
        val listed = !catalogFallback && listedModels.contains(modelId)
        val timeout = request.healthTimeoutMillis ?: existing?.health?.timeoutMillis
            ?: HealthCheckConfig.DEFAULT_TIMEOUT_MILLIS
        val mode = request.endpointMode
            ?: EndpointDiscoveryMode.CONFIGURED_ENDPOINT
        return ModelPreset(
            id = existing?.id ?: request.presetId.orEmpty(),
            displayName = displayName,
            providerType = providerType,
            modelIdentifier = modelId,
            apiProtocol = protocol,
            apiBasePath = apiBasePath.ifBlank { "" },
            credentialRef = existing?.credentialRef?.takeUnless { request.clearCredential },
            startupScript = request.startupScript.ifBlank { existing?.startupScript.orEmpty() },
            serverPort = request.serverPort ?: existing?.serverPort,
            endpoint = EndpointConfig(
                mode = mode,
                explicitUrl = rootUrl,
            ),
            tunnel = TunnelConfig(
                type = request.tunnelType ?: TunnelType.NONE,
                marker = request.tunnelMarker?.ifBlank { null } ?: TunnelConfig.DEFAULT_MARKER,
            ),
            health = (existing?.health ?: HealthCheckConfig()).copy(
                path = request.healthPath?.trim()?.ifBlank { null },
                timeoutMillis = timeout,
                requireModelInList = listed,
            ),
            colab = existing?.colab,
            enabled = request.enabled,
            setupKind = request.setupKind.id,
            createdAtMillis = existing?.createdAtMillis ?: 0L,
            updatedAtMillis = existing?.updatedAtMillis ?: 0L,
        )
    }

    private suspend fun persist(
        draft: ModelPreset,
        request: ModelConnectRequest,
        existing: ModelPreset?,
    ): ModelPreset? {
        val credential = request.credential?.takeIf { it.isNotBlank() }
        val result = if (existing == null) {
            manager.createPreset(draft, credential)
        } else {
            manager.updatePreset(draft, credential, clearCredential = request.clearCredential)
        }
        return result.valueOrNull()
    }

    private fun storedProviderId(preset: ModelPreset): String = preset.providerId

    private fun registerDiscoveredModels(
        providerId: String,
        modelIds: List<String>,
        catalogFallback: Boolean,
        local: Boolean,
    ) {
        if (catalogFallback) return
        modelIds.forEach { modelId ->
            val id = normalizeModelId(modelId)
            if (id.isBlank()) return@forEach
            capabilityRegistry.registerOrUpdate(
                ModelCapabilityProfile.discovered(
                    providerId = providerId,
                    modelId = id,
                    local = local,
                ),
            )
        }
    }

    private fun registerConnectedModels(
        providerId: String,
        listedModels: List<String>,
        selectedModelId: String,
        catalogFallback: Boolean,
        local: Boolean,
    ) {
        if (!catalogFallback) {
            registerDiscoveredModels(providerId, listedModels, catalogFallback = false, local = local)
        }
        val selected = normalizeModelId(selectedModelId)
        if (selected.isBlank()) return
        capabilityRegistry.registerOrUpdate(
            ModelCapabilityProfile.connected(
                providerId = providerId,
                modelId = selected,
                local = local,
            ),
        )
    }

    private fun isLocalEndpoint(rootUrl: String): Boolean {
        val host = runCatching { java.net.URI(rootUrl.trim()).host?.lowercase() }.getOrNull() ?: return false
        return host == "localhost" || host == "127.0.0.1" || host == "::1" || host.endsWith(".local")
    }

}
