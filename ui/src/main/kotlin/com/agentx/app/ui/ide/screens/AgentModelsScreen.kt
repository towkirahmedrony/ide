package com.agentx.app.ui.ide.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.unit.dp
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.model.ProviderModelOption
import com.agentx.app.agent.model.RoleModelState
import com.agentx.app.ui.ide.components.IdeCard
import com.agentx.app.ui.ide.components.IdeDivider
import com.agentx.app.ui.ide.components.IdeSectionLabel
import com.agentx.app.ui.ide.components.IdeSpacer
import com.agentx.app.ui.ide.components.IdeStatusPill
import com.agentx.app.ui.ide.components.IdeTopBar
import com.agentx.app.ui.ide.state.AgentModelRow
import com.agentx.app.ui.theme.ForgeAmber
import com.agentx.app.ui.theme.ForgeCanvas
import com.agentx.app.ui.theme.ForgeDanger
import com.agentx.app.ui.theme.ForgeInk
import com.agentx.app.ui.theme.ForgeMint
import com.agentx.app.ui.theme.ForgeMuted

/**
 * Settings → Agent Models.
 *
 * Lists the Main agent and every sub-agent with the provider and model each one
 * runs on, and lets the user reassign them independently. Availability is judged
 * from the Model Manager's live state; a role whose provider is not connected is
 * shown as not configured rather than pretending to be ready.
 */
@Composable
fun AgentModelsScreen(
    rows: List<AgentModelRow>,
    options: List<ProviderModelOption>,
    loading: Boolean,
    message: String?,
    onBack: () -> Unit,
    onSave: (AgentRole, String, String?, String?) -> Unit,
    onReset: (AgentRole) -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf<AgentRole?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ForgeCanvas,
        topBar = { IdeTopBar(title = "Agent Models", subtitle = "Settings", onBack = onBack) },
    ) { padding ->
        if (loading && rows.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = ForgeMint)
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IdeCard {
                Text(
                    text = "Each agent runs on its own model. Assign a provider and model per agent; " +
                        "they resolve independently at run time. Nothing here stores an API key — a " +
                        "provider's credential stays with its connection in Models.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ForgeMuted,
                )
            }
            rows.forEach { row ->
                AgentModelCard(row = row, onClick = { editing = row.role })
            }
            IdeSpacer(8)
        }
    }

    val role = editing
    if (role != null) {
        val row = rows.firstOrNull { it.role == role }
        if (row != null) {
            AgentModelEditorDialog(
                row = row,
                options = options,
                onSave = { providerId, model, connectionId ->
                    editing = null
                    onSave(role, providerId, model, connectionId)
                },
                onReset = {
                    editing = null
                    onReset(role)
                },
                onDismiss = { editing = null },
            )
        }
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = onDismissMessage,
            title = { Text("Agent Models") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = onDismissMessage) { Text("OK") } },
        )
    }
}

@Composable
private fun AgentModelCard(row: AgentModelRow, onClick: () -> Unit) {
    val color = statusColor(row.state)
    IdeCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = ForgeInk,
                )
                Text(
                    text = "Role · ${row.role.name}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ForgeMuted,
                )
            }
            Spacer(Modifier.width(8.dp))
            IdeStatusPill(text = statusLabel(row.state), color = color)
        }
        IdeSpacer(8)
        Text(
            text = providerModelLine(row),
            style = MaterialTheme.typography.bodyMedium,
            color = ForgeInk,
        )
        IdeSpacer(4)
        Text(
            text = row.description,
            style = MaterialTheme.typography.bodyMedium,
            color = ForgeMuted,
        )
        if (row.state != RoleModelState.CONNECTED) {
            IdeSpacer(6)
            Text(
                text = row.message,
                style = MaterialTheme.typography.bodyMedium,
                color = color,
            )
        }
        IdeSpacer(4)
        Text(
            text = if (row.explicit) "Custom assignment" else "Built-in default",
            style = MaterialTheme.typography.labelSmall,
            color = ForgeMuted,
        )
    }
}

@Composable
private fun AgentModelEditorDialog(
    row: AgentModelRow,
    options: List<ProviderModelOption>,
    onSave: (providerId: String, model: String?, connectionId: String?) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val initialProvider = row.providerId?.takeIf { id -> options.any { it.providerId == id } }
        ?: options.firstOrNull()?.providerId
    var selectedProvider by remember(row.role) { mutableStateOf(initialProvider) }
    var selectedModel by remember(row.role) { mutableStateOf(row.model) }

    val currentProvider = options.firstOrNull { it.providerId == selectedProvider }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Model for ${row.name}") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = row.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ForgeMuted,
                )
                IdeSpacer(12)
                if (options.isEmpty()) {
                    Text(
                        text = "No providers are configured yet. Add a model in Settings → Models, " +
                            "then come back to assign it to an agent.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ForgeAmber,
                    )
                    return@Column
                }
                IdeSectionLabel("Provider")
                IdeSpacer(4)
                options.forEach { option ->
                    ChoiceRow(
                        selected = option.providerId == selectedProvider,
                        title = option.providerLabel,
                        subtitle = if (option.connected) {
                            option.connectionLabel ?: "Connected"
                        } else {
                            "Not connected"
                        },
                        onClick = {
                            selectedProvider = option.providerId
                            selectedModel = option.models.firstOrNull()
                        },
                    )
                }
                currentProvider?.models?.takeIf { it.isNotEmpty() }?.let { models ->
                    IdeSpacer(6)
                    IdeDivider()
                    IdeSpacer(10)
                    IdeSectionLabel("Model")
                    IdeSpacer(4)
                    models.forEach { model ->
                        ChoiceRow(
                            selected = model == selectedModel,
                            title = model,
                            subtitle = null,
                            onClick = { selectedModel = model },
                        )
                    }
                }
                IdeSpacer(10)
                IdeDivider()
                IdeSpacer(4)
                TextButton(onClick = onReset) { Text("Use built-in default") }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selectedProvider != null,
                onClick = {
                    val provider = selectedProvider ?: return@TextButton
                    onSave(provider, selectedModel, currentProvider?.connectionId)
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ChoiceRow(
    selected: Boolean,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, color = ForgeInk)
            if (subtitle != null) {
                Text(text = subtitle, style = MaterialTheme.typography.labelSmall, color = ForgeMuted)
            }
        }
    }
}

private fun providerModelLine(row: AgentModelRow): String {
    val model = row.model?.takeIf { it.isNotBlank() }
    return if (model != null) "${row.providerLabel} · $model" else row.providerLabel
}

private fun statusLabel(state: RoleModelState): String = when (state) {
    RoleModelState.CONNECTED -> "Connected"
    RoleModelState.NOT_CONFIGURED -> "Not configured"
    RoleModelState.MODEL_UNAVAILABLE -> "Model unavailable"
}

private fun statusColor(state: RoleModelState): Color = when (state) {
    RoleModelState.CONNECTED -> ForgeMint
    RoleModelState.NOT_CONFIGURED -> ForgeAmber
    RoleModelState.MODEL_UNAVAILABLE -> ForgeDanger
}
