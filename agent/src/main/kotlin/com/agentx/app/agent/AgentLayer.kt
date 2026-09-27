package com.agentx.app.agent

import com.agentx.app.core.architecture.LayerDescriptor
import com.agentx.app.core.architecture.LayerStatus

val AGENT_LAYER = LayerDescriptor(
    id = "agent",
    title = "Agent Core",
    summary = "Runs the Main Agent and specialized sub-agents over the model, tool, and workspace layers.",
    status = LayerStatus.ACTIVE,
)
