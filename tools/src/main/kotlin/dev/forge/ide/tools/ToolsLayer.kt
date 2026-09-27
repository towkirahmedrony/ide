package dev.forge.ide.tools

import dev.forge.ide.core.architecture.LayerDescriptor
import dev.forge.ide.core.architecture.LayerStatus

val TOOLS_LAYER = LayerDescriptor(
    id = "tools",
    title = "Tool System",
    summary = "Registers, routes, authorizes, and executes agent tools behind one contract.",
    status = LayerStatus.ACTIVE,
)
