package com.agentx.app.tools

import com.agentx.app.core.architecture.LayerDescriptor
import com.agentx.app.core.architecture.LayerStatus

val TOOLS_LAYER = LayerDescriptor(
    id = "tools",
    title = "Tool System",
    summary = "Registers, routes, authorizes, and executes agent tools behind one contract.",
    status = LayerStatus.ACTIVE,
)
