package dev.forge.ide.ui.ide.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.forge.ide.model.manager.ModelManagerState
import dev.forge.ide.model.preset.ModelPreset
import dev.forge.ide.model.runtime.ModelLifecycleState
import dev.forge.ide.model.runtime.ModelRuntimeFailure
import dev.forge.ide.model.runtime.ModelRuntimeStatus
import dev.forge.ide.ui.ide.components.IdeCard
import dev.forge.ide.ui.ide.components.IdeDot
import dev.forge.ide.ui.ide.components.IdeEmptyState
import dev.forge.ide.ui.ide.components.IdeLabelValue
import dev.forge.ide.ui.ide.components.IdeSectionLabel
import dev.forge.ide.ui.ide.components.IdeSpacer
import dev.forge.ide.ui.ide.components.IdeStatusPill
import dev.forge.ide.ui.ide.components.IdeTopBar
import dev.forge.ide.ui.theme.ForgeAmber
import dev.forge.ide.ui.theme.ForgeCanvas
import dev.forge.ide.ui.theme.ForgeDanger
import dev.forge.ide.ui.theme.ForgeInk
import dev.forge.ide.ui.theme.ForgeMint
import dev.forge.ide.ui.theme.ForgeMuted
import dev.forge.ide.ui.theme.ForgePeriwinkle

/**
 * Model Manager: saved models and what can be done with each one.
 *
 * The screen renders state and forwards intent; every lifecycle decision belongs
 * to the Model Manager. A model that is not verified never claims to be online.
 */
@Composable
fun ModelsScreen(
    state: ModelManagerState,
    busyPresetId: String?,
    message: String?,
    credentialsPersistent: Boolean,
    onBack: () -> Unit,
    onAddModel: () -> Unit,
    onEdit: (String) -> Unit,
    onUse: (String) -> Unit,
    onStart: (String) -> Unit,
    onStop: (String) -> Unit,
    onReconnect: (String) -> Unit,
    onCheckHealth: (String) -> Unit,
    onOpenRunner: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRefresh: () -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingDelete by remember { mutableStateOf<ModelPreset?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ForgeCanvas,
        topBar = {
            IdeTopBar(
                title = "Models",
                subtitle = "Model Manager",
                onBack = onBack,
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Reload models")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!credentialsPersistent) {
                IdeCard {
                    IdeSectionLabel("Credentials")
                    IdeSpacer(6)
                    Text(
                        text = "Encrypted storage is unavailable on this device, so an API key is kept " +
                            "only until the app is closed.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ForgeAmber,
                    )
                }
            }

            when {
                state.loading -> Box(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = ForgeMint)
                }

                state.isEmpty -> IdeEmptyState(
                    icon = Icons.Filled.Memory,
                    title = "No models yet",
                    message = "Save a model to remember how it starts and how to reach it. " +
                        "Nothing is assumed about which model you use.",
                    actionLabel = "Add Model",
                    onAction = onAddModel,
                )

                else -> {
                    state.presets.forEach { preset ->
                        ModelCard(
                            preset = preset,
                            status = state.status(preset.id),
                            active = state.activePresetId == preset.id,
                            busy = busyPresetId == preset.id,
                            onUse = { onUse(preset.id) },
                            onStart = { onStart(preset.id) },
                            onStop = { onStop(preset.id) },
                            onReconnect = { onReconnect(preset.id) },
                            onCheckHealth = { onCheckHealth(preset.id) },
                            onOpenRunner = { onOpenRunner(preset.id) },
                            onEdit = { onEdit(preset.id) },
                            onDelete = { pendingDelete = preset },
                        )
                    }
                    IdeSpacer(4)
                    Button(onClick = onAddModel, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Add Model")
                    }
                    IdeSpacer(8)
                }
            }
        }
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = onDismissMessage,
            title = { Text("Model operation failed") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = onDismissMessage) { Text("OK") } },
        )
    }

    pendingDelete?.let { preset ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete model?") },
            text = { Text("\"${preset.displayName}\" and its saved credential will be removed.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        onDelete(preset.id)
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ModelCard(
    preset: ModelPreset,
    status: ModelRuntimeStatus,
    active: Boolean,
    busy: Boolean,
    onUse: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onReconnect: () -> Unit,
    onCheckHealth: () -> Unit,
    onOpenRunner: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    IdeCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IdeDot(statusColor(status.state))
            Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                Text(
                    text = preset.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = ForgeInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = preset.providerType.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = ForgeMuted,
                )
            }
            IdeStatusPill(status.state.displayName, statusColor(status.state))
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit model", tint = ForgeMuted)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete model", tint = ForgeMuted)
            }
        }

        IdeSpacer(8)
        IdeLabelValue("Model", preset.modelIdentifier)
        IdeLabelValue("API", preset.apiProtocol.displayName)
        preset.serverPort?.let { IdeLabelValue("Server port", it.toString()) }
        IdeLabelValue(
            "Endpoint",
            status.endpoint?.url ?: preset.endpoint.explicitUrl ?: preset.endpoint.mode.displayName,
        )
        IdeLabelValue("Selected", if (active) "Yes — the agent uses this model" else "No")

        IdeSpacer(6)
        Text(
            text = status.message,
            style = MaterialTheme.typography.bodyMedium,
            color = if (status.state == ModelLifecycleState.FAILED) ForgeDanger else ForgeMuted,
        )

        if (status.awaitingRuntime) {
            IdeSpacer(4)
            Text(
                text = runtimeHint(status.failure),
                style = MaterialTheme.typography.bodyMedium,
                color = ForgeAmber,
            )
        }

        if (busy) {
            IdeSpacer(10)
            CircularProgressIndicator(color = ForgeMint, modifier = Modifier.size(18.dp))
        }

        IdeSpacer(10)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (status.isUsable) {
                if (!active) {
                    Button(onClick = onUse) { Text("Use") }
                } else {
                    OutlinedButton(onClick = onStop) { Text("Stop") }
                }
            } else {
                Button(onClick = onStart, enabled = !busy) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Start")
                }
            }
            OutlinedButton(onClick = onReconnect, enabled = !busy) { Text("Reconnect") }
        }
        IdeSpacer(8)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCheckHealth, enabled = !busy) { Text("Check") }
            OutlinedButton(onClick = onOpenRunner) {
                Icon(Icons.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Runner")
            }
            if (status.isUsable && !active) {
                OutlinedButton(onClick = onStop) {
                    Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/** Honest copy for the states Android cannot fix by itself. */
private fun runtimeHint(failure: ModelRuntimeFailure): String = when (failure) {
    ModelRuntimeFailure.RUNTIME_NOT_DETECTED ->
        "The model runtime is not running. Android cannot start or keep a Colab runtime " +
            "alive — open the Model Runner, start your notebook there, then reconnect."

    ModelRuntimeFailure.ENDPOINT_INVALID ->
        "The configured endpoint is not usable. Fix it in the model settings and try again."

    ModelRuntimeFailure.MODEL_API_UNREACHABLE ->
        "The endpoint answered but the model API did not. The runtime may still be loading."

    ModelRuntimeFailure.INVALID_PRESET ->
        "This model is missing required settings. Open it and complete the form."

    ModelRuntimeFailure.TIMEOUT ->
        "The attempt timed out. The runtime may still be starting — reconnect when it is up."

    else -> "The user has to act before this model can come online."
}

private fun statusColor(state: ModelLifecycleState): Color = when (state) {
    ModelLifecycleState.ONLINE -> ForgeMint
    ModelLifecycleState.DEGRADED -> ForgeAmber
    ModelLifecycleState.STARTING,
    ModelLifecycleState.CONNECTING,
    ModelLifecycleState.CHECKING,
    ModelLifecycleState.STOPPING,
    -> ForgePeriwinkle

    ModelLifecycleState.FAILED -> ForgeDanger
    else -> ForgeMuted
}
