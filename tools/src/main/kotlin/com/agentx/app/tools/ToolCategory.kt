package com.agentx.app.tools

/**
 * Coarse grouping for tools. Categories whose real implementation is not ready
 * exist so future tools can register without changing the router or agent loop.
 */
enum class ToolCategory {
    FILESYSTEM,
    SEARCH,
    COMMAND,
    GIT,
    WEB,
    BROWSER,
    MCP,
    OTHER,
}
