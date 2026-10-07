package com.agentx.app.ui.ide.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agentx.app.tools.ToolCategory
import com.agentx.app.ui.ide.components.IdeCard
import com.agentx.app.ui.ide.components.IdeEmptyState
import com.agentx.app.ui.ide.components.IdeLabelValue
import com.agentx.app.ui.ide.components.IdeSectionLabel
import com.agentx.app.ui.ide.components.IdeSpacer
import com.agentx.app.ui.ide.components.IdeStatusPill
import com.agentx.app.ui.ide.components.IdeTopBar
import com.agentx.app.ui.ide.state.ToolEntry
import com.agentx.app.ui.ide.state.ToolFilter
import com.agentx.app.ui.ide.state.ToolStatus
import com.agentx.app.ui.ide.state.ToolsViewModel
import com.agentx.app.ui.theme.ForgeAmber
import com.agentx.app.ui.theme.ForgeCanvas
import com.agentx.app.ui.theme.ForgeInk
import com.agentx.app.ui.theme.ForgeMint
import com.agentx.app.ui.theme.ForgeMuted
import com.agentx.app.ui.theme.ForgePeriwinkle
import com.agentx.app.ui.theme.ForgeSurfaceVariant

/**
 * Settings → Tools: the control surface for the Tool System.
 *
 * It lists the tools the registry actually holds — available and declared-but-
 * unavailable alike — grouped by category, and lets the user turn an available
 * tool off. A switch here writes the same enablement the Tool Router and the
 * agent tool bridge read, so it changes what the agent may run; an unavailable
 * tool has no switch because it has no backend to enable.
 */
@Composable
fun ToolsScreen(
    viewModel: ToolsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var details by remember { mutableStateOf<ToolEntry?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ForgeCanvas,
        topBar = {
            IdeTopBar(
                title = "Tools",
                subtitle = "Tools available to your agents",
                onBack = onBack,
            )
        },
    ) { padding ->
        if (!viewModel.available) {
            IdeEmptyState(
                icon = Icons.Filled.Handyman,
                title = "Tool system unavailable",
                message = "The tool system is not wired in this build, so there are no tools to manage.",
                modifier = Modifier.padding(padding).padding(top = 24.dp),
            )
            return@Scaffold
        }
        if (viewModel.loading && viewModel.tools.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
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
            viewModel.message?.let { text ->
                IdeCard {
                    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = ForgeInk)
                }
            }

            ToolsSummary(viewModel)

            OutlinedTextField(
                value = viewModel.query,
                onValueChange = viewModel::updateQuery,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Search tools") },
            )

            FilterRow(
                filters = ToolFilter.entries.toList(),
                selected = viewModel.statusFilter,
                labelOf = { it.label },
                onSelect = { viewModel.updateStatusFilter(it) },
            )
            if (viewModel.categories.size > 1) {
                FilterRow(
                    filters = listOf<ToolCategory?>(null) + viewModel.categories,
                    selected = viewModel.categoryFilter,
                    labelOf = { it?.let { category -> viewModel.tools.first { entry -> entry.category == category }.categoryLabel } ?: "All categories" },
                    onSelect = { viewModel.updateCategoryFilter(it) },
                )
            }

            val grouped = viewModel.grouped
            if (grouped.isEmpty()) {
                IdeCard {
                    Text(
                        text = "No tools match this filter.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ForgeMuted,
                    )
                }
            }
            grouped.forEach { (_, entries) ->
                CategorySection(
                    title = entries.first().categoryLabel,
                    entries = entries,
                    onToggle = viewModel::setEnabled,
                    onOpen = { details = it },
                )
            }
            IdeSpacer(8)
        }
    }

    details?.let { entry ->
        ToolDetailDialog(
            entry = entry,
            onToggle = { enabled -> viewModel.setEnabled(entry.id, enabled) },
            onDismiss = { details = null },
        )
    }
}

@Composable
private fun ToolsSummary(viewModel: ToolsViewModel) {
    IdeCard {
        IdeSectionLabel("Tool access")
        IdeSpacer(6)
        Text(
            text = "${viewModel.enabledCount} of ${viewModel.tools.size} tools enabled",
            style = MaterialTheme.typography.titleMedium,
            color = ForgeInk,
        )
        IdeSpacer(4)
        Text(
            text = "A tool that is turned off is never offered to an agent and never run. " +
                "Unavailable tools have no backend in this build and cannot be enabled.",
            style = MaterialTheme.typography.bodyMedium,
            color = ForgeMuted,
        )
        IdeSpacer(4)
        Text(
            text = "Approval and permission rules are enforced by the tool policy and " +
                "cannot be loosened here.",
            style = MaterialTheme.typography.labelSmall,
            color = ForgeMuted,
        )
        IdeSpacer(10)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IdeStatusPill(text = "${viewModel.enabledCount} enabled", color = ForgeMint)
            if (viewModel.disabledCount > 0) {
                IdeStatusPill(text = "${viewModel.disabledCount} disabled", color = ForgeMuted)
            }
            if (viewModel.unavailableCount > 0) {
                IdeStatusPill(text = "${viewModel.unavailableCount} unavailable", color = ForgeAmber)
            }
        }
        if (viewModel.disabledCount > 0) {
            IdeSpacer(4)
            TextButton(onClick = viewModel::reset) { Text("Enable all tools", color = ForgeMint) }
        }
    }
}

@Composable
private fun <T> FilterRow(
    filters: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        filters.forEach { value ->
            SelectableChip(
                text = labelOf(value),
                selected = value == selected,
                onClick = { onSelect(value) },
            )
        }
    }
}

@Composable
private fun SelectableChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) ForgeMint.copy(alpha = 0.16f) else ForgeSurfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) ForgeMint else ForgeMuted,
        )
    }
}

@Composable
private fun CategorySection(
    title: String,
    entries: List<ToolEntry>,
    onToggle: (String, Boolean) -> Unit,
    onOpen: (ToolEntry) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        IdeSectionLabel(title)
        entries.forEach { entry ->
            ToolRow(entry = entry, onToggle = onToggle, onOpen = onOpen)
        }
    }
}

@Composable
private fun ToolRow(
    entry: ToolEntry,
    onToggle: (String, Boolean) -> Unit,
    onOpen: (ToolEntry) -> Unit,
) {
    IdeCard(modifier = Modifier.clickable { onOpen(entry) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (entry.available) ForgeInk else ForgeMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!entry.available) {
                        Spacer(Modifier.width(8.dp))
                        IdeStatusPill(text = "Unavailable", color = ForgeAmber)
                    }
                }
                IdeSpacer(4)
                Text(
                    text = entry.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ForgeMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                IdeSpacer(6)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IdeStatusPill(text = entry.permissionLabel, color = permissionColor(entry))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${entry.categoryLabel} · ${entry.accessLabel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = ForgeMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            if (entry.editable) {
                Switch(checked = entry.enabled, onCheckedChange = { onToggle(entry.id, it) })
            }
        }
    }
}

@Composable
private fun ToolDetailDialog(
    entry: ToolEntry,
    onToggle: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(text = entry.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = entry.id,
                    style = MaterialTheme.typography.labelSmall,
                    color = ForgeMuted,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(text = entry.description, style = MaterialTheme.typography.bodyMedium)
                IdeSpacer(8)
                IdeLabelValue("Category", entry.categoryLabel)
                IdeLabelValue("Status", entry.status.label)
                IdeLabelValue("Availability", if (entry.available) "Available" else "Not available")
                IdeLabelValue("Permission", entry.permissionLabel)
                IdeLabelValue("Access", entry.accessLabel)
                entry.connectionLabel?.let { IdeLabelValue("Connection", it) }
                entry.unavailableReason?.let { reason ->
                    IdeSpacer(4)
                    Text(text = reason, style = MaterialTheme.typography.bodyMedium, color = ForgeAmber)
                }
                IdeSpacer(4)
                IdeSectionLabel("Capabilities")
                Text(
                    text = entry.capabilities.joinToString(", ").ifBlank { "None declared" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = ForgeInk,
                )
                IdeSpacer(6)
                IdeSectionLabel("Agents that use it")
                Text(
                    text = entry.roles.joinToString(", ").ifBlank { "No built-in agent — contributed by a connection" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = ForgeInk,
                )
                if (entry.editable) {
                    IdeSpacer(10)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            IdeSectionLabel("Enabled")
                            Text(
                                text = if (entry.enabled) {
                                    "This tool may be offered to agents."
                                } else {
                                    "This tool is never offered or run."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = ForgeMuted,
                            )
                        }
                        Switch(checked = entry.enabled, onCheckedChange = onToggle)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun permissionColor(entry: ToolEntry): androidx.compose.ui.graphics.Color = when {
    !entry.available -> ForgeMuted
    entry.status == ToolStatus.DISABLED -> ForgeMuted
    entry.permissionLabel == "Asks for approval" -> ForgePeriwinkle
    else -> ForgeMint
}
