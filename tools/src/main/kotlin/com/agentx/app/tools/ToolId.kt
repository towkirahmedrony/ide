package com.agentx.app.tools

/**
 * Unique identifier of a registered tool. Distinct from display titles so the
 * registry, router, and model layer share one stable key.
 */
@JvmInline
value class ToolId(val value: String) {
    init {
        require(value.isNotBlank()) { "Tool id must not be blank" }
        require(value.matches(ToolDefinition.NAME_PATTERN)) {
            "Tool id '$value' must contain only letters, digits, '.', '_', or '-'"
        }
    }

    override fun toString(): String = value
}
