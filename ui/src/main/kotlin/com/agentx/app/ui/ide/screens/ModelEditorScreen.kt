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
import com.agentx.app.model.preset.EndpointDiscoveryMode
import com.agentx.app.model.preset.ModelApiProtocol
import com.agentx.app.model.preset.ModelProviderType
import com.agentx.app.model.preset.TunnelType
import com.agentx.app.ui.ide.components.IdeCard
import com.agentx.app.ui.ide.components.IdeSectionLabel
import com.agentx.app.ui.ide.components.IdeSpacer
import com.agentx.app.ui.ide.components.IdeTopBar
import com.agentx.app.ui.ide.state.ModelEditorState
import com.agentx.app.ui.theme.ForgeCanvas
import com.agentx.app.ui.theme.ForgeDanger
import com.agentx.app.ui.theme.ForgeInk
import com.agentx.app.ui.theme.ForgeMint
import com.agentx.app.ui.theme.ForgeMuted
import com.agentx.app.ui.theme.ForgeSurfaceVariant

/**
 * Add/Edit Model form. Only the fields a preset needs are shown, and validation
 * is the domain's job: the errors below the form come straight from the preset
 * validator, so the form can never accept something the manager would reject.
 */
@Composable
fun ModelEditorScreen(
    state: ModelEditorState,
    onBack: () -> Unit,
    onEdit: ((ModelEditorState) -> ModelEditorState) -> Unit,
    onSave: () -> Unit,
    onRemoveCredential: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ForgeCanvas,
        topBar = {
            IdeTopBar(
                title = if (state.isEditing) "Edit model" else "Add model",
                subtitle = "Model preset",
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

            Field(
                label = "Name",
                value = state.displayName,
                onValueChange = { value -> onEdit { it.copy(displayName = value) } },
            )

            Choice(
                label = "Where the model runs",
                options = ModelProviderType.entries.map { it to it.displayName },
                selected = state.providerType,
                onSelect = { provider -> onEdit { it.copy(providerType = provider) } },
            )

            Field(
                label = "Model identifier",
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
                label = "API base path",
                value = state.apiBasePath,
                onValueChange = { value -> onEdit { it.copy(apiBasePath = value) } },
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

            if (state.requireEndpointField) {
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
            Text(
                text = "Saved with the preset. Wire it up so that the runtime prints the tunnel " +
                    "endpoint after the marker above — that is how the app finds the model API.",
                style = MaterialTheme.typography.bodyMedium,
                color = ForgeMuted,
            )

            Field(
                label = "Health check path (optional)",
                value = state.healthPath,
                onValueChange = { value -> onEdit { it.copy(healthPath = value) } },
            )

            Field(
                label = if (state.hasStoredCredential) "Replace API key (optional)" else "API key (optional)",
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

            Choice(
                label = "Availability",
                options = listOf(true to "Enabled", false to "Disabled"),
                selected = state.enabled,
                onSelect = { enabled -> onEdit { it.copy(enabled = enabled) } },
            )

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
                Text(if (state.saving) "Saving…" else "Save model")
            }
            IdeSpacer(8)
        }
    }
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

/** Same field, sized for a script. */
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
