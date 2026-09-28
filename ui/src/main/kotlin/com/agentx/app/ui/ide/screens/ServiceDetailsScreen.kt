package com.agentx.app.ui.ide.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.agentx.app.integrations.connection.Connection
import com.agentx.app.integrations.connection.ConnectionCapability
import com.agentx.app.integrations.connection.ConnectionStatus
import com.agentx.app.integrations.connection.ConnectionType
import com.agentx.app.integrations.connection.InstalledTool
import com.agentx.app.integrations.connection.ProviderAvailability
import com.agentx.app.integrations.connection.ProviderCapabilityInfo
import com.agentx.app.integrations.connection.ProviderDescriptor
import com.agentx.app.integrations.setup.IntegrationLifecycle
import com.agentx.app.integrations.setup.ProviderSetupGuide
import com.agentx.app.integrations.setup.ProviderSetupSnapshot
import com.agentx.app.integrations.setup.usesPersonalOAuthSetup
import com.agentx.app.ui.ide.components.IdeCard
import com.agentx.app.ui.ide.components.IdeDivider
import com.agentx.app.ui.ide.components.IdeSectionLabel
import com.agentx.app.ui.ide.components.IdeSpacer
import com.agentx.app.ui.ide.components.IdeSpacerW
import com.agentx.app.ui.ide.components.IdeStatusPill
import com.agentx.app.ui.ide.components.IdeTopBar
import com.agentx.app.ui.theme.ForgeAmber
import com.agentx.app.ui.theme.ForgeCanvas
import com.agentx.app.ui.theme.ForgeDanger
import com.agentx.app.ui.theme.ForgeInk
import com.agentx.app.ui.theme.ForgeMint
import com.agentx.app.ui.theme.ForgeMuted

/**
 * One service, in full: what the agent can do with it, how to connect it, and —
 * once connected — who it is connected as, which capabilities the provider really
 * granted and which tools are now available to the agent.
 *
 * Capabilities are always derived from the connection's granted scopes, so the
 * list can only ever show access the user approved. Nothing here renders a token.
 */
@Composable
fun ServiceDetailsScreen(
    type: ConnectionType,
    availability: ProviderAvailability,
    descriptor: ProviderDescriptor?,
    connection: Connection?,
    tools: List<InstalledTool>,
    setup: ProviderSetupSnapshot? = null,
    guide: ProviderSetupGuide? = null,
    busy: Boolean,
    authorizing: Boolean,
    setupBusy: Boolean = false,
    onBack: () -> Unit,
    onConnect: () -> Unit,
    onReconnect: () -> Unit,
    onCancelAuthorization: () -> Unit,
    onDisconnect: () -> Unit,
    onVerify: () -> Unit,
    onSaveSetup: (clientId: String, brokerUrl: String?) -> Unit = { _, _ -> },
    onClearSetup: () -> Unit = {},
    onManage: () -> Unit,
) {
    var confirmDisconnect by remember { mutableStateOf(false) }
    val status = connection?.status ?: ConnectionStatus.NOT_CONNECTED
    val connected = status == ConnectionStatus.CONNECTED
    val granted = connection?.capabilities.orEmpty()
    val lifecycle = setup?.lifecycle
    val pillText = lifecycle?.displayName ?: statusLabel(status, connected)
    val pillColor = lifecycle?.let { lifecycleColor(it) } ?: statusColor(status)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ForgeCanvas),
    ) {
        IdeTopBar(title = availability.displayName, onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = availability.displayName,
                    style = MaterialTheme.typography.headlineSmall,
                    color = ForgeInk,
                    fontWeight = FontWeight.SemiBold,
                )
                IdeSpacerW(10)
                IdeStatusPill(text = pillText, color = pillColor)
            }

            IdeSpacer(8)
            Text(
                text = descriptor?.details ?: availability.description,
                style = MaterialTheme.typography.bodyMedium,
                color = ForgeMuted,
            )

            if (connected) {
                IdeSpacer(12)
                val account = connection?.accountLabel ?: "Connected"
                Text(
                    text = "Connected as $account",
                    style = MaterialTheme.typography.titleSmall,
                    color = ForgeMint,
                )
                connection?.credentialsExpireAtMillis?.let { expires ->
                    IdeSpacer(4)
                    Text(
                        text = "Access expires ${formatTimestamp(expires)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = ForgeAmber,
                    )
                }
            }

            if (setup != null && type != ConnectionType.MCP_SERVER && type != ConnectionType.CUSTOM_API) {
                IdeSpacer(16)
                SetupCard(
                    setup = setup,
                    busy = setupBusy,
                    onSave = onSaveSetup,
                    onClear = onClearSetup,
                )
            }

            guide?.let { instructions ->
                IdeSpacer(16)
                HowToCard(guide = instructions)
            }

            IdeSpacer(16)
            IdeSectionLabel("Capabilities")
            IdeSpacer(8)
            IdeCard {
                (descriptor?.capabilities ?: declaredCapabilities(type)).forEach { info ->
                    CapabilityRow(
                        info = info,
                        granted = granted.isEmpty() || info.capability in granted,
                        showGrantState = connected,
                    )
                }
                if (descriptor == null) {
                    IdeSpacer(4)
                    Text(
                        text = "This build cannot describe the capabilities of ${availability.displayName}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ForgeMuted,
                    )
                }
            }

            if (connected && granted.isNotEmpty()) {
                IdeSpacer(12)
                Text(
                    text = "Granted scopes: ${connection?.grantedScopes?.sorted()?.joinToString(", ").orEmpty()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = ForgeMuted,
                )
            }

            IdeSpacer(16)
            IdeSectionLabel("Tools for the agent")
            IdeSpacer(8)
            IdeCard {
                if (tools.isEmpty()) {
                    Text(
                        text = when {
                            type == ConnectionType.MCP_SERVER ->
                                "Tools appear once a server advertises them. MCP tool discovery is not " +
                                    "implemented in this build."

                            !connected -> "Connect ${availability.displayName} to make its tools available."
                            else -> "No tools are available for this connection yet."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = ForgeMuted,
                    )
                } else {
                    tools.forEachIndexed { index, tool ->
                        if (index > 0) {
                            IdeSpacer(10)
                            IdeDivider()
                            IdeSpacer(10)
                        }
                        ToolRow(tool)
                    }
                }
            }

            // Recovery states: say what happened and offer the way out of it.
            connection?.statusMessage?.let { detail ->
                if (!connected && status != ConnectionStatus.NOT_CONNECTED) {
                    IdeSpacer(16)
                    IdeCard {
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (status == ConnectionStatus.ERROR) ForgeDanger else ForgeAmber,
                        )
                    }
                }
            }

            IdeSpacer(20)
            ActionRow(
                availability = availability,
                status = status,
                connected = connected,
                busy = busy,
                authorizing = authorizing,
                canConnect = setup?.canConnect != false &&
                    lifecycle != IntegrationLifecycle.NOT_CONFIGURED,
                onConnect = onConnect,
                onReconnect = onReconnect,
                onCancelAuthorization = onCancelAuthorization,
                onDisconnect = { confirmDisconnect = true },
                onVerify = onVerify,
                onManage = onManage,
            )
            IdeSpacer(24)
        }
    }

    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Disconnect ${availability.displayName}?") },
            text = {
                Text(
                    "The stored credential is deleted, the connection returns to Not connected and the " +
                        "agent loses the tools that needed it. Revoking access at the provider is only " +
                        "possible where the provider supports it.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDisconnect = false
                        onDisconnect()
                    },
                ) { Text("Disconnect") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDisconnect = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ActionRow(
    availability: ProviderAvailability,
    status: ConnectionStatus,
    connected: Boolean,
    busy: Boolean,
    authorizing: Boolean,
    canConnect: Boolean,
    onConnect: () -> Unit,
    onReconnect: () -> Unit,
    onCancelAuthorization: () -> Unit,
    onDisconnect: () -> Unit,
    onVerify: () -> Unit,
    onManage: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        when {
            authorizing || status.isAuthorizing -> {
                Text(
                    text = "Waiting for authorization — finish approving access in your browser, then " +
                        "return here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ForgeInk,
                )
                IdeSpacer(8)
                OutlinedButton(onClick = onCancelAuthorization, enabled = !busy) { Text("Cancel") }
            }

            connected -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onManage, enabled = !busy) { Text("Manage Access") }
                    OutlinedButton(onClick = onVerify, enabled = !busy) { Text("Verify") }
                    OutlinedButton(onClick = onDisconnect, enabled = !busy) { Text("Disconnect") }
                }
            }

            status == ConnectionStatus.EXPIRED || status == ConnectionStatus.ERROR -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onReconnect, enabled = !busy) { Text("Reconnect") }
                    OutlinedButton(onClick = onDisconnect, enabled = !busy) { Text("Disconnect") }
                }
            }

            !availability.registered -> {
                Text(
                    text = availability.unavailableReason
                        ?: "No provider is registered for ${availability.displayName} in this build.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ForgeAmber,
                )
            }

            // A service the user describes in the app (MCP, custom API) has no
            // provider page to authorize on, so the action adds the service instead
            // of opening a browser authorization that cannot exist.
            !usesPersonalOAuthSetup(availability.type) -> {
                Button(onClick = onManage, enabled = !busy) {
                    Text(if (availability.type == ConnectionType.MCP_SERVER) "Add Server" else "Add Connection")
                }
            }

            !canConnect -> {
                Text(
                    text = availability.unavailableReason
                        ?: "Save a Client ID above, then connect ${availability.displayName}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ForgeAmber,
                )
            }

            else -> {
                Button(onClick = onConnect, enabled = !busy) {
                    Text("Connect ${availability.displayName}")
                }
            }
        }
    }
}

@Composable
private fun CapabilityRow(
    info: ProviderCapabilityInfo,
    granted: Boolean,
    showGrantState: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (showGrantState && granted) "✓" else "•",
            style = MaterialTheme.typography.bodyMedium,
            color = if (showGrantState) (if (granted) ForgeMint else ForgeMuted) else ForgeMuted,
        )
        IdeSpacerW(8)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = info.label,
                style = MaterialTheme.typography.bodyMedium,
                color = ForgeInk,
            )
            if (info.description.isNotBlank()) {
                Text(
                    text = info.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = ForgeMuted,
                )
            }
        }
        if (info.mutating) {
            Text(text = "write", style = MaterialTheme.typography.labelSmall, color = ForgeAmber)
        }
    }
}

@Composable
private fun ToolRow(tool: InstalledTool) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = tool.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (tool.enabled) ForgeInk else ForgeMuted,
                modifier = Modifier.weight(1f),
            )
            IdeStatusPill(
                text = if (tool.enabled) "Enabled" else "Unavailable",
                color = if (tool.enabled) ForgeMint else ForgeMuted,
            )
        }
        Text(
            text = tool.reason ?: tool.description,
            style = MaterialTheme.typography.bodySmall,
            color = ForgeMuted,
        )
    }
}

private fun statusLabel(status: ConnectionStatus, connected: Boolean): String = when {
    connected -> "Connected"
    else -> status.displayName
}

@Composable
private fun SetupCard(
    setup: ProviderSetupSnapshot,
    busy: Boolean,
    onSave: (clientId: String, brokerUrl: String?) -> Unit,
    onClear: () -> Unit,
) {
    var clientId by remember(setup.clientId) { mutableStateOf(setup.clientId) }
    var broker by remember(setup.exchangeBrokerUrl) { mutableStateOf(setup.exchangeBrokerUrl.orEmpty()) }
    IdeSectionLabel("Integration setup")
    IdeSpacer(8)
    IdeCard {
        Text(
            text = "Client ID is a public value. A client secret is never stored here.",
            style = MaterialTheme.typography.bodySmall,
            color = ForgeMuted,
        )
        IdeSpacer(10)
        Text(
            text = "Callback URL",
            style = MaterialTheme.typography.labelSmall,
            color = ForgeMuted,
        )
        IdeSpacer(4)
        SelectionContainer {
            Text(
                text = setup.callbackUri,
                style = MaterialTheme.typography.bodyMedium,
                color = ForgeInk,
            )
        }
        IdeSpacer(10)
        OutlinedTextField(
            value = clientId,
            onValueChange = { clientId = it },
            label = { Text("Client ID") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        IdeSpacer(8)
        OutlinedTextField(
            value = broker,
            onValueChange = { broker = it },
            label = { Text("Exchange broker URL (optional)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        IdeSpacer(10)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onSave(clientId, broker.takeIf { it.isNotBlank() }) },
                enabled = !busy && clientId.isNotBlank(),
            ) { Text("Save Client ID") }
            if (setup.clientIdConfigured) {
                OutlinedButton(onClick = onClear, enabled = !busy) { Text("Clear") }
            }
        }
        if (!setup.validation.complete) {
            IdeSpacer(8)
            Text(
                text = setup.validation.summary,
                style = MaterialTheme.typography.bodySmall,
                color = ForgeAmber,
            )
        }
    }
}

@Composable
private fun HowToCard(guide: ProviderSetupGuide) {
    val uriHandler = LocalUriHandler.current
    IdeSectionLabel("How to set up")
    IdeSpacer(8)
    IdeCard {
        Text(
            text = guide.title,
            style = MaterialTheme.typography.titleSmall,
            color = ForgeInk,
            fontWeight = FontWeight.SemiBold,
        )
        IdeSpacer(4)
        Text(
            text = guide.summary,
            style = MaterialTheme.typography.bodyMedium,
            color = ForgeMuted,
        )
        IdeSpacer(10)
        guide.steps.forEach { step ->
            Text(
                text = "${step.number}. ${step.text}",
                style = MaterialTheme.typography.bodyMedium,
                color = ForgeInk,
            )
            IdeSpacer(4)
        }
        // Read once into locals: the guide lives in another module, so its
        // properties cannot be smart-cast.
        val developerUrl = guide.developerSettingsUrl
        val developerLabel = guide.developerSettingsLabel
        if (developerUrl != null && developerLabel != null) {
            IdeSpacer(6)
            TextButton(onClick = { uriHandler.openUri(developerUrl) }) {
                Text(developerLabel)
            }
        }
        guide.notes.forEach { note ->
            IdeSpacer(4)
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = ForgeMuted,
            )
        }
    }
}

private fun declaredCapabilities(type: ConnectionType): List<ProviderCapabilityInfo> =
    type.defaultCapabilities.map { capability: ConnectionCapability ->
        ProviderCapabilityInfo(capability = capability, label = capability.id.replace('_', ' '))
    }
