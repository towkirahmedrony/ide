package dev.forge.ide.model

import dev.forge.ide.core.architecture.LayerDescriptor
import dev.forge.ide.core.architecture.LayerStatus

val MODEL_LAYER = LayerDescriptor(
    id = "model",
    title = "Model Gateway",
    summary = "Routes model requests to local or remote providers behind one provider-agnostic " +
        "interface, and owns saved model presets, runners, endpoint discovery and health checks.",
    status = LayerStatus.ACTIVE,
)
