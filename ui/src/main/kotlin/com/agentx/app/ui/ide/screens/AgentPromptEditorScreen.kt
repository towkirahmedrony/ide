package com.agentx.app.ui.ide.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.agentx.app.agent.prompt.PromptTemplate
import com.agentx.app.ui.ide.components.IdeCard
import com.agentx.app.ui.ide.components.IdeSectionLabel
import com.agentx.app.ui.ide.components.IdeSpacer
import com.agentx.app.ui.ide.components.IdeStatusPill
import com.agentx.app.ui.ide.components.IdeTopBar
import com.agentx.app.ui.ide.state.AgentPromptEditorState
import com.agentx.app.ui.theme.ForgeCanvas
import com.agentx.app.ui.theme.ForgeDanger
import com.agentx.app.ui.theme.ForgeInk
import com.agentx.app.ui.theme.ForgeMint
import com.agentx.app.ui.theme.ForgeMuted
import com.agentx.app.ui.theme.ForgePeriwinkle

/**
 * Editor for one agent's system prompt. Deliberately a plain multiline text
 * field — no rich editor — with save, reset-to-default and discard.
 */
@Composable
fun AgentPromptEditorScreen(
    state: AgentPromptEditorState,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
    onDiscard: () -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ForgeCanvas,
        topBar = {
            IdeTopBar(
                title = state.name,
                subtitle = "Role · ${state.role.name}",
                onBack = onBack,
                actions = {
                    IdeStatusPill(
                        text = if (state.isCustom) "Custom prompt" else "Built-in default",
                        color = if (state.isCustom) ForgePeriwinkle else ForgeMuted,
                    )
                },
            )
        },
    ) { padding ->
        if (state.loading) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
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
            IdeCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        IdeSectionLabel("Enabled")
                        IdeSpacer(4)
                        Text(
                            text = "When disabled, the built-in default is used instead of your override.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ForgeMuted,
                        )
                    }
                    Switch(checked = state.enabled, onCheckedChange = onToggleEnabled)
                }
            }

            IdeCard {
                IdeSectionLabel("System prompt")
                IdeSpacer(6)
                OutlinedTextField(
                    value = state.prompt,
                    onValueChange = onEdit,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 12,
                    maxLines = 30,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        color = ForgeInk,
                    ),
                )
            }

            IdeCard {
                IdeSectionLabel("Template variables")
                IdeSpacer(6)
                PromptTemplate.SUPPORTED.forEach { variable ->
                    Text(
                        text = "{{${variable.name}}} — ${variable.description}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ForgeMuted,
                    )
                }
                IdeSpacer(6)
                Text(
                    text = "An unknown variable is left as-is and never breaks the agent. " +
                        "Secret values (keys, tokens, passwords) are never substituted.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ForgeMuted,
                )
            }

            state.message?.let { message ->
                IdeCard {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (message.startsWith("A prompt cannot")) ForgeDanger else ForgeInk,
                    )
                    IdeSpacer(6)
                    OutlinedButton(onClick = onDismissMessage, modifier = Modifier.fillMaxWidth()) {
                        Text("Dismiss")
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onSave,
                    enabled = state.dirty && state.prompt.isNotBlank() && !state.saving,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (state.saving) "Saving…" else "Save")
                }
                OutlinedButton(
                    onClick = onDiscard,
                    enabled = state.dirty,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Discard")
                }
            }
            OutlinedButton(
                onClick = onReset,
                enabled = state.isCustom,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Reset to default")
            }
            IdeSpacer(8)
        }
    }
}
