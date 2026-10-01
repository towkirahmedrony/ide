package com.agentx.app.ui.ide.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agentx.app.model.manager.ModelManagerState
import com.agentx.app.model.preset.ModelPreset
import com.agentx.app.model.runtime.ModelLifecycleState
import com.agentx.app.model.runtime.ModelRuntimeStatus
import com.agentx.app.ui.ide.components.IdeDot
import com.agentx.app.ui.ide.components.IdeEmptyState
import com.agentx.app.ui.ide.components.IdeSpacer
import com.agentx.app.ui.ide.components.IdeStatusPill
import com.agentx.app.ui.ide.components.IdeTopBar
import com.agentx.app.ui.theme.ForgeAmber
import com.agentx.app.ui.theme.ForgeCanvas
import com.agentx.app.ui.theme.ForgeInk
import com.agentx.app.ui.theme.ForgeMint
import com.agentx.app.ui.theme.ForgeMuted

/**
 * Saved models as a scannable list.
 *
 * One compact row per model — status, name, provider and model id — and a tap
 * that opens the model's own screen. Lifecycle actions live there, so this page
 * never grows a wall of buttons.
 */
@Composable
fun ModelsScreen(
    state: ModelManagerState,
    busyPresetId: String?,
    message: String?,
    credentialsPersistent: Boolean,
    onBack: () -> Unit,
    onAddModel: () -> Unit,
    onOpen: (String) -> Unit,
    onRefresh: () -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ForgeCanvas,
        topBar = {
            IdeTopBar(
                title = "Models",
                onBack = onBack,
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Reload models")
                    }
                },
            )
        },
        floatingActionButton = {
            if (!state.loading && !state.isEmpty) {
                ExtendedFloatingActionButton(
                    onClick = onAddModel,
                    containerColor = ForgeMint,
                    contentColor = ForgeCanvas,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    text = { Text("Add model") },
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            message?.let { text ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = ForgeAmber,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismissMessage) { Text("Dismiss") }
                }
            }

            if (!credentialsPersistent) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Encrypted storage is unavailable: an API key is kept only until the app closes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ForgeAmber,
                    )
                }
            }

            when {
                state.loading -> Box(
                    modifier = Modifier.fillMaxWidth().padding(40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = ForgeMint, modifier = Modifier.size(20.dp))
                }

                state.isEmpty -> Column(modifier = Modifier.padding(16.dp)) {
                    IdeEmptyState(
                        icon = Icons.Filled.Memory,
                        title = "No models yet",
                        message = "Add a local or API model to get started.",
                        actionLabel = "Add model",
                        onAction = onAddModel,
                    )
                }

                else -> {
                    state.presets.forEachIndexed { index, preset ->
                        ModelListRow(
                            preset = preset,
                            status = state.status(preset.id),
                            active = state.activePresetId == preset.id,
                            busy = busyPresetId == preset.id,
                            onClick = { onOpen(preset.id) },
                        )
                        if (index != state.presets.lastIndex) {
                            HorizontalDivider(
                                color = ForgeMuted.copy(alpha = 0.15f),
                                modifier = Modifier.padding(start = 16.dp),
                            )
                        }
                    }
                    IdeSpacer(72)
                }
            }
        }
    }
}

@Composable
private fun ModelListRow(
    preset: ModelPreset,
    status: ModelRuntimeStatus,
    active: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(color = ForgeMint, modifier = Modifier.size(12.dp))
        } else {
            IdeDot(modelLifecycleColor(status.state))
        }
        Column(
            modifier = Modifier.weight(1f).padding(start = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = preset.displayName,
                style = MaterialTheme.typography.titleSmall,
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
        val notable = status.state == ModelLifecycleState.ONLINE ||
            status.state == ModelLifecycleState.DEGRADED ||
            status.state == ModelLifecycleState.FAILED
        if (active) {
            IdeStatusPill("Active", ForgeMint)
        } else if (notable) {
            IdeStatusPill(status.state.displayName, modelLifecycleColor(status.state))
        }
    }
}
