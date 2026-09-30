package com.agentx.app.ui.ide.screens

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentx.app.ui.ide.components.IdeDivider
import com.agentx.app.ui.ide.components.IdeStatusPill
import com.agentx.app.ui.ide.model.AgentActivity
import com.agentx.app.ui.ide.model.AgentActivityStatus
import com.agentx.app.ui.ide.model.AgentChatUiState
import com.agentx.app.ui.ide.model.GenerationPhase
import com.agentx.app.ui.ide.model.GenerationState
import com.agentx.app.ui.ide.model.PermissionPromptUi
import com.agentx.app.ui.ide.screens.agent.AgentMessageItem
import com.agentx.app.ui.ide.screens.agent.AgentSessionDrawerContent
import com.agentx.app.ui.ide.screens.agent.copyToClipboard
import com.agentx.app.ui.ide.state.AgentChatPresentation
import com.agentx.app.ui.ide.state.AgentViewModel
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@Composable
fun AgentScreen(
    viewModel: AgentViewModel,
    modifier: Modifier = Modifier,
) {
    val state = viewModel.uiState
    val context = LocalContext.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }

    // The sidebar's relative timestamps are refreshed once per composition of a
    // new session list, never on every streaming frame.
    LaunchedEffect(state.sessions) { nowMillis = System.currentTimeMillis() }

    val activeTitle = state.sessions.firstOrNull { it.id == state.activeSessionId }?.title ?: "Agent"

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = ForgeSurface,
                drawerContentColor = ForgeInk,
            ) {
                AgentSessionDrawerContent(
                    sessions = state.sessions,
                    activeSessionId = state.activeSessionId,
                    nowMillis = nowMillis,
                    onNewSession = {
                        viewModel.newSession()
                        scope.launch { drawerState.close() }
                    },
                    onOpenSession = { id ->
                        viewModel.openSession(id)
                        scope.launch { drawerState.close() }
                    },
                    onRenameSession = viewModel::renameSession,
                    onDeleteSession = viewModel::deleteSession,
                )
            }
        },
        modifier = modifier.fillMaxSize(),
    ) {
        val listState = rememberLazyListState()
        var stickToBottom by remember { mutableStateOf(true) }

        val atBottom by remember {
            derivedStateOf {
                val info = listState.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull()
                last == null || last.index >= info.totalItemsCount - 1
            }
        }

        // Only a user scroll gesture changes the follow decision; programmatic
        // scrolling ends with the list at the bottom and keeps following.
        LaunchedEffect(listState) {
            snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
                if (!scrolling) stickToBottom = atBottom
            }
        }

        val contentSignature = state.messages.size to (
            state.messages.lastOrNull()?.let { it.rawText.length + it.blocks.size * 31 + it.activities.size * 7 } ?: 0
            )
        LaunchedEffect(contentSignature, state.activeSessionId) {
            if (stickToBottom && state.messages.isNotEmpty()) {
                listState.animateScrollToItem(state.messages.lastIndex)
            }
        }
        LaunchedEffect(state.activeSessionId) { stickToBottom = true }

        Column(modifier = Modifier.fillMaxSize().background(ForgeCanvas)) {
            AgentHeader(
                title = activeTitle,
                activity = state.activity,
                generation = state.generation,
                currentAgent = state.currentAgent,
                onOpenDrawer = { scope.launch { drawerState.open() } },
                onNewSession = { viewModel.newSession() },
            )

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (state.messages.isEmpty()) {
                    AgentEmptyState()
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(state.messages, key = { it.id }) { message ->
                            AgentMessageItem(
                                message = message,
                                onCopy = { copyToClipboard(context, "Agent message", message.copyText) },
                                onRegenerate = { viewModel.regenerate(message.id) },
                                onRetry = { viewModel.retry(message.id) },
                                onEditSend = { text -> viewModel.editAndResend(message.id, text) },
                            )
                        }
                    }
                }

                if (!stickToBottom && state.messages.isNotEmpty()) {
                    JumpToLatestPill(
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                        onClick = {
                            stickToBottom = true
                            scope.launch { listState.animateScrollToItem(state.messages.lastIndex) }
                        },
                    )
                }
            }

            state.pendingPermission?.let { prompt ->
                PermissionPromptCard(prompt = prompt, onDecision = viewModel::respondToPermission)
            }

            AgentInputBar(
                state = state,
                onInputChange = viewModel::onInputChange,
                onSend = viewModel::send,
                onStop = viewModel::stop,
            )
        }
    }
}

// ───────────────────────────── Header ─────────────────────────────

@Composable
private fun AgentHeader(
    title: String,
    activity: AgentActivity,
    generation: GenerationState,
    currentAgent: String,
    onOpenDrawer: () -> Unit,
    onNewSession: () -> Unit,
) {
    val color = activityColor(activity.status)
    Column(modifier = Modifier.fillMaxWidth().background(ForgeSurface)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onOpenDrawer, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Filled.Menu, contentDescription = "Open sessions", tint = ForgeInk)
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = ForgeInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "$currentAgent · ${activity.label}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ForgeMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            GenerationChip(generation = generation, color = color)
            IconButton(onClick = onNewSession, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Filled.Add, contentDescription = "New session", tint = ForgeInk)
            }
        }
        IdeDivider()
    }
}

/** `Generating… 12s` while running, the final `12.4s` once the turn ends. */
@Composable
private fun GenerationChip(generation: GenerationState, color: Color) {
    when {
        generation.running -> Row(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(color.copy(alpha = 0.14f))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp, color = color)
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Generating… ${AgentChatPresentation.formatElapsedSeconds(generation.elapsedMillis)}",
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        }

        generation.phase != GenerationPhase.IDLE && generation.elapsedMillis > 0L -> Text(
            text = AgentChatPresentation.formatDuration(generation.elapsedMillis),
            style = MaterialTheme.typography.labelSmall,
            color = ForgeMuted,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
    }
}

@Composable
private fun AgentEmptyState() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Start an agent task",
            style = MaterialTheme.typography.titleMedium,
            color = ForgeInk,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Describe a task and the agent will inspect, plan, edit and verify. " +
                "Its tool calls and reasoning summary appear here as it works.",
            style = MaterialTheme.typography.bodySmall,
            color = ForgeMuted,
        )
    }
}

@Composable
private fun JumpToLatestPill(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(ForgeSurfaceVariant)
            .border(1.dp, ForgeBorder, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.ArrowDownward, contentDescription = null, tint = ForgeMint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(text = "Jump to latest", style = MaterialTheme.typography.labelSmall, color = ForgeInk)
    }
}

// ───────────────────────────── Permission ─────────────────────────────

@Composable
private fun PermissionPromptCard(
    prompt: PermissionPromptUi,
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
            Text(text = prompt.detail, style = MaterialTheme.typography.bodySmall, color = ForgeMuted)
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
            Text(text = prompt.reason, style = MaterialTheme.typography.labelSmall, color = ForgeMuted)
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
                colors = ButtonDefaults.buttonColors(containerColor = ForgeAmber, contentColor = ForgeCanvas),
            ) {
                Text("Allow")
            }
        }
    }
}

// ───────────────────────────── Input ─────────────────────────────

@Composable
private fun AgentInputBar(
    state: AgentChatUiState,
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
                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp),
                                    color = ForgeMuted,
                                )
                            }
                            innerTextField()
                        }
                    },
                )
            }

            if (state.running) {
                Text(
                    text = AgentChatPresentation.formatElapsedSeconds(state.generation.elapsedMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = ForgeMuted,
                    modifier = Modifier.padding(end = 8.dp, bottom = 10.dp),
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
            contentDescription = if (running) "Stop generating" else "Send message",
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
