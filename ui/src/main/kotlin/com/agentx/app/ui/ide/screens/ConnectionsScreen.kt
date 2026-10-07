package com.agentx.app.ui.ide.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agentx.app.integrations.connection.Connection
import com.agentx.app.integrations.connection.ConnectionManagerState
import com.agentx.app.integrations.connection.ConnectionStatus
import com.agentx.app.integrations.connection.ConnectionType
import com.agentx.app.integrations.connection.InstalledTool
import com.agentx.app.integrations.connection.ProviderAvailability
import com.agentx.app.integrations.connection.ProviderDescriptor
import com.agentx.app.integrations.setup.IntegrationLifecycle
import com.agentx.app.integrations.setup.ProviderSetupSnapshot
import com.agentx.app.integrations.setup.usesPersonalOAuthSetup
import com.agentx.app.ui.ide.components.IdeCard
import com.agentx.app.ui.ide.components.IdeSectionLabel
import com.agentx.app.ui.ide.components.IdeSpacer
import com.agentx.app.ui.ide.components.IdeSpacerW
import com.agentx.app.ui.ide.components.IdeStatusPill
import com.agentx.app.ui.ide.components.IdeTopBar
import com.agentx.app.ui.theme.ForgeAmber
import com.agentx.app.ui.theme.ForgeBorder
import com.agentx.app.ui.theme.ForgeCanvas
import com.agentx.app.ui.theme.ForgeDanger
import com.agentx.app.ui.theme.ForgeInk
import com.agentx.app.ui.theme.ForgeMint
import com.agentx.app.ui.theme.ForgeMuted
import com.agentx.app.ui.theme.ForgePeriwinkle
import com.agentx.app.ui.theme.ForgeSurfaceVariant

/**
 * The Connections page: the central place where a service is connected and the
 * agent gains its tools.
 *
 * Each card is compact: icon, name and status on top, a one-line capability
 * summary, who it is connected as, and the single action that makes sense
 * (Set Up, Connect, Manage, Reconnect or Add Server). Credentials are never
 * rendered.
 */
@Composable
fun ConnectionsScreen(
    state: ConnectionManagerState,
    providers: List<ProviderAvailability>,
    descriptors: List<ProviderDescriptor>,
    tools: List<InstalledTool>,
    busyKey: String?,
    message: String?,
    credentialsPersistent: Boolean,
    setupOf: (ConnectionType, Connection?) -> ProviderSetupSnapshot? = { _, _ -> null },
    onBack: () -> Unit,
    onOpenService: (ConnectionType) -> Unit,
    onConnect: (ConnectionType) -> Unit,
    onReconnect: (String) -> Unit,
    onCancelAuthorization: (String) -> Unit,
    onDisconnect: (String) -> Unit,
    onDismissMessage: () -> Unit,
    onRefresh: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ForgeCanvas),
    ) {
        IdeTopBar(
            title = "Connections",
            subtitle = "Services your AI agent can use",
            onBack = onBack,
            actions = {
                IconButton(onClick = onRefresh) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Refresh connections",
                        tint = ForgeMuted,
                    )
                }
            },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            if (!credentialsPersistent) {
                IdeCard {
                    Text(
                        text = "Secure storage is unavailable on this device, so credentials cannot be " +
                            "stored securely. Connections will not survive leaving the app.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ForgeAmber,
                    )
                }
                IdeSpacer(12)
            }

            message?.let { text ->
                IdeCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = ForgeInk,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onDismissMessage) { Text("Dismiss") }
                    }
                }
                IdeSpacer(12)
            }

            IdeSectionLabel("Services")
            IdeSpacer(8)

            orderProviders(providers).forEach { availability ->
                val descriptor = descriptors.firstOrNull { it.type == availability.type }
                val connection = state.ofType(availability.type).firstOrNull { it.enabled }
                    ?: state.ofType(availability.type).firstOrNull()
                ServiceCard(
                    availability = availability,
                    descriptor = descriptor,
                    connection = connection,
                    setup = setupOf(availability.type, connection),
                    enabledTools = tools.count { it.provider == availability.type && it.enabled },
                    // busyKey and the connection id are both null before anything is
                    // connected; comparing them directly made every unconnected card
                    // look busy and disabled its button.
                    busy = busyKey != null &&
                        (busyKey == availability.type.name || busyKey == connection?.id?.value),
                    onOpen = { onOpenService(availability.type) },
                    onPrimaryAction = {
                        val snapshot = setupOf(availability.type, connection)
                        when {
                            snapshot?.lifecycle == IntegrationLifecycle.NOT_CONFIGURED ->
                                onOpenService(availability.type)
                            // A service with no provider-console OAuth app is added on
                            // its own page: starting a browser authorization for it
                            // would have no page to open.
                            !usesPersonalOAuthSetup(availability.type) ->
                                onOpenService(availability.type)
                            connection == null -> onConnect(availability.type)
                            connection.status == ConnectionStatus.CONNECTED -> onOpenService(availability.type)
                            connection.status.isAuthorizing -> onCancelAuthorization(connection.id.value)
                            else -> onReconnect(connection.id.value)
                        }
                    },
                    onDisconnect = connection
                        ?.takeIf { it.status == ConnectionStatus.CONNECTED || it.hasCredential }
                        ?.let { { onDisconnect(it.id.value) } },
                )
                IdeSpacer(12)
            }

            IdeSpacer(4)
            Text(
                text = "The agent only receives the access you approve on the provider's own page. " +
                    "Tokens stay in the platform's secure storage and are never shown here.",
                style = MaterialTheme.typography.bodySmall,
                color = ForgeMuted,
            )
            IdeSpacer(24)
        }
    }
}

@Composable
private fun ServiceCard(
    availability: ProviderAvailability,
    descriptor: ProviderDescriptor?,
    connection: Connection?,
    setup: ProviderSetupSnapshot?,
    enabledTools: Int,
    busy: Boolean,
    onOpen: () -> Unit,
    onPrimaryAction: () -> Unit,
    onDisconnect: (() -> Unit)?,
) {
    val status = connection?.status ?: ConnectionStatus.NOT_CONNECTED
    val connected = status == ConnectionStatus.CONNECTED
    val lifecycle = setup?.lifecycle
    val pillText = lifecycle?.displayName ?: status.displayName
    val pillColor = lifecycle?.let { lifecycleColor(it) } ?: statusColor(status)
    val labels = descriptor?.capabilities.orEmpty().map { it.label }

    IdeCard(modifier = Modifier.clickable(onClick = onOpen)) {
        Row(verticalAlignment = Alignment.Top) {
            ServiceIcon(availability.type)
            IdeSpacerW(12)
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = availability.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = ForgeInk,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IdeSpacerW(8)
                    IdeStatusPill(text = pillText, color = pillColor)
                }
                IdeSpacer(2)
                Text(
                    text = descriptor?.description ?: availability.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = ForgeMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (labels.isNotEmpty()) {
            IdeSpacer(10)
            Text(
                text = capabilitySummary(labels),
                style = MaterialTheme.typography.bodySmall,
                color = ForgeInk.copy(alpha = 0.8f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        accountLine(status, connection, enabledTools, setup)?.let { detail ->
            IdeSpacer(8)
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (status) {
                    ConnectionStatus.CONNECTED -> {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = ForgeMint,
                            modifier = Modifier.size(14.dp),
                        )
                        IdeSpacerW(6)
                    }

                    ConnectionStatus.ERROR, ConnectionStatus.EXPIRED -> {
                        Icon(
                            imageVector = Icons.Filled.ErrorOutline,
                            contentDescription = null,
                            tint = ForgeDanger,
                            modifier = Modifier.size(14.dp),
                        )
                        IdeSpacerW(6)
                    }

                    else -> Unit
                }
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (connected) ForgeMint else ForgeMuted,
                )
            }
        }

        IdeSpacer(12)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onPrimaryAction,
                enabled = !busy && availability.registered,
                modifier = Modifier.weight(1f),
            ) {
                Text(primaryLabel(availability, status, connected, setup))
            }
            onDisconnect?.let { disconnect ->
                OutlinedButton(onClick = disconnect, enabled = !busy) { Text("Disconnect") }
            }
        }

        if (availability.registered && availability.supportsHostedAuthorization && !availability.configured) {
            availability.unavailableReason?.let { reason ->
                IdeSpacer(8)
                Text(text = reason, style = MaterialTheme.typography.bodySmall, color = ForgeAmber)
            }
        }
        if (!availability.registered) {
            availability.unavailableReason?.let { reason ->
                IdeSpacer(8)
                Text(text = reason, style = MaterialTheme.typography.bodySmall, color = ForgeAmber)
            }
        }
    }
}

@Composable
private fun ServiceIcon(type: ConnectionType) {
    val icon: ImageVector = when (type) {
        ConnectionType.GITHUB -> Icons.Filled.Code
        ConnectionType.SUPABASE -> Icons.Filled.Storage
        ConnectionType.MCP_SERVER -> Icons.Filled.Extension
        ConnectionType.CUSTOM_API -> Icons.Filled.Cloud
    }
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(ForgeSurfaceVariant, RoundedCornerShape(10.dp))
            .border(1.dp, ForgeBorder, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = ForgePeriwinkle)
    }
}

/** One line of what the service gives the agent, e.g. "Read repositories · Pull requests · +2". */
private fun capabilitySummary(labels: List<String>): String {
    val shown = labels.take(MAX_CARD_CAPABILITIES).joinToString(" · ")
    val more = labels.size - MAX_CARD_CAPABILITIES
    return if (more > 0) "$shown · +$more" else shown
}

/** Services in the order the product presents them. */
internal fun orderProviders(providers: List<ProviderAvailability>): List<ProviderAvailability> {
    val order = listOf(ConnectionType.GITHUB, ConnectionType.SUPABASE, ConnectionType.MCP_SERVER)
    return providers.sortedBy { availability ->
        order.indexOf(availability.type).takeIf { it >= 0 } ?: order.size
    }
}

internal fun primaryLabel(
    availability: ProviderAvailability,
    status: ConnectionStatus,
    connected: Boolean,
    setup: ProviderSetupSnapshot? = null,
): String = when {
    !availability.registered -> "Unavailable"
    connected -> "Manage"
    status.isAuthorizing -> "Cancel"
    status == ConnectionStatus.EXPIRED || status == ConnectionStatus.ERROR -> "Reconnect"
    setup?.lifecycle == IntegrationLifecycle.NOT_CONFIGURED -> "Set Up"
    !usesPersonalOAuthSetup(availability.type) ->
        if (availability.type == ConnectionType.MCP_SERVER) "Add Server" else "Add Connection"
    availability.supportsHostedAuthorization || setup?.canConnect == true -> "Connect"
    else -> "Add Server"
}

/** The one-line detail under a card: who is connected, or what is missing. */
private fun accountLine(
    status: ConnectionStatus,
    connection: Connection?,
    enabledTools: Int,
    setup: ProviderSetupSnapshot? = null,
): String? = when {
    setup?.lifecycle == IntegrationLifecycle.NOT_CONFIGURED ->
        "Add your Client ID to enable sign-in."
    status == ConnectionStatus.CONNECTED -> listOfNotNull(
        connection?.accountLabel ?: "Connected",
        enabledTools.takeIf { it > 0 }?.let { "$it tools available to the agent" },
    ).joinToString(" · ")
    status == ConnectionStatus.AUTHORIZING -> "Finish authorizing in your browser"
    status == ConnectionStatus.VERIFYING -> "Verifying with the provider"
    status == ConnectionStatus.ERROR || status == ConnectionStatus.EXPIRED -> connection?.statusMessage
    else -> connection?.statusMessage
}

internal fun lifecycleColor(lifecycle: IntegrationLifecycle): Color = when (lifecycle) {
    IntegrationLifecycle.CONNECTED -> ForgeMint
    IntegrationLifecycle.AUTHORIZING, IntegrationLifecycle.VERIFYING -> ForgePeriwinkle
    IntegrationLifecycle.ERROR -> ForgeDanger
    IntegrationLifecycle.EXPIRED, IntegrationLifecycle.DISCONNECTED, IntegrationLifecycle.NOT_CONFIGURED -> ForgeAmber
    IntegrationLifecycle.READY_TO_CONNECT -> ForgeMuted
}

internal fun statusColor(status: ConnectionStatus): Color = when (status) {
    ConnectionStatus.CONNECTED -> ForgeMint
    ConnectionStatus.AUTHORIZING, ConnectionStatus.VERIFYING, ConnectionStatus.CONNECTING -> ForgePeriwinkle
    ConnectionStatus.ERROR -> ForgeDanger
    ConnectionStatus.EXPIRED, ConnectionStatus.DISCONNECTED -> ForgeAmber
    ConnectionStatus.NOT_CONNECTED -> ForgeMuted
}

private const val MAX_CARD_CAPABILITIES = 3

/** Human-readable timestamp for "expires at" details. Never shows a credential. */
internal fun formatTimestamp(millis: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(millis))
