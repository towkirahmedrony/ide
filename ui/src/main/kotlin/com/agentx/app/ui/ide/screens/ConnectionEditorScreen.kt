package com.agentx.app.ui.ide.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.agentx.app.integrations.connection.ConnectionAuthMethod
import com.agentx.app.integrations.connection.ConnectionCapabilities
import com.agentx.app.integrations.connection.ConnectionType
import com.agentx.app.integrations.mcp.McpTransportKind
import com.agentx.app.ui.ide.components.IdeCard
import com.agentx.app.ui.ide.components.IdeSectionLabel
import com.agentx.app.ui.ide.components.IdeSpacer
import com.agentx.app.ui.ide.components.IdeTopBar
import com.agentx.app.ui.ide.state.ConnectionEditorState
import com.agentx.app.ui.theme.ForgeCanvas
import com.agentx.app.ui.theme.ForgeDanger
import com.agentx.app.ui.theme.ForgeInk
import com.agentx.app.ui.theme.ForgeMint
import com.agentx.app.ui.theme.ForgeMuted
import com.agentx.app.ui.theme.ForgeSurfaceVariant

/**
 * Add/Edit Connection form. Credentials are write-only. OAuth is listed but
 * cannot succeed: there is no implementation yet, and the form says so.
 */
@Composable
fun ConnectionEditorScreen(
    state: ConnectionEditorState,
    onBack: () -> Unit,
    onEdit: ((ConnectionEditorState) -> ConnectionEditorState) -> Unit,
    onSave: () -> Unit,
    onRemoveCredential: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ForgeCanvas,
        topBar = {
            IdeTopBar(
                title = if (state.isEditing) "Edit connection" else "Add connection",
                subtitle = "External service",
                onBack = onBack,
            )
        },
    ) { padding ->
        if (state.loading) {
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
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (state.missing) {
                IdeCard {
                    IdeSectionLabel("Connection")
                    IdeSpacer(6)
                    Text(
                        text = "This connection no longer exists. It may have been removed.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ForgeDanger,
                    )
                }
                return@Column
            }

            Field(
                label = "Display name",
                value = state.displayName,
                onValueChange = { value -> onEdit { it.copy(displayName = value) } },
            )

            Choice(
                label = "Service type",
                options = ConnectionType.entries.map { it to it.displayName },
                selected = state.type,
                onSelect = { type ->
                    onEdit {
                        it.copy(
                            type = type,
                            authMethod = type.defaultAuthMethod,
                            mcpTransport = McpTransportKind.STDIO,
                        )
                    }
                },
            )

            Text(
                text = state.type.description,
                style = MaterialTheme.typography.bodyMedium,
                color = ForgeMuted,
            )

            if (state.showMcpFields) {
                Choice(
                    label = "MCP transport",
                    options = McpTransportKind.entries.map { it to it.displayName },
                    selected = state.mcpTransport,
                    onSelect = { transport -> onEdit { it.copy(mcpTransport = transport) } },
                )
                if (state.mcpTransport == McpTransportKind.STDIO) {
                    Field(
                        label = "Command",
                        value = state.mcpCommand,
                        onValueChange = { value -> onEdit { it.copy(mcpCommand = value) } },
                    )
                }
            }

            if (state.showEndpoint) {
                Field(
                    label = "Endpoint / base URL",
                    value = state.endpoint,
                    onValueChange = { value -> onEdit { it.copy(endpoint = value) } },
                )
            }

            Choice(
                label = "Authentication",
                options = ConnectionAuthMethod.entries.map { it to it.displayName },
                selected = state.authMethod,
                onSelect = { method -> onEdit { it.copy(authMethod = method) } },
            )

            if (state.showOAuthSection) {
                IdeCard {
                    IdeSectionLabel("OAuth")
                    IdeSpacer(6)
                    Text(
                        text = when {
                            state.canAuthorizeWithOAuth ->
                                "${state.type.displayName} is authorized on ${state.type.displayName}'s own " +
                                    "page. Manual credentials are only a fallback."

                            else -> state.oauthUnavailableReason
                                ?: "OAuth is not available for ${state.type.displayName} in this build."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = ForgeMuted,
                    )
                    if (state.awaitingAuthorization) {
                        IdeSpacer(6)
                        Text(
                            text = "Waiting for you to approve access in the browser…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ForgeInk,
                        )
                    }
                }
            }

            if (state.showUsername) {
                Field(
                    label = "Username",
                    value = state.username,
                    onValueChange = { value -> onEdit { it.copy(username = value) } },
                )
            }

            if (state.showCredential) {
                Field(
                    label = credentialLabel(state),
                    value = state.credential,
                    onValueChange = { value -> onEdit { it.copy(credential = value) } },
                    visualTransformation = PasswordVisualTransformation(),
                )
                if (state.hasStoredCredential) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "A credential is stored for this connection.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ForgeMuted,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onRemoveCredential) { Text("Remove") }
                    }
                }
            }

            Choice(
                label = "Availability",
                options = listOf(true to "Enabled", false to "Disabled"),
                selected = state.enabled,
                onSelect = { enabled -> onEdit { it.copy(enabled = enabled) } },
            )

            IdeCard {
                IdeSectionLabel("Capabilities")
                IdeSpacer(6)
                Text(
                    text = ConnectionCapabilities.defaultsFor(state.type).joinToString(", ") { it.id },
                    style = MaterialTheme.typography.bodyMedium,
                    color = ForgeInk,
                )
                IdeSpacer(6)
                Text(
                    text = "Declared for future tools. Nothing is called yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ForgeMuted,
                )
            }

            if (state.errors.isNotEmpty()) {
                IdeCard {
                    IdeSectionLabel("Fix these first")
                    IdeSpacer(6)
                    state.errors.forEach { error ->
                        Text(
                            text = "· $error",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ForgeDanger,
                        )
                    }
                }
            }

            Button(
                onClick = onSave,
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.saving) "Saving…" else "Save connection")
            }
            IdeSpacer(8)
        }
    }
}

private fun credentialLabel(state: ConnectionEditorState): String {
    val kind = when (state.authMethod) {
        ConnectionAuthMethod.API_KEY -> "API key"
        ConnectionAuthMethod.ACCESS_TOKEN -> "Access token"
        ConnectionAuthMethod.USERNAME_PASSWORD -> "Password"
        else -> "Credential"
    }
    return if (state.hasStoredCredential) "Replace $kind (optional)" else kind
}

@Composable
private fun Field(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        visualTransformation = visualTransformation,
    )
}

@Composable
private fun <T> Choice(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    IdeCard {
        IdeSectionLabel(label)
        IdeSpacer(6)
        options.forEach { (value, text) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(value) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .background(
                            if (value == selected) ForgeMint else ForgeSurfaceVariant,
                            CircleShape,
                        ),
                )
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (value == selected) ForgeInk else ForgeMuted,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
        }
    }
}
