package com.agentx.app.tools

import com.agentx.app.core.timeout.AgentTimeouts

/**
 * Chooses which central [AgentTimeouts] budget applies to one tool call.
 *
 * The categories differ by orders of magnitude — reading a file and running a
 * Gradle build are not comparable — so the executor never falls back to a single
 * short constant. A tool's declared category is authoritative; a tool that only
 * declares capabilities is classified from those; anything unrecognised gets the
 * generic tool budget rather than a short one.
 *
 * An explicit [ToolExecutionContext.timeoutMillis] always wins over this.
 */
object ToolTimeouts {

    fun forCall(
        category: ToolCategory? = null,
        capabilities: Set<ToolCapability> = emptySet(),
        timeouts: AgentTimeouts = AgentTimeouts.DEFAULT,
    ): Long = when {
        // Shell first: a command tool reports SHELL even when it also touches files.
        ToolCapability.SHELL in capabilities || category == ToolCategory.COMMAND ->
            timeouts.shellCommandMillis

        category == ToolCategory.GIT -> timeouts.shellCommandMillis

        ToolCapability.NETWORK in capabilities ||
            category == ToolCategory.WEB ||
            category == ToolCategory.BROWSER ||
            category == ToolCategory.MCP -> timeouts.networkMillis

        category == ToolCategory.FILESYSTEM || category == ToolCategory.SEARCH ->
            timeouts.fileOperationMillis

        else -> timeouts.toolExecutionMillis
    }
}
