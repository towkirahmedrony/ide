package dev.forge.ide.ui

import dev.forge.ide.core.architecture.LayerDescriptor
import dev.forge.ide.core.architecture.LayerStatus

val UI_LAYER = LayerDescriptor(
    id = "ui",
    title = "IDE UI",
    summary = "Native Jetpack Compose shell that surfaces agent activity and workspace state.",
    status = LayerStatus.ACTIVE,
)
