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
import androidx.compose.material3.Switch
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
import com.agentx.app.agent.model.RoleModelEvaluation
import com.agentx.app.agent.model.RoleModelState
import com.agentx.app.model.manager.ModelConnectionKind
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
 * runs on, and lets the user reassign them independently. Roles are grouped by
 * execution domain — Local AI for the models that run on the user's own endpoint,
 * API AI for the hosted provider connections — so which domain an agent runs in is
 * visible without reading the provider name. Availability is judged from the Model
 * Manager's live state; a role whose provider is not connected is shown as not
 * configured rather than pretending to be ready.
 */
@Composable
fun AgentModelsScreen(
    rows: List<AgentModelRow>,
    options: List<ProviderModelOption>,
    loading: Boolean,
    message: String?,
    onBack: () -> Unit,
    /**
     * `role`, provider, model, the connection it was chosen from, and the user's statement
     * that this model calls tools. The statement travels with the assignment because this
     * is where the model is chosen: a role may be pointed at any model a gateway serves,
     * and the model the gateway routes to decides whether tools work.
     */
    onSave: (AgentRole, String, String?, String?, Boolean) -> Unit,
    onReset: (AgentRole) -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
    catalogBusy: Boolean = false,
    catalogMessage: String? = null,
    onRefreshCatalog: () -> Unit = {},
    onDismissCatalogMessage: () -> Unit = {},
    providerSummaries: Map<String, String> = emptyMap(),
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
                        "provider's credential stays with its connection in Models. Model lists come " +
                        "from the provider's own catalog; a model it no longer lists is shown as " +
                        "unavailable rather than being replaced silently.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ForgeMuted,
                )
                IdeSpacer(8)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (catalogBusy) "Refreshing models…" else "Refresh model catalogs",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ForgeMint,
                    )
                    Spacer(Modifier.width(12.dp))
                    TextButton(onClick = onRefreshCatalog, enabled = !catalogBusy) {
                        Text("Refresh")
                    }
                }
            }
            // Grouped by execution domain: a local role and an API role are different
            // kinds of assignment, and the grouping is what makes that visible even
            // when both providers happen to speak the same OpenAI-compatible protocol.
            listOf(
                ModelConnectionKind.LOCAL_CUSTOM to "Local AI",
                ModelConnectionKind.API to "API AI",
            ).forEach { (domain, label) ->
                val group = rows.filter { it.domain == domain }
                if (group.isEmpty()) return@forEach
                IdeSectionLabel(label)
                group.forEach { row ->
                    AgentModelCard(row = row, onClick = { editing = row.role })
                }
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
                providerSummaries = providerSummaries,
                onSave = { providerId, model, connectionId, declaresToolCalling ->
                    editing = null
                    onSave(role, providerId, model, connectionId, declaresToolCalling)
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

    catalogMessage?.let { text ->
        AlertDialog(
            onDismissRequest = onDismissCatalogMessage,
            title = { Text("Model catalog") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = onDismissCatalogMessage) { Text("OK") } },
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
                // One explanation, not two. The capability note, when there is one, is
                // the same fact stated in terms of the fix the user needs; showing both
                // would report a single problem twice with two different colours.
                text = row.capabilityNote ?: row.message,
                style = MaterialTheme.typography.bodyMedium,
                color = color,
            )
        }
        IdeSpacer(4)
        Text(
            // The statement is part of what was assigned, so it is shown with it: a role
            // whose model the user stated tool calling for is not the same assignment as
            // one whose model's support is still unknown.
            text = buildString {
                append(if (row.explicit) "Custom assignment" else "Built-in default")
                if (row.declaresToolCalling) append(" · tool calling stated")
            },
            style = MaterialTheme.typography.labelSmall,
            color = ForgeMuted,
        )
    }
}

@Composable
private fun AgentModelEditorDialog(
    row: AgentModelRow,
    options: List<ProviderModelOption>,
    providerSummaries: Map<String, String>,
    onSave: (providerId: String, model: String?, connectionId: String?, declaresToolCalling: Boolean) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val initialProvider = row.providerId?.takeIf { id -> options.any { it.providerId == id } }
        ?: options.firstOrNull()?.providerId
    var selectedProvider by remember(row.role) { mutableStateOf(initialProvider) }
    var selectedModel by remember(row.role) { mutableStateOf(row.model) }

    // The statement belongs to one model, so the switch follows the selection: opening the
    // saved model shows what was stated for it, and picking another model shows nothing
    // rather than carrying the previous model's answer over — which would state a
    // capability for a model the user never said it about.
    var declaresToolCalling by remember(row.role, selectedModel) {
        mutableStateOf(row.declaresToolCalling && selectedModel == row.model)
    }

    val currentProvider = options.firstOrNull { it.providerId == selectedProvider }
    val selectedModelLabel = selectedModel?.takeIf { it.isNotBlank() } ?: "this model"

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
                // The user's statement for this assignment's model. A gateway routes a
                // model to whatever upstream serves it, so nothing authoritative describes
                // whether it calls tools, and a role that needs tools cannot run on
                // "unknown". Only shown for the providers whose models the user states
                // themselves — a catalogue provider states its own capabilities and a
                // switch must not override them.
                if (RoleModelEvaluation.declarableByUser(selectedProvider)) {
                    IdeSpacer(10)
                    IdeDivider()
                    IdeSpacer(10)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Supports tool calling",
                                style = MaterialTheme.typography.bodyMedium,
                                color = ForgeInk,
                            )
                            Text(
                                text = if (declaresToolCalling) {
                                    "Stated for $selectedModelLabel only: this agent will send tools to it"
                                } else {
                                    "Leave off unless this model really calls tools — it stays unknown " +
                                        "until stated, and this agent cannot run on it"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = ForgeMuted,
                            )
                        }
                        Switch(
                            checked = declaresToolCalling,
                            onCheckedChange = { enabled -> declaresToolCalling = enabled },
                        )
                    }
                }
                currentProvider?.discoveryNote?.takeIf { it.isNotBlank() }?.let { note ->
                    IdeSpacer(10)
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodyMedium,
                        color = ForgeAmber,
                    )
                }
                currentProvider?.unavailableModels?.takeIf { it.isNotEmpty() }?.let { models ->
                    IdeSpacer(10)
                    IdeSectionLabel("Unavailable")
                    IdeSpacer(4)
                    models.forEach { model ->
                        Text(
                            text = "$model — no longer listed by the provider",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ForgeDanger,
                        )
                    }
                }
                selectedProvider?.let { providerId ->
                    providerSummaries[providerId]?.takeIf { it.isNotBlank() }?.let { summary ->
                        IdeSpacer(10)
                        IdeDivider()
                        IdeSpacer(10)
                        IdeSectionLabel("Limits & usage")
                        IdeSpacer(4)
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = ForgeMuted,
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
                    // Only a provider the user states for carries the switch: for a
                    // catalogue provider the statement stays false, never a claim that
                    // overrides what the provider says about its own models.
                    val declares = declaresToolCalling && RoleModelEvaluation.declarableByUser(provider)
                    onSave(provider, selectedModel, currentProvider?.connectionId, declares)
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
    RoleModelState.CONNECTION_MISSING -> "Connection missing"
    RoleModelState.CAPABILITY_UNSUPPORTED -> "Unsupported by this model"
    RoleModelState.CAPABILITY_UNKNOWN -> "Capability unconfirmed"
    RoleModelState.DISABLED -> "Disabled"
}

@Composable
private fun statusColor(state: RoleModelState): Color = when (state) {
    RoleModelState.CONNECTED -> ForgeMint
    RoleModelState.NOT_CONFIGURED -> ForgeAmber
    RoleModelState.MODEL_UNAVAILABLE -> ForgeDanger
    // The saved connection is gone: the assignment is stale rather than absent, and it
    // is repaired by re-picking the model, not by adding a provider.
    RoleModelState.CONNECTION_MISSING -> ForgeDanger
    RoleModelState.CAPABILITY_UNSUPPORTED -> ForgeDanger
    RoleModelState.DISABLED -> ForgeDanger
    // Amber, not red: an unconfirmed capability is an unresolved verdict, not a
    // rejection, and a model nobody has described yet must not look broken.
    RoleModelState.CAPABILITY_UNKNOWN -> ForgeAmber
}
