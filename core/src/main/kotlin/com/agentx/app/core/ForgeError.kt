package com.agentx.app.core

/** Stable, coarse-grained error categories shared across the platform. */
enum class ForgeErrorCode {
    CONFIG_INVALID,
    MODULE_INIT_FAILED,
    DUPLICATE_MODULE,
    DUPLICATE_SERVICE,
    SERVICE_NOT_FOUND,
    ARCHITECTURE_INVALID,
    /** A model preset failed validation. */
    MODEL_PRESET_INVALID,
    /** No model preset exists for the requested id. */
    MODEL_PRESET_NOT_FOUND,
    /** A model lifecycle operation (start/stop/reconnect/health) did not succeed. */
    MODEL_OPERATION_FAILED,
    /** No runner is registered for the preset's provider type. */
    MODEL_RUNNER_UNAVAILABLE,
    /** A connection failed validation. */
    CONNECTION_INVALID,
    /** No connection exists for the requested id. */
    CONNECTION_NOT_FOUND,
    /** A connection with the same identity already exists. */
    CONNECTION_DUPLICATE,
    /** A connection lifecycle operation did not succeed. */
    CONNECTION_OPERATION_FAILED,
    /** A tool requested a connection that is missing, disabled, or unauthorized. */
    CONNECTION_UNAUTHORIZED,
    /** The connection type has no tester implementation yet. */
    CONNECTION_TEST_UNSUPPORTED,
    UNKNOWN,
}

/**
 * Base error type for the platform. Carries a stable [code] so callers can
 * branch on failure categories instead of parsing messages.
 */
class ForgeError(
    val code: ForgeErrorCode,
    message: String,
    cause: Throwable? = null,
    val details: Map<String, Any?> = emptyMap(),
) : RuntimeException(message, cause) {

    override fun toString(): String = "ForgeError(code=$code, message=$message)"
}

/** Wraps an arbitrary throwable so it can travel through typed error channels. */
fun Throwable.toForgeError(code: ForgeErrorCode = ForgeErrorCode.UNKNOWN): ForgeError =
    this as? ForgeError ?: ForgeError(code, message ?: this::class.simpleName.orEmpty(), this)
