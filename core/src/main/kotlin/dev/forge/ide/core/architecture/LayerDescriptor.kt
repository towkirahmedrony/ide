package dev.forge.ide.core.architecture

/** Lifecycle stage of an architectural layer. */
enum class LayerStatus {
    /** Designed but not started. */
    PLANNED,

    /** Interfaces/contracts defined; no implementation yet. */
    CONTRACT_ONLY,

    /** Implemented and wired into the running application. */
    ACTIVE,
}

/**
 * Describes one layer of the platform. Layer metadata is intentionally plain
 * data so it can be listed by the UI and validated by startup health checks.
 */
data class LayerDescriptor(
    val id: String,
    val title: String,
    val summary: String,
    val status: LayerStatus,
)

/** The shared kernel: primitives every other layer builds on. */
val CORE_LAYER = LayerDescriptor(
    id = "core",
    title = "Core Kernel",
    summary = "Result, error, logging, module, health, and config primitives shared by all layers.",
    status = LayerStatus.ACTIVE,
)
