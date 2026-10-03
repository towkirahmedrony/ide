package com.agentx.app.tools

/**
 * Whether the runtime can actually run a tool yet.
 *
 * Tool *declaration* and tool *availability* are deliberately separate. A tool
 * can be declared, described to the model and listed in a role's policy while its
 * implementation does not exist (shell, git write, browser, MCP, GitHub). Such a
 * tool is [UNAVAILABLE]: the architecture can represent it honestly and a role
 * policy can reference it, but nothing may execute or advertise it as working.
 *
 * This is the single place the distinction is made, so no caller has to infer
 * availability from an error message or a metadata string.
 */
enum class ToolAvailability {
    /** The runtime implements this tool and it may run when policy allows. */
    AVAILABLE,

    /**
     * Declared but not implemented, or implemented but not enabled in this build.
     * Never offered to the model and always rejected with a structured
     * "tool unavailable" error rather than an execution failure.
     */
    UNAVAILABLE,
    ;

    val isAvailable: Boolean get() = this == AVAILABLE
}

/**
 * The availability a tool actually has, combining its declared state with the
 * existing `implemented=false` marker a provider-synced tool carries.
 *
 * Provider tools are described by data, not by a Kotlin class, so the marker is
 * honoured here rather than at every call site.
 */
val ToolDefinition.effectiveAvailability: ToolAvailability
    get() = when {
        availability == ToolAvailability.UNAVAILABLE -> ToolAvailability.UNAVAILABLE
        metadata[IMPLEMENTED_METADATA_KEY] == "false" -> ToolAvailability.UNAVAILABLE
        else -> ToolAvailability.AVAILABLE
    }

/** Metadata key a declared-but-unimplemented tool sets to `"false"`. */
const val IMPLEMENTED_METADATA_KEY: String = "implemented"
