package com.agentx.app.ui.ide.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.agentx.app.model.connect.ModelSetupKind
import com.agentx.app.ui.ide.components.IdeSectionLabel
import com.agentx.app.ui.ide.components.IdeSettingRow
import com.agentx.app.ui.ide.components.IdeSpacer
import com.agentx.app.ui.ide.components.IdeTopBar
import com.agentx.app.ui.ide.state.API_PROVIDER_KINDS
import com.agentx.app.ui.ide.state.ModelConnectionType
import com.agentx.app.ui.ide.state.ModelChoices
import com.agentx.app.ui.ide.state.ModelEditorState
import com.agentx.app.ui.ide.state.ModelSetupField
import com.agentx.app.ui.ide.state.ModelSetupForm
import com.agentx.app.ui.theme.ForgeAmber
import com.agentx.app.ui.theme.ForgeCanvas
import com.agentx.app.ui.theme.ForgeDanger
import com.agentx.app.ui.theme.ForgeMint
import com.agentx.app.ui.theme.ForgeMuted

/**
 * Add/Edit model.
 *
 * Two decisions, then the fields that belong to them: a connection type, a
 * provider and model, and either a server URL or an API key. Nothing else is
 * asked for — the remaining preset fields are derived or inherited.
 */
@Composable
fun ModelEditorScreen(
    state: ModelEditorState,
    onBack: () -> Unit,
    onEdit: ((ModelSetupForm) -> ModelSetupForm) -> Unit,
    onSelectConnectionType: (ModelConnectionType) -> Unit,
    onSelectProvider: (ModelSetupKind) -> Unit,
    onSelectModel: (String) -> Unit,
    onToggleManualModel: (Boolean) -> Unit,
    onRetryCatalog: () -> Unit,
    onConnect: () -> Unit,
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
                onBack = onBack,
            )
        },
    ) { padding ->
        if (state.loading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = ForgeMint, modifier = Modifier.size(20.dp))
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (state.missing) {
                Text(
                    text = "This model no longer exists. It may have been deleted.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ForgeDanger,
                )
                return@Column
            }

            ConnectionTypeSelector(state, onSelectConnectionType)

            if (state.form.connectionType == ModelConnectionType.API) {
                ProviderSelector(state, onSelectProvider)
            }

            FormField(
                label = "Name",
                value = state.form.name,
                onValueChange = { value -> onEdit { it.copy(name = value) } },
                supporting = "How this model appears in the list",
            )

            if (state.form.connectionType == ModelConnectionType.LOCAL) {
                FormField(
                    label = "Model",
                    value = state.form.modelId,
                    onValueChange = { value -> onEdit { it.copy(modelId = value) } },
                    supporting = "Model id sent to the endpoint",
                )
                FormField(
                    label = "Server URL",
                    value = state.form.serverUrl,
                    onValueChange = { value -> onEdit { it.copy(serverUrl = value) } },
                    keyboardType = KeyboardType.Uri,
                    isError = state.issue(ModelSetupField.SERVER_URL) != null,
                    supporting = state.issue(ModelSetupField.SERVER_URL)
                        ?: ModelConnectionType.LOCAL.helper,
                )
            } else {
                ModelField(state, onEdit, onSelectModel, onToggleManualModel, onRetryCatalog)
                FormField(
                    label = if (state.form.hasStoredCredential) "Replace API key" else "API key",
                    value = state.form.credential,
                    onValueChange = { value -> onEdit { it.copy(credential = value) } },
                    visualTransformation = PasswordVisualTransformation(),
                    isError = state.issue(ModelSetupField.API_KEY) != null,
                    supporting = state.issue(ModelSetupField.API_KEY)
                        ?: "Stored encrypted on this device",
                )
                if (state.form.hasStoredCredential) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "A key is already stored for this model.",
                            style = MaterialTheme.typography.bodySmall,
                            color = ForgeMuted,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onRemoveCredential) { Text("Remove") }
                    }
                }
            }

            if (state.discoveredModels.isNotEmpty()) {
                IdeSpacer(8)
                IdeSectionLabel("Choose a model")
                state.discoveredModels.forEach { id ->
                    IdeSettingRow(
                        label = id,
                        value = if (id == state.form.modelId) "Selected" else null,
                        onClick = { onSelectModel(id) },
                    )
                }
            }

            if (state.connecting) {
                IdeSpacer(10)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = ForgeMint, modifier = Modifier.size(16.dp))
                    Text(
                        text = state.connectPhase?.displayName ?: "Connecting…",
                        style = MaterialTheme.typography.bodySmall,
                        color = ForgeMuted,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }

            state.catalogError?.let { message ->
                IdeSpacer(8)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = ForgeAmber,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onRetryCatalog) { Text("Retry") }
                }
            }

            if (state.errors.isNotEmpty()) {
                IdeSpacer(8)
                state.errors.forEach { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = ForgeDanger,
                    )
                }
            }

            IdeSpacer(14)
            Button(
                onClick = onConnect,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(state.primaryActionLabel)
            }
            TextButton(
                onClick = onSave,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.saving) "Saving…" else "Save without testing")
            }
            IdeSpacer(16)
        }
    }
}

/** Local/API choice, rendered as two compact chips. */
@Composable
private fun ConnectionTypeSelector(
    state: ModelEditorState,
    onSelect: (ModelConnectionType) -> Unit,
) {
    IdeSectionLabel("Connection")
    IdeSpacer(6)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ModelConnectionType.entries.forEach { type ->
            FilterChip(
                selected = state.form.connectionType == type,
                onClick = { onSelect(type) },
                label = { Text(type.displayName) },
                leadingIcon = if (state.form.connectionType == type) {
                    { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                } else {
                    null
                },
            )
        }
    }
    IdeSpacer(6)
    Text(
        text = state.form.connectionType.helper,
        style = MaterialTheme.typography.bodySmall,
        color = ForgeMuted,
    )
    IdeSpacer(10)
}

@Composable
private fun ProviderSelector(
    state: ModelEditorState,
    onSelect: (ModelSetupKind) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IdeSettingRow(
            label = "Provider",
            value = state.form.apiProvider.displayName,
            onClick = { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            API_PROVIDER_KINDS.forEach { kind ->
                DropdownMenuItem(
                    text = { Text(kind.displayName) },
                    onClick = {
                        open = false
                        onSelect(kind)
                    },
                )
            }
        }
    }
}

/** The model id: a picker when a list is available, a field otherwise. */
@Composable
private fun ModelField(
    state: ModelEditorState,
    onEdit: ((ModelSetupForm) -> ModelSetupForm) -> Unit,
    onSelectModel: (String) -> Unit,
    onToggleManualModel: (Boolean) -> Unit,
    onRefreshModels: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }

    if (state.hasModelList) {
        Box {
            IdeSettingRow(
                label = "Model",
                value = ModelChoices.labelFor(state.models, state.form.modelId).ifBlank { "Select" },
                enabled = !state.catalogLoading,
                onClick = { open = true },
            )
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                state.models.forEach { choice ->
                    DropdownMenuItem(
                        text = { Text(choice.label) },
                        onClick = {
                            open = false
                            onSelectModel(choice.id)
                        },
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = sourceLabel(state),
                style = MaterialTheme.typography.bodySmall,
                color = ForgeMuted,
                modifier = Modifier.weight(1f),
            )
            if (state.catalogLoading) {
                CircularProgressIndicator(color = ForgeMint, modifier = Modifier.size(14.dp))
            }
            TextButton(onClick = onRefreshModels, enabled = !state.catalogLoading) { Text("Refresh") }
            TextButton(onClick = { onToggleManualModel(true) }) { Text("Enter manually") }
        }
    } else {
        FormField(
            label = "Model",
            value = state.form.modelId,
            onValueChange = { value -> onEdit { it.copy(modelId = value) } },
            isError = state.issue(ModelSetupField.MODEL) != null,
            supporting = state.issue(ModelSetupField.MODEL)
                ?: "Model id sent to the provider",
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.catalogLoading) {
                CircularProgressIndicator(color = ForgeMint, modifier = Modifier.size(14.dp))
            }
            if (state.models.isNotEmpty()) {
                TextButton(onClick = { onToggleManualModel(false) }) { Text("Choose from list") }
            }
            TextButton(onClick = onRefreshModels, enabled = !state.catalogLoading) { Text("Refresh") }
        }
    }
}

/** Where the offered models came from, so a fallback list is never mistaken for a live one. */
private fun sourceLabel(state: ModelEditorState): String = when {
    state.modelsFromCatalog -> "From ${state.form.apiProvider.displayName}'s model list"
    state.catalogError != null -> "${state.form.apiProvider.displayName} models unavailable"
    else -> "Built-in ${state.form.apiProvider.displayName} list"
}

@Composable
private fun FormField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    supporting: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    isError: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        singleLine = true,
        isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = visualTransformation,
        supportingText = supporting?.let { text -> { Text(text) } },
        shape = RoundedCornerShape(10.dp),
    )
}
