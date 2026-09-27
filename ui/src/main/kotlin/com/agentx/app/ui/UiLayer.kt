package com.agentx.app.ui

import com.agentx.app.core.architecture.LayerDescriptor
import com.agentx.app.core.architecture.LayerStatus

val UI_LAYER = LayerDescriptor(
    id = "ui",
    title = "IDE UI",
    summary = "Native Jetpack Compose shell that surfaces agent activity and workspace state.",
    status = LayerStatus.ACTIVE,
)
