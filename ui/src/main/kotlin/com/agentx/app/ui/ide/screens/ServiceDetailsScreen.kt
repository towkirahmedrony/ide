package com.agentx.app.ui.ide.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentx.app.integrations.connection.Connection
import com.agentx.app.integrations.connection.ConnectionCapability
import com.agentx.app.integrations.connection.ConnectionStatus
import com.agentx.app.integrations.connection.ConnectionType
import com.agentx.app.integrations.connection.DeviceAuthorization
import com.agentx.app.integrations.connection.InstalledTool
import com.agentx.app.integrations.connection.ProviderAvailability
import com.agentx.app.integrations.connection.ProviderCapabilityInfo
import com.agentx.app.integrations.connection.ProviderDescriptor
import com.agentx.app.integrations.oauth.DeviceFlowState
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
import com.agentx.app.ui.theme.ForgePeriwinkle

/**
 * One service, in full: how to connect it, what the agent can do with it, and —
 * once connected — who it is connected as and which tools are available.
 *
 * Layout is ordered by what the user needs next: the connect action sits right under
 * the header, the device code stays pinned above the scrolling content while an
 * authorization is running, and the one-time setup folds away once a Client ID is saved.
 * Nothing here renders a token.
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
    deviceState: DeviceFlowState = DeviceFlowState.DISCONNECTED,
    deviceAuthorization: DeviceAuthorization? = null,
    onBack: () -> Unit,
    onConnect: () -> Unit,
    onReconnect: () -> Unit,
    onCancelAuthorization: () -> Unit,
    onOpenDeviceVerification: () -> Unit = {},
    onCancelDeviceFlow: () -> Unit = {},
    onDisconnect: () -> Unit,
    onVerify: () -> Unit,
    onSaveSetup: (clientId: String, brokerUrl: String?) -> Unit = { _, _ -> },
    onClearSetup: () -> Unit = {},
    onManage: () -> Unit,
    onBrowseRepositories: (() -> Unit)? = null,
    message: String? = null,
    onDismissMessage: () -> Unit = {},
) {
    var confirmDisconnect by remember { mutableStateOf(false) }
    // The setup guide is only useful before the first connection.
    var showGuide by remember(setup?.clientIdConfigured) { mutableStateOf(setup?.clientIdConfigured != true) }
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

        // Pinned outside the scroll area: the code must be visible the moment the
        // user returns from the browser, wherever the page was scrolled to.
        // Only the user code and the verification URI cross into the UI.
        deviceAuthorization?.let { authorization ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 12.dp),
            ) {
                DeviceAuthorizationCard(
                    authorization = authorization,
                    state = deviceState,
                    busy = busy,
                    onOpen = onOpenDeviceVerification,
                    onCancel = onCancelDeviceFlow,
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
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

            // A settled device attempt that did not connect says why, so the user can
            // tell "authorize again" from "try again later".
            if (deviceAuthorization == null && message == null &&
                deviceState.isTerminal &&
                deviceState != DeviceFlowState.CONNECTED &&
                deviceState != DeviceFlowState.DISCONNECTED
            ) {
                IdeSpacer(16)
                IdeCard {
                    Text(
                        text = deviceErrorText(deviceState, null),
                        style = MaterialTheme.typography.bodyMedium,
                        color = ForgeDanger,
                    )
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

            IdeSpacer(16)
            ActionRow(
                availability = availability,
                status = status,
                connected = connected,
                busy = busy,
                authorizing = authorizing,
                deviceActive = deviceAuthorization != null && deviceState.isInProgress,
                canConnect = setup?.canConnect != false &&
                    lifecycle != IntegrationLifecycle.NOT_CONFIGURED,
                onConnect = onConnect,
                onReconnect = onReconnect,
                onCancelAuthorization = onCancelAuthorization,
                onDisconnect = { confirmDisconnect = true },
                onVerify = onVerify,
                onManage = onManage,
                onBrowseRepositories = onBrowseRepositories,
            )

            if (setup != null && type != ConnectionType.MCP_SERVER && type != ConnectionType.CUSTOM_API) {
                IdeSpacer(20)
                SetupCard(
                    setup = setup,
                    busy = setupBusy,
                    onSave = onSaveSetup,
                    onClear = onClearSetup,
                )
            }

            guide?.let { instructions ->
                IdeSpacer(12)
                if (showGuide) {
                    HowToCard(guide = instructions)
                    TextButton(onClick = { showGuide = false }) { Text("Hide setup guide") }
                } else {
                    TextButton(onClick = { showGuide = true }) { Text("Show setup guide") }
                }
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
    deviceActive: Boolean,
    canConnect: Boolean,
    onConnect: () -> Unit,
    onReconnect: () -> Unit,
    onCancelAuthorization: () -> Unit,
    onDisconnect: () -> Unit,
    onVerify: () -> Unit,
    onManage: () -> Unit,
    onBrowseRepositories: (() -> Unit)?,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        when {
            // A device authorization is running: the code card above has everything
            // the user needs, so there is nothing more to press here.
            deviceActive -> {
                Text(
                    text = "Enter the code shown above on GitHub, then this screen connects " +
                        "automatically. You can leave it while the browser is open.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ForgeInk,
                )
            }

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
                if (availability.type == ConnectionType.GITHUB && onBrowseRepositories != null) {
                    Button(
                        onClick = onBrowseRepositories,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Browse repositories") }
                    IdeSpacer(8)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onManage, enabled = !busy) { Text("Manage Access") }
                        OutlinedButton(onClick = onVerify, enabled = !busy) { Text("Verify") }
                        OutlinedButton(onClick = onDisconnect, enabled = !busy) { Text("Disconnect") }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onManage, enabled = !busy) { Text("Manage Access") }
                        OutlinedButton(onClick = onVerify, enabled = !busy) { Text("Verify") }
                        OutlinedButton(onClick = onDisconnect, enabled = !busy) { Text("Disconnect") }
                    }
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
                        ?: "Save a Client ID below, then connect ${availability.displayName}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ForgeAmber,
                )
            }

            else -> {
                Button(
                    onClick = onConnect,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
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

/**
 * The device code, front and centre. It is copied to the clipboard the moment it
 * exists, so the user can open GitHub and paste it without coming back to read it.
 */
@Composable
private fun DeviceAuthorizationCard(
    authorization: DeviceAuthorization,
    state: DeviceFlowState,
    busy: Boolean,
    onOpen: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    var copied by remember(authorization.userCode) { mutableStateOf(false) }
    LaunchedEffect(authorization.userCode) {
        copied = copyToClipboard(context, authorization.userCode)
    }

    IdeCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Authorize on GitHub",
                style = MaterialTheme.typography.titleSmall,
                color = ForgeInk,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IdeStatusPill(
                text = state.displayName,
                color = if (state == DeviceFlowState.POLLING) ForgePeriwinkle else ForgeMint,
            )
        }
        IdeSpacer(6)
        Text(
            text = if (copied) {
                "Code copied. Tap Open GitHub, paste it, and approve access."
            } else {
                "Enter this code on GitHub to authorize the app."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (copied) ForgeMint else ForgeMuted,
        )
        IdeSpacer(10)
        SelectionContainer {
            Text(
                text = authorization.userCode,
                style = MaterialTheme.typography.headlineMedium,
                color = ForgeInk,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 4.sp,
            )
        }
        IdeSpacer(4)
        SelectionContainer {
            Text(
                text = authorization.verificationUri,
                style = MaterialTheme.typography.bodySmall,
                color = ForgeMuted,
            )
        }
        IdeSpacer(12)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onOpen,
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) { Text("Open GitHub") }
            OutlinedButton(
                onClick = { copied = copyToClipboard(context, authorization.userCode) },
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                IdeSpacerW(6)
                Text("Copy code")
            }
        }
        IdeSpacer(4)
        TextButton(onClick = onCancel, enabled = !busy) { Text("Cancel") }
    }
}

/** Puts [text] on the clipboard. Returns false when the platform refuses. */
private fun copyToClipboard(context: Context, text: String): Boolean = runCatching {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    manager.setPrimaryClip(ClipData.newPlainText("GitHub device code", text))
    true
}.getOrDefault(false)

@Composable
private fun SetupCard(
    setup: ProviderSetupSnapshot,
    busy: Boolean,
    onSave: (clientId: String, brokerUrl: String?) -> Unit,
    onClear: () -> Unit,
) {
    var clientId by remember(setup.clientId) { mutableStateOf(setup.clientId) }
    var broker by remember(setup.exchangeBrokerUrl) { mutableStateOf(setup.exchangeBrokerUrl.orEmpty()) }
    // Folded once a Client ID is saved and the setup is complete: after that the
    // user only needs the fields again to change them.
    var expanded by remember(setup.clientIdConfigured, setup.validation.complete) {
        mutableStateOf(!(setup.clientIdConfigured && setup.validation.complete))
    }

    IdeSectionLabel("Integration setup")
    IdeSpacer(8)
    IdeCard {
        if (!expanded) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = ForgeMint,
                    modifier = Modifier.size(18.dp),
                )
                IdeSpacerW(8)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Client ID saved",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ForgeInk,
                    )
                    Text(
                        text = "Tap Edit to change it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ForgeMuted,
                    )
                }
                TextButton(onClick = { expanded = true }) { Text("Edit") }
            }
        } else {
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

/** Human-readable text for a settled device-flow failure. Never a credential. */
private fun deviceErrorText(state: DeviceFlowState, statusMessage: String?): String = when (state) {
    DeviceFlowState.EXPIRED -> statusMessage ?: "The authorization code expired. Start the connection again."
    DeviceFlowState.RATE_LIMITED -> statusMessage ?: "The provider is rate limiting this app. Try again later."
    DeviceFlowState.NETWORK_ERROR -> statusMessage
        ?: "The provider could not be reached. Check your connection and try again."
    DeviceFlowState.AUTH_ERROR -> statusMessage ?: "Authorization failed. Try again."
    else -> statusMessage ?: state.displayName
}

private fun declaredCapabilities(type: ConnectionType): List<ProviderCapabilityInfo> =
    type.defaultCapabilities.map { capability: ConnectionCapability ->
        ProviderCapabilityInfo(capability = capability, label = capability.id.replace('_', ' '))
    }
