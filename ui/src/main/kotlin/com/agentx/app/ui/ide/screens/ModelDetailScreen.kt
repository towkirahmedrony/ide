package com.agentx.app.ui.ide.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
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
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.model.connect.ModelSetupKind
import com.agentx.app.model.preset.ModelPreset
import com.agentx.app.model.runtime.ModelLifecycleState
import com.agentx.app.model.runtime.ModelRuntimeFailure
import com.agentx.app.model.runtime.ModelRuntimeStatus
import com.agentx.app.ui.ide.components.IdeDot
import com.agentx.app.ui.ide.components.IdeSectionLabel
import com.agentx.app.ui.ide.components.IdeSettingRow
import com.agentx.app.ui.ide.components.IdeSpacer
import com.agentx.app.ui.ide.components.IdeStatusPill
import com.agentx.app.ui.ide.components.IdeTopBar
import com.agentx.app.ui.ide.state.ModelUsageSummary
import com.agentx.app.ui.theme.ForgeCanvas
import com.agentx.app.ui.theme.ForgeDanger
import com.agentx.app.ui.theme.ForgeInk
import com.agentx.app.ui.theme.ForgeMint
import com.agentx.app.ui.theme.ForgeMuted
import com.agentx.app.ui.theme.ForgePeriwinkle
import com.agentx.app.ui.theme.ForgeAmber

/**
 * One saved model: what it is, what it serves, what it has used, and what can be
 * done with it.
 *
 * Deliberately a settings-style list rather than a card: the header carries
 * identity and status, and every action is one compact row.
 */
@Composable
fun ModelDetailScreen(
    preset: ModelPreset?,
    status: ModelRuntimeStatus,
    active: Boolean,
    busy: Boolean,
    assignedRoles: List<AgentRole>,
    usage: ModelUsageSummary,
    onBack: () -> Unit,
    onUse: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onReconnect: () -> Unit,
    onCheckHealth: () -> Unit,
    onOpenRunner: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ForgeCanvas,
        topBar = {
            IdeTopBar(
                title = preset?.displayName ?: "Model",
                onBack = onBack,
                actions = {
                    if (preset != null) {
                        IconButton(onClick = onEdit) {
                            Icon(Icons.Filled.Edit, contentDescription = "Edit model", tint = ForgeMuted)
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (preset == null) {
            Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                Text(
                    text = "This model no longer exists. It may have been deleted.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ForgeMuted,
                )
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
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
                        text = "${providerLabel(preset)} · ${preset.modelIdentifier}",
                        style = MaterialTheme.typography.labelSmall,
                        color = ForgeMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IdeStatusPill(status.state.displayName, statusColor(status.state))
            }
            IdeSpacer(6)
            Text(
                text = status.message,
                style = MaterialTheme.typography.bodySmall,
                color = if (status.state == ModelLifecycleState.FAILED) ForgeDanger else ForgeMuted,
            )
            if (status.awaitingRuntime) {
                IdeSpacer(4)
                Text(
                    text = runtimeHint(status.failure),
                    style = MaterialTheme.typography.bodySmall,
                    color = ForgeAmber,
                )
            }
            if (busy) {
                IdeSpacer(8)
                CircularProgressIndicator(color = ForgeMint, modifier = Modifier.size(16.dp))
            }

            IdeSpacer(12)
            HorizontalDivider(color = ForgeMuted.copy(alpha = 0.2f))
            IdeSpacer(10)

            IdeSectionLabel("Connection")
            IdeLabelRow("Type", if (active) "Active — the agent uses this model" else "Saved")
            IdeLabelRow("Provider", providerLabel(preset))
            IdeLabelRow("Model id", preset.modelIdentifier)
            IdeLabelRow(
                "Endpoint",
                status.endpoint?.url ?: preset.endpoint.explicitUrl ?: preset.endpoint.mode.displayName,
            )
            IdeLabelRow("API", preset.apiProtocol.displayName)

            IdeSpacer(12)
            HorizontalDivider(color = ForgeMuted.copy(alpha = 0.2f))
            IdeSpacer(10)

            IdeSectionLabel("Agent roles")
            if (assignedRoles.isEmpty()) {
                Text(
                    text = "Not assigned to any agent role.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ForgeMuted,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            } else {
                assignedRoles.forEach { role ->
                    IdeSettingRow(label = role.displayNameOrName())
                }
            }

            IdeSpacer(12)
            HorizontalDivider(color = ForgeMuted.copy(alpha = 0.2f))
            IdeSpacer(10)

            IdeSectionLabel("Usage")
            Text(
                text = usage.line() ?: "No usage recorded yet.",
                style = MaterialTheme.typography.bodySmall,
                color = ForgeMuted,
                modifier = Modifier.padding(vertical = 6.dp),
            )

            IdeSpacer(12)
            HorizontalDivider(color = ForgeMuted.copy(alpha = 0.2f))
            IdeSpacer(10)

            IdeSectionLabel("Actions")
            if (status.isUsable) {
                if (active) {
                    IdeSettingRow(
                        label = "Disconnect",
                        value = "Stops this connection",
                        enabled = !busy,
                        onClick = onStop,
                    )
                } else {
                    IdeSettingRow(
                        label = "Use this model",
                        value = "The agent will use it",
                        enabled = !busy,
                        onClick = onUse,
                    )
                    IdeSettingRow(label = "Disconnect", enabled = !busy, onClick = onStop)
                }
            } else {
                IdeSettingRow(label = "Start", enabled = !busy, onClick = onStart)
            }
            IdeSettingRow(label = "Test connection", enabled = !busy, onClick = onCheckHealth)
            IdeSettingRow(label = "Reconnect", enabled = !busy, onClick = onReconnect)
            IdeSettingRow(label = "Model runner", enabled = !busy, onClick = onOpenRunner)
            IdeSettingRow(label = "Edit model", enabled = !busy, onClick = onEdit)
            IdeSettingRow(label = "Delete model", enabled = !busy, danger = true, onClick = { confirmDelete = true })
            IdeSpacer(16)
        }
    }

    if (confirmDelete && preset != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete model?") },
            text = { Text("\"${preset.displayName}\" and its saved credential will be removed.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun IdeLabelRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = ForgeMuted,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = ForgeInk,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 12.dp),
        )
    }
}

/** The provider label the model screens share. */
internal fun providerLabel(preset: ModelPreset): String {
    val kind = ModelSetupKind.fromId(preset.setupKind)
    return if (kind == ModelSetupKind.CUSTOM) preset.providerType.displayName else kind.displayName
}

/** A role's display name, falling back to its identifier. */
private fun AgentRole.displayNameOrName(): String = name.lowercase().replaceFirstChar { it.uppercase() }

/** Honest copy for the states Android cannot fix by itself. */
internal fun runtimeHint(failure: ModelRuntimeFailure): String = when (failure) {
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

internal fun statusColor(state: ModelLifecycleState): Color = when (state) {
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
