package dev.forge.ide.core

/** Stable, coarse-grained error categories shared across the platform. */
enum class ForgeErrorCode {
    CONFIG_INVALID,
    MODULE_INIT_FAILED,
    DUPLICATE_MODULE,
    DUPLICATE_SERVICE,
    SERVICE_NOT_FOUND,
    ARCHITECTURE_INVALID,
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
