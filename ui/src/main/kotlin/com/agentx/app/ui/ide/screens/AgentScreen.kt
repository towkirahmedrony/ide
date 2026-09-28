package com.agentx.app.ui.ide.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentx.app.ui.ide.components.IdeDivider
import com.agentx.app.ui.ide.components.IdeStatusPill
import com.agentx.app.ui.ide.model.AgentActivity
import com.agentx.app.ui.ide.model.AgentActivityStatus
import com.agentx.app.ui.ide.model.ChatMessage
import com.agentx.app.ui.ide.model.ChatRole
import com.agentx.app.ui.ide.state.AgentUiState
import com.agentx.app.ui.ide.state.AgentViewModel
import com.agentx.app.ui.ide.state.PermissionPrompt
import com.agentx.app.ui.theme.ForgeAmber
import com.agentx.app.ui.theme.ForgeBorder
import com.agentx.app.ui.theme.ForgeCanvas
import com.agentx.app.ui.theme.ForgeDanger
import com.agentx.app.ui.theme.ForgeInk
import com.agentx.app.ui.theme.ForgeMint
import com.agentx.app.ui.theme.ForgeMuted
import com.agentx.app.ui.theme.ForgePeriwinkle
import com.agentx.app.ui.theme.ForgeSurface
import com.agentx.app.ui.theme.ForgeSurfaceVariant

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
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(state.messages, key = { it.id }) { message ->
                ChatBubble(message)
            }
        }

        state.pendingPermission?.let { prompt ->
            PermissionPromptCard(
                prompt = prompt,
                onDecision = viewModel::respondToPermission,
            )
        }

        AgentInputBar(
            state = state,
            onInputChange = viewModel::onInputChange,
            onSend = viewModel::send,
            onStop = viewModel::stop,
        )
    }
}

// ───────────────────────────── Status bar ─────────────────────────────

@Composable
private fun AgentActivityBar(activity: AgentActivity, running: Boolean, currentAgent: String) {
    val color = activityColor(activity.status)
    Column(modifier = Modifier.fillMaxWidth().background(ForgeSurface)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(14.dp), contentAlignment = Alignment.Center) {
                if (running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = color,
                    )
                } else {
                    Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = "$currentAgent · ${activity.label}",
                style = MaterialTheme.typography.labelSmall,
                color = ForgeInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            IdeStatusPill(
                text = activity.status.name.lowercase().replace('_', ' '),
                color = color,
            )
        }
        IdeDivider()
    }
}

// ───────────────────────────── Messages ─────────────────────────────

@Composable
private fun ChatBubble(message: ChatMessage) {
    when (message.role) {
        ChatRole.SYSTEM -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.labelSmall,
                color = ForgeMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .background(ForgeSurfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }

        ChatRole.USER -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Box(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .background(
                        ForgePeriwinkle.copy(alpha = 0.18f),
                        RoundedCornerShape(
                            topStart = 18.dp,
                            topEnd = 18.dp,
                            bottomStart = 18.dp,
                            bottomEnd = 4.dp,
                        ),
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                SelectionContainer {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp),
                        color = ForgeInk,
                    )
                }
            }
        }

        // Agent replies are plain text on the canvas with a small avatar,
        // like ChatGPT / Claude, instead of a boxed bubble.
        ChatRole.AGENT -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AgentAvatar()
            Column(modifier = Modifier.weight(1f).padding(top = 3.dp)) {
                if (message.text.isEmpty()) {
                    TypingDots()
                } else {
                    SelectionContainer {
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 23.sp),
                            color = ForgeInk,
                        )
                    }
                }
            }
        }

        // Tool activity is shown inline so the conversation records what the
        // agent actually did, not just what it said.
        ChatRole.TOOL -> Row(
            modifier = Modifier.fillMaxWidth().padding(start = 38.dp),
        ) {
            ToolCard(message)
        }
    }
}

@Composable
private fun AgentAvatar() {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(ForgeMint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.AutoAwesome,
            contentDescription = null,
            tint = ForgeMint,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun TypingDots() {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(
        modifier = Modifier.height(22.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(500),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(index * 160),
                ),
                label = "dot$index",
            )
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(ForgeMuted.copy(alpha = alpha), CircleShape),
            )
        }
    }
}

@Composable
private fun ToolCard(message: ChatMessage) {
    val lines = message.text.lines()
    val header = lines.firstOrNull().orEmpty().removePrefix("▶").trim()
    val result = lines.drop(1).joinToString("\n").trim()
    val failed = result.startsWith("✖")
    val resultText = result.removePrefix("✔").removePrefix("✖").trim()
    val tint = when {
        message.streaming -> ForgeMint
        failed -> ForgeDanger
        else -> ForgeMint
    }
    val shape = RoundedCornerShape(12.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ForgeSurface)
            .border(1.dp, ForgeBorder, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier.padding(top = 2.dp).size(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                message.streaming -> CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = tint,
                )
                failed -> Icon(Icons.Filled.Close, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                else -> Icon(Icons.Filled.Check, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = header,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = ForgeInk,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (resultText.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = resultText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = if (failed) ForgeDanger else ForgeMuted,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ───────────────────────────── Permission ─────────────────────────────

@Composable
private fun PermissionPromptCard(
    prompt: PermissionPrompt,
    onDecision: (Boolean) -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(shape)
            .background(ForgeAmber.copy(alpha = 0.10f))
            .border(1.dp, ForgeAmber.copy(alpha = 0.35f), shape)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = ForgeAmber,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Permission required",
                style = MaterialTheme.typography.titleMedium,
                color = ForgeAmber,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = prompt.toolName,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = ForgeInk,
            modifier = Modifier
                .background(ForgeCanvas.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
        if (prompt.detail.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = prompt.detail,
                style = MaterialTheme.typography.bodySmall,
                color = ForgeMuted,
            )
        }
        if (prompt.requiredPermission.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Requires: ${prompt.requiredPermission}",
                style = MaterialTheme.typography.labelSmall,
                color = ForgeMuted,
            )
        }
        if (prompt.reason.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = prompt.reason,
                style = MaterialTheme.typography.labelSmall,
                color = ForgeMuted,
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = { onDecision(false) },
                modifier = Modifier.weight(1f).height(44.dp),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, ForgeBorder),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = ForgeDanger),
            ) {
                Text("Deny")
            }
            Button(
                onClick = { onDecision(true) },
                modifier = Modifier.weight(1f).height(44.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ForgeAmber,
                    contentColor = ForgeCanvas,
                ),
            ) {
                Text("Allow")
            }
        }
    }
}

// ───────────────────────────── Input ─────────────────────────────

@Composable
private fun AgentInputBar(
    state: AgentUiState,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val awaitingPermission = state.pendingPermission != null
    val canSend = state.input.isNotBlank() && !awaitingPermission && !state.running
    val shape = RoundedCornerShape(26.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ForgeCanvas)
            .padding(horizontal = 12.dp)
            .padding(top = 8.dp, bottom = 12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(ForgeSurfaceVariant)
                .border(1.dp, ForgeBorder, shape)
                .padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 38.dp)
                    .padding(end = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = state.input,
                    onValueChange = onInputChange,
                    enabled = !awaitingPermission,
                    maxLines = 6,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = ForgeInk,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                    ),
                    cursorBrush = SolidColor(ForgeMint),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { innerTextField ->
                        Box {
                            if (state.input.isEmpty()) {
                                Text(
                                    text = if (awaitingPermission) {
                                        "Answer the permission request above…"
                                    } else {
                                        "Describe a task for the agent…"
                                    },
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontSize = 15.sp,
                                        lineHeight = 22.sp,
                                    ),
                                    color = ForgeMuted,
                                )
                            }
                            innerTextField()
                        }
                    },
                )
            }

            SendStopButton(
                running = state.running,
                canSend = canSend,
                onSend = onSend,
                onStop = onStop,
            )
        }
    }
}

/** Round icon button inside the input field: arrow-up to send, square to stop. */
@Composable
private fun SendStopButton(
    running: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val active = running || canSend
    val container by animateColorAsState(
        targetValue = if (active) ForgeMint else ForgeBorder,
        label = "sendContainer",
    )
    val content = if (active) ForgeCanvas else ForgeMuted

    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(container)
            .clickable(enabled = active, onClick = if (running) onStop else onSend),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (running) Icons.Filled.Stop else Icons.Filled.ArrowUpward,
            contentDescription = if (running) "Stop" else "Send",
            tint = content,
            modifier = Modifier.size(20.dp),
        )
    }
}

private fun activityColor(status: AgentActivityStatus): Color = when (status) {
    AgentActivityStatus.IDLE -> ForgeMuted
    AgentActivityStatus.SENDING -> ForgePeriwinkle
    AgentActivityStatus.THINKING -> ForgePeriwinkle
    AgentActivityStatus.USING_TOOL -> ForgeMint
    AgentActivityStatus.TOOL_SUCCESS -> ForgeMint
    AgentActivityStatus.TOOL_FAILURE -> ForgeDanger
    AgentActivityStatus.PERMISSION_REQUIRED -> ForgeAmber
    AgentActivityStatus.WAITING -> ForgeAmber
    AgentActivityStatus.COMPLETED -> ForgeMint
    AgentActivityStatus.CONNECTION_ERROR,
    AgentActivityStatus.TIMEOUT,
    AgentActivityStatus.INVALID_RESPONSE,
    AgentActivityStatus.ERROR,
    -> ForgeDanger
}
