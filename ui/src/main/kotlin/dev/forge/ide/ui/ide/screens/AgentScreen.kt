package dev.forge.ide.ui.ide.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.forge.ide.ui.ide.model.AgentActivity
import dev.forge.ide.ui.ide.model.AgentActivityStatus
import dev.forge.ide.ui.ide.model.ChatMessage
import dev.forge.ide.ui.ide.model.ChatRole
import dev.forge.ide.ui.ide.state.AgentUiState
import dev.forge.ide.ui.ide.state.AgentViewModel
import dev.forge.ide.ui.theme.ForgeAmber
import dev.forge.ide.ui.theme.ForgeCanvas
import dev.forge.ide.ui.theme.ForgeDanger
import dev.forge.ide.ui.theme.ForgeInk
import dev.forge.ide.ui.theme.ForgeMint
import dev.forge.ide.ui.theme.ForgeMuted
import dev.forge.ide.ui.theme.ForgePeriwinkle
import dev.forge.ide.ui.theme.ForgeSurfaceVariant

@Composable
fun AgentScreen(
    viewModel: AgentViewModel,
    modifier: Modifier = Modifier,
) {
    val state = viewModel.uiState
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.text) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    Column(modifier = modifier.fillMaxSize().background(ForgeCanvas)) {
        AgentActivityBar(
            activity = state.activity,
            running = state.running,
            currentAgent = state.currentAgent,
        )

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.messages, key = { it.id }) { message ->
                ChatBubble(message)
            }
        }

        AgentInputBar(
            state = state,
            onInputChange = viewModel::onInputChange,
            onSend = viewModel::send,
            onStop = viewModel::stop,
        )
    }
}

@Composable
private fun AgentActivityBar(activity: AgentActivity, running: Boolean, currentAgent: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ForgeSurfaceVariant)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (running) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = activityColor(activity.status),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(activityColor(activity.status), RoundedCornerShape(50))
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = "$currentAgent · ${activity.label}",
            style = MaterialTheme.typography.labelSmall,
            color = ForgeInk,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = activity.status.name.lowercase(),
            style = MaterialTheme.typography.labelSmall,
            color = activityColor(activity.status),
        )
    }
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    when (message.role) {
        ChatRole.SYSTEM -> Text(
            text = message.text,
            style = MaterialTheme.typography.labelSmall,
            color = ForgeMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        )

        ChatRole.USER -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.82f)
                    .background(ForgePeriwinkle.copy(alpha = 0.18f), RoundedCornerShape(14.dp))
                    .padding(12.dp),
            ) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ForgeInk,
                )
            }
        }

        ChatRole.AGENT -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .background(ForgeSurfaceVariant, RoundedCornerShape(14.dp))
                    .padding(12.dp),
            ) {
                Text(
                    text = message.text.ifEmpty { "…" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = ForgeInk,
                )
            }
        }
    }
}

@Composable
private fun AgentInputBar(
    state: AgentUiState,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ForgeSurfaceVariant)
            .padding(12.dp),
    ) {
        OutlinedTextField(
            value = state.input,
            onValueChange = onInputChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.running,
            minLines = 1,
            maxLines = 5,
            placeholder = { Text("Describe a task for the agent…") },
        )
        Spacer(Modifier.size(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.running) {
                Button(
                    onClick = onStop,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Stop")
                }
            } else {
                Button(
                    onClick = onSend,
                    enabled = state.input.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Send")
                }
            }
        }
    }
}

private fun activityColor(status: AgentActivityStatus): Color = when (status) {
    AgentActivityStatus.IDLE -> ForgeMuted
    AgentActivityStatus.THINKING -> ForgePeriwinkle
    AgentActivityStatus.USING_TOOL -> ForgeMint
    AgentActivityStatus.WAITING -> ForgeAmber
    AgentActivityStatus.COMPLETED -> ForgeMint
    AgentActivityStatus.ERROR -> ForgeDanger
}
