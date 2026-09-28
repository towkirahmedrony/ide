package com.agentx.app.ui.ide.screens

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
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Refresh
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
import com.agentx.app.integrations.connection.Connection
import com.agentx.app.integrations.connection.ConnectionManagerState
import com.agentx.app.integrations.connection.ConnectionStatus
import com.agentx.app.ui.ide.components.IdeCard
import com.agentx.app.ui.ide.components.IdeDot
import com.agentx.app.ui.ide.components.IdeEmptyState
import com.agentx.app.ui.ide.components.IdeLabelValue
import com.agentx.app.ui.ide.components.IdeSectionLabel
import com.agentx.app.ui.ide.components.IdeSpacer
import com.agentx.app.ui.ide.components.IdeStatusPill
import com.agentx.app.ui.ide.components.IdeTopBar
import com.agentx.app.ui.theme.ForgeAmber
import com.agentx.app.ui.theme.ForgeCanvas
import com.agentx.app.ui.theme.ForgeDanger
import com.agentx.app.ui.theme.ForgeInk
import com.agentx.app.ui.theme.ForgeMint
import com.agentx.app.ui.theme.ForgeMuted
import com.agentx.app.ui.theme.ForgePeriwinkle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Connections: saved external services and what can be done with each one.
 *
 * Credentials are never shown. Testing an unsupported type reports that the
 * test is not implemented — it never claims to be connected.
 */
@Composable
fun ConnectionsScreen(
    state: ConnectionManagerState,
    busyConnectionId: String?,
    message: String?,
    credentialsPersistent: Boolean,
    onBack: () -> Unit,
    onAddConnection: () -> Unit,
    onEdit: (String) -> Unit,
    onTest: (String) -> Unit,
    onSetEnabled: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onRefresh: () -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingDelete by remember { mutableStateOf<Connection?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ForgeCanvas,
        topBar = {
            IdeTopBar(
                title = "Connections",
                subtitle = "External services",
                onBack = onBack,
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Reload connections")
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
                        text = "Encrypted storage is unavailable on this device, so a token or API key " +
                            "is kept only until the app is closed.",
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
                    icon = Icons.Filled.Hub,
                    title = "No connections yet",
                    message = "Add a GitHub, Supabase, MCP or custom API connection. " +
                        "The agent cannot use a service until a tool requests it.",
                    actionLabel = "Add Connection",
                    onAction = onAddConnection,
                )

                else -> {
                    state.connections.forEach { connection ->
                        ConnectionCard(
                            connection = connection,
                            busy = busyConnectionId == connection.id.value,
                            onEdit = { onEdit(connection.id.value) },
                            onTest = { onTest(connection.id.value) },
                            onSetEnabled = { enabled -> onSetEnabled(connection.id.value, enabled) },
                            onDelete = { pendingDelete = connection },
                        )
                    }
                    IdeSpacer(4)
                    Button(onClick = onAddConnection, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Add Connection")
                    }
                    IdeSpacer(8)
                }
            }
        }
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = onDismissMessage,
            title = { Text("Connection") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = onDismissMessage) { Text("OK") } },
        )
    }

    pendingDelete?.let { connection ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Remove connection?") },
            text = { Text("\"${connection.displayName}\" and its saved credential will be removed.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        onDelete(connection.id.value)
                    },
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ConnectionCard(
    connection: Connection,
    busy: Boolean,
    onEdit: () -> Unit,
    onTest: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    IdeCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IdeDot(statusColor(connection.status))
            Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                Text(
                    text = connection.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = ForgeInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = connection.type.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = ForgeMuted,
                )
            }
            IdeStatusPill(connection.status.displayName, statusColor(connection.status))
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit connection", tint = ForgeMuted)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Remove connection", tint = ForgeMuted)
            }
        }

        IdeSpacer(8)
        IdeLabelValue("Service", connection.type.displayName)
        IdeLabelValue("Enabled", if (connection.enabled) "Yes" else "No")
        connection.config.endpoint?.let { IdeLabelValue("Endpoint", it) }
        IdeLabelValue("Authentication", connection.config.authMethod.displayName)
        IdeLabelValue(
            "Capabilities",
            connection.capabilities.joinToString(", ") { it.id }.ifBlank { "None" },
        )
        IdeLabelValue("Last test", formatTested(connection.lastTestedAtMillis))

        connection.statusMessage?.let { message ->
            IdeSpacer(6)
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = if (connection.status == ConnectionStatus.ERROR) ForgeDanger else ForgeMuted,
            )
        }

        if (busy) {
            IdeSpacer(10)
            CircularProgressIndicator(color = ForgeMint, modifier = Modifier.size(18.dp))
        }

        IdeSpacer(10)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onTest, enabled = !busy && connection.enabled) { Text("Test") }
            OutlinedButton(onClick = { onSetEnabled(!connection.enabled) }, enabled = !busy) {
                Text(if (connection.enabled) "Disable" else "Enable")
            }
        }
    }
}

private fun formatTested(millis: Long?): String {
    if (millis == null || millis <= 0L) return "Never"
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(millis))
}

private fun statusColor(status: ConnectionStatus): Color = when (status) {
    ConnectionStatus.CONNECTED -> ForgeMint
    // In-flight states: waiting on the provider's authorization page or on a probe.
    ConnectionStatus.AUTHORIZING -> ForgePeriwinkle
    ConnectionStatus.CONNECTING -> ForgePeriwinkle
    ConnectionStatus.ERROR -> ForgeDanger
    ConnectionStatus.EXPIRED -> ForgeAmber
    ConnectionStatus.DISCONNECTED -> ForgeAmber
    ConnectionStatus.NOT_CONNECTED -> ForgeMuted
}
