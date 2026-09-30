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
import androidx.compose.material3.OutlinedButton
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
import com.agentx.app.model.connect.ModelConnectPhase
import com.agentx.app.model.connect.ModelSetupKind
import com.agentx.app.model.preset.EndpointDiscoveryMode
import com.agentx.app.model.preset.ModelApiProtocol
import com.agentx.app.model.preset.ModelProviderType
import com.agentx.app.model.preset.TunnelType
import com.agentx.app.ui.ide.components.IdeCard
import com.agentx.app.ui.ide.components.IdeLabelValue
import com.agentx.app.ui.ide.components.IdeSectionLabel
import com.agentx.app.ui.ide.components.IdeSpacer
import com.agentx.app.ui.ide.components.IdeStatusPill
import com.agentx.app.ui.ide.components.IdeTopBar
import com.agentx.app.ui.ide.state.ModelEditorState
import com.agentx.app.ui.theme.ForgeCanvas
import com.agentx.app.ui.theme.ForgeDanger
import com.agentx.app.ui.theme.ForgeInk
import com.agentx.app.ui.theme.ForgeMint
import com.agentx.app.ui.theme.ForgeMuted
import com.agentx.app.ui.theme.ForgeSurfaceVariant

/**
 * Main Agent model setup. Quick Connect is the primary, phone-sized path;
 * Advanced keeps the existing manual fields collapsed by default.
 */
@Composable
fun ModelEditorScreen(
    state: ModelEditorState,
    onBack: () -> Unit,
    onEdit: ((ModelEditorState) -> ModelEditorState) -> Unit,
    onSave: () -> Unit,
    onConnect: () -> Unit,
    onToggleAdvanced: () -> Unit,
    onRemoveCredential: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ForgeCanvas,
        topBar = {
            IdeTopBar(
                title = if (state.isEditing) "Edit model" else "AI Model",
                subtitle = "Main Agent",
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
                    IdeSectionLabel("Model")
                    IdeSpacer(6)
                    Text(
                        text = "This model no longer exists. It may have been deleted.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ForgeDanger,
                    )
                }
                return@Column
            }

            state.connectedSummary?.let { summary ->
                IdeCard {
                    IdeSectionLabel("Connected")
                    IdeSpacer(8)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = summary.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            color = ForgeInk,
                            modifier = Modifier.weight(1f),
                        )
                        IdeStatusPill(summary.state, ForgeMint)
                    }
                    IdeLabelValue("API", summary.protocol)
                    IdeLabelValue("Endpoint", summary.endpoint)
                    if (summary.modelId.isNotBlank()) {
                        IdeLabelValue("Model ID", summary.modelId)
                    }
                    IdeSpacer(4)
                    Text(
                        text = "The Main Agent can use this model now.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ForgeMuted,
                    )
                }
            }

            Choice(
                label = "Provider",
                options = ModelSetupKind.entries.map { it to it.displayName },
                selected = state.setupKind,
                onSelect = { kind ->
                    onEdit { current ->
                        current.copy(
                            setupKind = kind,
                            providerType = when (kind) {
                                ModelSetupKind.CUSTOM -> current.providerType
                                ModelSetupKind.GEMINI, ModelSetupKind.GROQ ->
                                    ModelProviderType.REMOTE_OPENAI_COMPATIBLE
                            },
                        )
                    }
                },
            )

            IdeCard {
                IdeSectionLabel("Quick Connect")
                IdeSpacer(8)
                Text(
                    text = state.setupKind.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ForgeMuted,
                )
            }

            Field(
                label = "Model Name",
                value = state.displayName,
                onValueChange = { value -> onEdit { it.copy(displayName = value) } },
            )

            if (state.setupKind.showsEndpointField) {
                Field(
                    label = "Endpoint URL",
                    value = state.explicitEndpoint,
                    onValueChange = { value -> onEdit { it.copy(explicitEndpoint = value) } },
                )
            }

            Field(
                label = when {
                    state.setupKind.requiresApiKey && !state.hasStoredCredential -> "API Key"
                    state.hasStoredCredential -> "Replace API key (optional)"
                    else -> "API Key (optional)"
                },
                value = state.credential,
                onValueChange = { value -> onEdit { it.copy(credential = value) } },
                visualTransformation = PasswordVisualTransformation(),
            )
            if (state.hasStoredCredential) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "A credential is stored for this model.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ForgeMuted,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onRemoveCredential) { Text("Remove") }
                }
            }

            if (state.availableModels.isNotEmpty()) {
                Choice(
                    label = "Model",
                    options = state.availableModels.map { it to it },
                    selected = state.modelIdentifier,
                    onSelect = { id -> onEdit { it.copy(modelIdentifier = id) } },
                )
            }

            if (state.connecting) {
                IdeCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = ForgeMint, modifier = Modifier.size(18.dp))
                        Text(
                            text = state.connectPhase?.displayName ?: ModelConnectPhase.CONNECTING.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = ForgeInk,
                            modifier = Modifier.padding(start = 10.dp),
                        )
                    }
                }
            }

            if (state.errors.isNotEmpty()) {
                IdeCard {
                    IdeSectionLabel("Could not connect")
                    IdeSpacer(6)
                    state.errors.forEach { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = ForgeDanger,
                        )
                    }
                }
            }

            Button(
                onClick = onConnect,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        state.connecting -> state.connectPhase?.displayName ?: "Connecting…"
                        state.availableModels.isNotEmpty() -> "Connect with selected model"
                        else -> "Connect"
                    },
                )
            }

            TextButton(onClick = onToggleAdvanced, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.advancedOpen) "Hide Advanced" else "Advanced")
            }

            if (state.advancedOpen) {
                AdvancedFields(state = state, onEdit = onEdit)
                OutlinedButton(
                    onClick = onSave,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.saving) "Saving…" else "Save without connecting")
                }
            }

            IdeSpacer(8)
        }
    }
}

@Composable
private fun AdvancedFields(
    state: ModelEditorState,
    onEdit: ((ModelEditorState) -> ModelEditorState) -> Unit,
) {
    Text(
        text = "Use these only for unusual servers. Quick Connect detects the rest.",
        style = MaterialTheme.typography.bodyMedium,
        color = ForgeMuted,
    )

    Choice(
        label = "Where the model runs",
        options = ModelProviderType.entries.map { it to it.displayName },
        selected = state.providerType,
        onSelect = { provider -> onEdit { it.copy(providerType = provider) } },
    )

    Field(
        label = "Model ID",
        value = state.modelIdentifier,
        onValueChange = { value -> onEdit { it.copy(modelIdentifier = value) } },
    )

    Choice(
        label = "API protocol",
        options = ModelApiProtocol.entries.map { it to it.displayName },
        selected = state.apiProtocol,
        onSelect = { protocol ->
            onEdit { it.copy(apiProtocol = protocol, apiBasePath = protocol.defaultApiBasePath) }
        },
    )

    Field(
        label = "Custom API path",
        value = state.apiBasePath,
        onValueChange = { value -> onEdit { it.copy(apiBasePath = value) } },
    )

    Field(
        label = "Timeout (ms)",
        value = state.healthTimeoutMillis,
        onValueChange = { value -> onEdit { it.copy(healthTimeoutMillis = value.filter(Char::isDigit)) } },
        keyboardType = KeyboardType.Number,
    )

    Field(
        label = "Server port",
        value = state.serverPort,
        onValueChange = { value -> onEdit { it.copy(serverPort = value.filter(Char::isDigit)) } },
        keyboardType = KeyboardType.Number,
    )

    Choice(
        label = "Endpoint discovery",
        options = EndpointDiscoveryMode.entries.map { it to it.displayName },
        selected = state.endpointMode,
        onSelect = { mode -> onEdit { it.copy(endpointMode = mode) } },
    )

    Choice(
        label = "Tunnel",
        options = TunnelType.entries.map { it to it.displayName },
        selected = state.tunnelType,
        onSelect = { tunnel -> onEdit { it.copy(tunnelType = tunnel) } },
    )

    Field(
        label = "Runtime output marker",
        value = state.tunnelMarker,
        onValueChange = { value -> onEdit { it.copy(tunnelMarker = value) } },
    )

    if (state.requireEndpointField && state.endpointMode != EndpointDiscoveryMode.CONFIGURED_ENDPOINT) {
        Field(
            label = "Endpoint (https://…)",
            value = state.explicitEndpoint,
            onValueChange = { value -> onEdit { it.copy(explicitEndpoint = value) } },
        )
    }

    if (state.requireNotebookUrl) {
        Field(
            label = "Colab notebook URL",
            value = state.colabNotebookUrl,
            onValueChange = { value -> onEdit { it.copy(colabNotebookUrl = value) } },
        )
        Text(
            text = "Android cannot start a Colab runtime. The notebook URL is what the " +
                "Model Runner opens; you start the runtime there.",
            style = MaterialTheme.typography.bodyMedium,
            color = ForgeMuted,
        )
    }

    MultilineField(
        label = "Startup script / configuration",
        value = state.startupScript,
        onValueChange = { value -> onEdit { it.copy(startupScript = value) } },
    )

    Field(
        label = "Health check path (optional)",
        value = state.healthPath,
        onValueChange = { value -> onEdit { it.copy(healthPath = value) } },
    )

    Choice(
        label = "Availability",
        options = listOf(true to "Enabled", false to "Disabled"),
        selected = state.enabled,
        onSelect = { enabled -> onEdit { it.copy(enabled = enabled) } },
    )
}

@Composable
private fun Field(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = visualTransformation,
    )
}

@Composable
private fun MultilineField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = false,
        minLines = 4,
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
