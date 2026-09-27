package dev.forge.ide.agent

import dev.forge.ide.core.architecture.LayerDescriptor
import dev.forge.ide.core.architecture.LayerStatus

val AGENT_LAYER = LayerDescriptor(
    id = "agent",
    title = "Agent Core",
    summary = "Runs the Main Agent and specialized sub-agents over the model, tool, and workspace layers.",
    status = LayerStatus.ACTIVE,
)
