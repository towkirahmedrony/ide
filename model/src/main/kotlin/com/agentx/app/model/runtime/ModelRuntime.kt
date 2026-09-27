package com.agentx.app.model.runtime

/**
 * A model endpoint that passed validation: just the URL plus where it came from.
 *
 * This is the value the Model Gateway is interested in. Nothing downstream of it
 * knows or cares whether the model runs in Colab, on the phone, or on a server.
 */
data class ModelEndpoint(
    val url: String,
    val source: EndpointSource,
) {
    /** Host shown in the UI. Never includes credentials (there are none). */
    val host: String get() = url.substringAfter("://").substringBefore('/')
}

/** Where a detected endpoint came from, shown to the user. */
enum class EndpointSource(val displayName: String) {
    CONFIGURED("Configured endpoint"),
    RUNTIME_OUTPUT("Detected from runtime output"),
    DEVICE_LOCAL("Device local server"),
}

/**
 * Lifecycle of one model connection.
 *
 * [STARTING], [CONNECTING], [CHECKING] and [STOPPING] are transient: every
 * operation that enters one of them is bounded by the connection policy, so the
 * UI can never be left spinning forever.
 */
enum class ModelLifecycleState(val displayName: String, val transient: Boolean = false) {
    /** No preset selected yet. */
    NOT_CONFIGURED("Not configured"),

    /** Nothing is known yet: the app has not checked since it started. */
    UNKNOWN("Unknown"),

    STOPPED("Stopped"),
    STARTING("Starting", transient = true),
    CONNECTING("Connecting", transient = true),
    CHECKING("Checking", transient = true),
    ONLINE("Online"),
    DEGRADED("Degraded"),
    DISCONNECTED("Disconnected"),
    STOPPING("Stopping", transient = true),
    FAILED("Failed"),
}

/** Why a connection attempt ended the way it did. */
enum class ModelRuntimeFailure(val displayName: String) {
    NONE(""),
    INVALID_PRESET("The model configuration is incomplete"),
    DISABLED("This model is disabled"),

    /**
     * No endpoint appeared. For a Colab model this usually means the runtime is
     * no longer running — Android cannot start or keep a Colab runtime alive.
     */
    RUNTIME_NOT_DETECTED("Model runtime stopped"),

    ENDPOINT_INVALID("The detected endpoint is not a usable URL"),
    MODEL_API_UNREACHABLE("The endpoint is reachable but the model API is not"),
    TIMEOUT("The operation timed out"),
    CANCELLED("The operation was cancelled"),
    NO_RUNNER("No runner handles this provider type"),
    UNKNOWN("The operation failed"),
}

/**
 * Status of one preset's connection. Always carries a human-readable [message]
 * so no screen has to invent copy for a state.
 */
data class ModelRuntimeStatus(
    val presetId: String,
    val state: ModelLifecycleState,
    val message: String,
    val detail: String? = null,
    val endpoint: ModelEndpoint? = null,
    val failure: ModelRuntimeFailure = ModelRuntimeFailure.NONE,
    /** Attempts used in the current operation, for progress display. */
    val attempt: Int = 0,
    val updatedAtMillis: Long = 0L,
    /**
     * True when the user has to act in the Model Runner (start/restart the
     * runtime or its tunnel) before this model can come online.
     */
    val awaitingRuntime: Boolean = false,
) {
    val isTransient: Boolean get() = state.transient

    /** Whether the model API answered a successful health check. */
    val isUsable: Boolean get() = state == ModelLifecycleState.ONLINE || state == ModelLifecycleState.DEGRADED

    companion object {
        fun notConfigured(presetId: String, now: Long = 0L): ModelRuntimeStatus = ModelRuntimeStatus(
            presetId = presetId,
            state = ModelLifecycleState.NOT_CONFIGURED,
            message = "Not configured",
            updatedAtMillis = now,
        )
    }
}

/** Result of a runner operation: the resulting status plus the health it saw. */
data class RunnerOperationResult(
    val status: ModelRuntimeStatus,
    val health: ModelHealth? = null,
) {
    val succeeded: Boolean get() = status.isUsable
}
