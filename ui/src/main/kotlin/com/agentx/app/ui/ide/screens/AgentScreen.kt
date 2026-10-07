package com.agentx.app.ui.ide.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentx.app.ui.ide.components.IdeDivider
import com.agentx.app.ui.ide.model.AgentActivity
import com.agentx.app.ui.ide.model.AgentActivityStatus
import com.agentx.app.context.AgentAttachmentKind
import com.agentx.app.ui.ide.model.AgentChatUiState
import com.agentx.app.ui.ide.model.AttachmentUiModel
import com.agentx.app.ui.ide.model.SkillChoiceUiModel
import com.agentx.app.ui.ide.model.ChatMessageKind
import com.agentx.app.ui.ide.model.GenerationPhase
import com.agentx.app.ui.ide.model.GenerationState
import com.agentx.app.ui.ide.model.PermissionPromptUi
import com.agentx.app.ui.ide.screens.agent.AgentMessageItem
import com.agentx.app.ui.ide.screens.agent.AgentTodoCard
import com.agentx.app.ui.ide.screens.agent.AgentSessionDrawerContent
import com.agentx.app.ui.ide.screens.agent.copyToClipboard
import com.agentx.app.ui.ide.state.AgentChatPresentation
import com.agentx.app.ui.ide.state.AgentViewModel
import com.agentx.app.ui.ide.state.AgentTodos
import com.agentx.app.ui.theme.ForgeAmber
import com.agentx.app.ui.theme.ForgeBorder
import com.agentx.app.ui.theme.ForgeCanvas
import com.agentx.app.ui.theme.ForgeDanger
import com.agentx.app.ui.theme.ForgeInk
import com.agentx.app.ui.theme.ForgeMint
import com.agentx.app.ui.theme.ForgeMuted
import com.agentx.app.ui.theme.ForgeOnAccent
import com.agentx.app.ui.theme.ForgeSurface
import com.agentx.app.ui.theme.ForgeSurfaceVariant
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The Agent page: header, conversation, permission card and composer.
 *
 * Keyboard: the composer is lifted above the IME by exactly the part of the
 * keyboard that overlaps this screen, so it never hides behind the keyboard
 * and never double-pads when the shell already reserves space for a bottom bar.
 *
 * Scrolling: the list follows the newest content while the agent streams. A
 * finger drag hands control to the user; reaching the bottom again (or tapping
 * "Jump to latest") resumes following.
 */
@Composable
fun AgentScreen(
    viewModel: AgentViewModel,
    modifier: Modifier = Modifier,
    workspaceName: String? = null,
    onBack: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
) {
    val state = viewModel.uiState
    val context = LocalContext.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(state.sessions) { nowMillis = System.currentTimeMillis() }

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
        val density = LocalDensity.current
        val rootView = LocalView.current

        // Keyboard handling. `bottomGapPx` is how far this screen's own bottom edge
        // is from the window bottom (e.g. the shell's bottom tab bar). Only the part
        // of the keyboard that actually overlaps the screen becomes padding.
        val imeBottomPx = WindowInsets.ime.getBottom(density)
        var bottomGapPx by remember { mutableStateOf(0) }
        val imePadding = with(density) { (imeBottomPx - bottomGapPx).coerceAtLeast(0).toDp() }

        val listState = rememberLazyListState()
        var stickToBottom by remember { mutableStateOf(true) }
        var userDriven by remember { mutableStateOf(false) }

        val renderedMessages = state.messages.filterNot { it.kind == ChatMessageKind.TOOL }
        // One extra tail item sits below the last message; scrolling to it always
        // lands on the true bottom, even when the last reply is taller than the screen.
        val tailIndex = renderedMessages.size

        val atBottom by remember {
            derivedStateOf {
                val info = listState.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull()
                info.totalItemsCount == 0 || (last != null && last.index >= info.totalItemsCount - 1)
            }
        }

        // A finger drag takes control away from auto-follow immediately.
        LaunchedEffect(listState) {
            listState.interactionSource.interactions.collect { interaction ->
                if (interaction is DragInteraction.Start) {
                    userDriven = true
                    stickToBottom = false
                }
            }
        }
        // When the user's own scroll/fling ends, follow again only if they are at the bottom.
        LaunchedEffect(listState) {
            snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
                if (!scrolling && userDriven) {
                    userDriven = false
                    stickToBottom = atBottom
                }
            }
        }
        // A new turn or another session always follows the conversation again.
        LaunchedEffect(state.generation.startedAtMillis, state.activeSessionId) {
            stickToBottom = true
        }

        val lastMessage = renderedMessages.lastOrNull()
        val contentSignature = listOf(
            renderedMessages.size,
            lastMessage?.rawText?.length ?: 0,
            lastMessage?.activities?.size ?: 0,
            lastMessage?.activities?.lastOrNull()?.outputLines?.size ?: 0,
            lastMessage?.planSteps?.size ?: 0,
            lastMessage?.state,
            state.pendingPermission != null,
            imeBottomPx,
        )
        LaunchedEffect(contentSignature, state.activeSessionId) {
            if (stickToBottom && renderedMessages.isNotEmpty()) {
                listState.scrollToItem(tailIndex)
            }
        }

        val lastAssistantId = renderedMessages.lastOrNull { it.kind == ChatMessageKind.ASSISTANT }?.id

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ForgeCanvas)
                .onGloballyPositioned { coordinates ->
                    val bottom = coordinates.positionInWindow().y + coordinates.size.height
                    bottomGapPx = (rootView.rootView.height - bottom).roundToInt().coerceAtLeast(0)
                },
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(bottom = imePadding)) {
                AgentHeader(
                    workspaceName = workspaceName,
                    modelId = state.modelId,
                    activity = state.activity,
                    generation = state.generation,
                    currentAgent = state.currentAgent,
                    onBack = onBack,
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onNewSession = { viewModel.newSession() },
                    onOpenSettings = onOpenSettings,
                )

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (renderedMessages.isEmpty()) {
                        AgentEmptyState(onSuggestion = viewModel::onInputChange)
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            state = listState,
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            items(renderedMessages, key = { it.id }) { message ->
                                AgentMessageItem(
                                    message = message,
                                    onCopy = { copyToClipboard(context, "Agent message", message.copyText) },
                                    onRegenerate = { viewModel.regenerate(message.id) },
                                    onRetry = { viewModel.retry(message.id) },
                                    onEditSend = { text -> viewModel.editAndResend(message.id, text) },
                                    awaitingPermission = state.pendingPermission != null && message.id == lastAssistantId,
                                )
                            }
                            item(key = "tail-anchor") { Spacer(Modifier.height(24.dp)) }
                        }
                    }

                    if (!stickToBottom && !atBottom && renderedMessages.isNotEmpty()) {
                        JumpToLatestPill(
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                            onClick = {
                                stickToBottom = true
                                scope.launch { listState.animateScrollToItem(tailIndex) }
                            },
                        )
                    }
                }

                AgentTodoCard(snapshot = AgentTodos.current(state.messages))
                state.pendingPermission?.let { prompt ->
                    PermissionPromptCard(prompt = prompt, onDecision = viewModel::respondToPermission)
                }

                AgentInputBar(
                    state = state,
                    onInputChange = viewModel::onInputChange,
                    onSend = viewModel::send,
                    onStop = viewModel::stop,
                    onPickAttachment = viewModel::pickAttachment,
                    onRemoveAttachment = viewModel::removeAttachment,
                    onToggleSkill = viewModel::toggleSkill,
                    onRefreshSkills = viewModel::refreshSkills,
                )
            }
        }
    }
}

// ───────────────────────────── Header ─────────────────────────────

@Composable
private fun AgentHeader(
    workspaceName: String?,
    modelId: String?,
    activity: AgentActivity,
    generation: GenerationState,
    currentAgent: String,
    onBack: (() -> Unit)?,
    onOpenDrawer: () -> Unit,
    onNewSession: () -> Unit,
    onOpenSettings: (() -> Unit)?,
) {
    val statusLabel = agentStatusLabel(generation, activity)
    val statusColor = agentStatusColor(generation)
    val agentName = remember(currentAgent) { displayAgentName(currentAgent) }
    // The model sits on the second line, shortened and ellipsised, so a long id
    // can never push the action buttons off screen.
    val subtitle = listOfNotNull(
        workspaceName?.takeIf { it.isNotBlank() },
        modelId?.takeIf { it.isNotBlank() }?.substringAfterLast('/'),
    ).joinToString(" · ")

    Column(modifier = Modifier.fillMaxWidth().background(ForgeSurface)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 2.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to workspace",
                        tint = ForgeInk,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = agentName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = ForgeInk,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(modifier = Modifier.size(7.dp).background(statusColor, CircleShape))
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = ForgeMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onNewSession, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.Add, contentDescription = "New session", tint = ForgeInk, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onOpenDrawer, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.Menu, contentDescription = "Open sessions", tint = ForgeInk, modifier = Modifier.size(20.dp))
            }
            if (onOpenSettings != null) {
                IconButton(onClick = onOpenSettings, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = ForgeInk, modifier = Modifier.size(20.dp))
                }
            }
        }
        IdeDivider()
    }
}

private fun displayAgentName(currentAgent: String): String =
    if (currentAgent.isBlank() || currentAgent.equals("Main", ignoreCase = true)) "AgentX" else currentAgent

private fun agentStatusLabel(generation: GenerationState, activity: AgentActivity): String {
    val base = when {
        generation.phase == GenerationPhase.WAITING_PERMISSION -> "Waiting"
        generation.running && generation.phase == GenerationPhase.TOOL -> "Working"
        generation.running -> "Thinking"
        generation.phase == GenerationPhase.COMPLETED -> "Done"
        generation.phase == GenerationPhase.STOPPED -> "Stopped"
        generation.phase == GenerationPhase.FAILED -> "Error"
        activity.status == AgentActivityStatus.IDLE -> "Idle"
        else -> activity.status.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
    val elapsed = if (generation.running) {
        AgentChatPresentation.formatElapsedSeconds(generation.elapsedMillis)
    } else {
        ""
    }
    return if (elapsed.isEmpty()) base else "$base · $elapsed"
}

@Composable
private fun agentStatusColor(generation: GenerationState): Color = when {
    generation.phase == GenerationPhase.WAITING_PERMISSION -> ForgeAmber
    generation.running -> ForgeMint
    generation.phase == GenerationPhase.COMPLETED -> ForgeMint
    generation.phase == GenerationPhase.STOPPED -> ForgeAmber
    generation.phase == GenerationPhase.FAILED -> ForgeDanger
    else -> ForgeMuted
}

// ───────────────────────────── Empty state ─────────────────────────────

@Composable
private fun AgentEmptyState(onSuggestion: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = "AgentX",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = ForgeInk,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "What are we building?",
            style = MaterialTheme.typography.titleMedium,
            color = ForgeMuted,
        )
        Spacer(Modifier.height(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SuggestionChip("Explore repo", onSuggestion, Modifier.weight(1f))
                SuggestionChip("Fix a problem", onSuggestion, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SuggestionChip("Build a feature", onSuggestion, Modifier.weight(1f))
                SuggestionChip("Run tests", onSuggestion, Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = "…or describe what you want AgentX to do.",
            style = MaterialTheme.typography.bodySmall,
            color = ForgeMuted,
        )
    }
}

@Composable
private fun SuggestionChip(label: String, onSuggestion: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = { onSuggestion(label) },
        modifier = modifier.height(42.dp),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, ForgeBorder),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = ForgeInk),
        contentPadding = PaddingValues(horizontal = 10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
    val destructive = isPotentiallyDestructive(prompt)
    val command = if (prompt.toolName.lowercase() in TERMINAL_TOOLS) {
        AgentChatPresentation.terminalCommand(prompt.detail)
    } else {
        null
    }
    val shape = RoundedCornerShape(14.dp)
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
                text = if (destructive) "This command may modify files" else "Permission required",
                style = MaterialTheme.typography.titleMedium,
                color = ForgeAmber,
            )
        }
        Spacer(Modifier.height(10.dp))
        val commandText = if (command != null) "$ $command" else prompt.toolName
        Text(
            text = commandText,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = ForgeInk,
            modifier = Modifier
                .background(ForgeCanvas.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
        if (command == null && prompt.detail.isNotBlank()) {
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
                Text(if (destructive) "Cancel" else "Deny")
            }
            Button(
                onClick = { onDecision(true) },
                modifier = Modifier.weight(1f).height(44.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ForgeAmber, contentColor = ForgeOnAccent),
            ) {
                Text("Allow")
            }
        }
    }
}

private val TERMINAL_TOOLS = setOf("run_command", "execute", "execute_command", "shell", "terminal", "sh", "bash")

private fun isPotentiallyDestructive(prompt: PermissionPromptUi): Boolean {
    val tool = prompt.toolName.lowercase()
    val writeTool = tool in setOf(
        "write_file", "create_file", "apply_patch", "edit_file",
        "delete_file", "remove_file", "move_file", "rename_file",
    )
    val permissionHint = prompt.requiredPermission.lowercase().let {
        it.contains("write") || it.contains("modify") || it.contains("delete")
    }
    val reasonHint = prompt.reason.lowercase().let {
        it.contains("modify") || it.contains("write") || it.contains("delete") || it.contains("remove")
    }
    return writeTool || permissionHint || reasonHint
}

// ───────────────────────────── Input ─────────────────────────────

@Composable
private fun AgentInputBar(
    state: AgentChatUiState,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onPickAttachment: (AgentAttachmentKind) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onToggleSkill: (String) -> Unit,
    onRefreshSkills: () -> Unit,
) {
    val awaitingPermission = state.pendingPermission != null
    // Send is available for text, for attachments, or both — but not for a skill selection alone,
    // which says how to work rather than what to do.
    val canSend = state.canSend
    val shape = RoundedCornerShape(20.dp)
    var actionsOpen by remember { mutableStateOf(false) }
    var skillsOpen by remember { mutableStateOf(false) }
    val selectedSkills = state.skills.filter { it.selected }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ForgeCanvas)
            .padding(horizontal = 12.dp)
            .padding(top = 8.dp, bottom = 10.dp),
    ) {
        if (state.attachments.isNotEmpty() || selectedSkills.isNotEmpty()) {
            ComposerChipsRow(
                attachments = state.attachments,
                skills = selectedSkills,
                onRemoveAttachment = onRemoveAttachment,
                onToggleSkill = onToggleSkill,
            )
            Spacer(Modifier.height(7.dp))
        }

        // A failed pick explains itself and clears on the next action; it never blocks the composer.
        state.attachmentMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.labelSmall,
                color = ForgeDanger,
                modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = 7.dp),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(ForgeSurface)
                .border(1.dp, if (state.running) ForgeMint.copy(alpha = 0.45f) else ForgeBorder, shape)
                .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box {
                IconButton(
                    onClick = { actionsOpen = true },
                    enabled = !awaitingPermission && !state.running,
                    modifier = Modifier.size(38.dp),
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = "Add an attachment or a skill",
                        tint = if (awaitingPermission || state.running) ForgeMuted else ForgeInk,
                        modifier = Modifier.size(20.dp),
                    )
                }
                DropdownMenu(expanded = actionsOpen, onDismissRequest = { actionsOpen = false }) {
                    AddAction("Image", Icons.Filled.Image) {
                        actionsOpen = false
                        onPickAttachment(AgentAttachmentKind.IMAGE)
                    }
                    AddAction("File", Icons.Filled.AttachFile) {
                        actionsOpen = false
                        onPickAttachment(AgentAttachmentKind.FILE)
                    }
                    AddAction("Document", Icons.Filled.Description) {
                        actionsOpen = false
                        onPickAttachment(AgentAttachmentKind.DOCUMENT)
                    }
                    HorizontalDivider()
                    AddAction("Skill", Icons.Filled.AutoAwesome) {
                        actionsOpen = false
                        // The list is the agent's own resolution for this chat, so nothing that
                        // could not actually be used is ever offered.
                        onRefreshSkills()
                        skillsOpen = true
                    }
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .padding(end = 8.dp, top = 2.dp, bottom = 2.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = state.input,
                    onValueChange = onInputChange,
                    enabled = !awaitingPermission,
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
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
                                    text = when {
                                        awaitingPermission -> "Answer the permission request above…"
                                        state.running -> "Working… type your next message"
                                        else -> "Ask AgentX what to do…"
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

            SendStopButton(
                running = state.running,
                canSend = canSend,
                onSend = onSend,
                onStop = onStop,
            )
        }
    }

    if (skillsOpen) {
        SkillPickerDialog(
            skills = state.skills,
            onToggle = onToggleSkill,
            onDismiss = { skillsOpen = false },
        )
    }
}

/** One row of the `+` menu. */
@Composable
private fun AddAction(label: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = ForgeInk)
        },
        leadingIcon = {
            Icon(icon, contentDescription = null, tint = ForgeMuted, modifier = Modifier.size(17.dp))
        },
        onClick = onClick,
    )
}

/** The attachments and skills chosen for the message being composed. */
@Composable
private fun ComposerChipsRow(
    attachments: List<AttachmentUiModel>,
    skills: List<SkillChoiceUiModel>,
    onRemoveAttachment: (String) -> Unit,
    onToggleSkill: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        attachments.forEach { attachment ->
            ComposerChip(
                icon = iconFor(attachment.kind),
                label = attachment.displayName,
                detail = attachment.sizeLabel,
                removeDescription = "Remove ${attachment.displayName}",
                onRemove = { onRemoveAttachment(attachment.id) },
            )
        }
        skills.forEach { skill ->
            ComposerChip(
                icon = Icons.Filled.AutoAwesome,
                label = skill.name,
                detail = null,
                removeDescription = "Remove ${skill.name}",
                onRemove = { onToggleSkill(skill.id) },
            )
        }
    }
}

@Composable
private fun ComposerChip(
    icon: ImageVector,
    label: String,
    detail: String?,
    removeDescription: String,
    onRemove: () -> Unit,
) {
    val chipShape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .clip(chipShape)
            .background(ForgeSurfaceVariant)
            .border(1.dp, ForgeBorder, chipShape)
            .padding(start = 9.dp, end = 2.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = ForgeMuted, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = ForgeInk,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 140.dp),
        )
        if (detail != null) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = ForgeMuted,
                maxLines = 1,
            )
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(22.dp)) {
            Icon(
                Icons.Filled.Close,
                contentDescription = removeDescription,
                tint = ForgeMuted,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

/**
 * The skills this chat can use on the next message.
 *
 * Deliberately the agent's own resolution rather than the whole registry: a skill that is disabled,
 * invalid or not assigned to the role cannot take effect, so offering it would be a control that
 * does nothing.
 */
@Composable
private fun SkillPickerDialog(
    skills: List<SkillChoiceUiModel>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ForgeSurface,
        title = {
            Text(
                text = "Skills for this message",
                style = MaterialTheme.typography.titleSmall,
                color = ForgeInk,
            )
        },
        text = {
            if (skills.isEmpty()) {
                Text(
                    text = "No skills are available for this project yet. Add one in Settings → Skills.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ForgeMuted,
                )
            } else {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    skills.forEach { skill ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggle(skill.id) }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = skill.selected, onCheckedChange = { onToggle(skill.id) })
                            Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                                Text(
                                    text = skill.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = ForgeInk,
                                )
                                if (skill.description.isNotBlank()) {
                                    Text(
                                        text = skill.description,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ForgeMuted,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done", color = ForgeMint) }
        },
    )
}

private fun iconFor(kind: AgentAttachmentKind): ImageVector = when (kind) {
    AgentAttachmentKind.IMAGE -> Icons.Filled.Image
    AgentAttachmentKind.DOCUMENT -> Icons.Filled.Description
    AgentAttachmentKind.FILE -> Icons.Filled.AttachFile
}

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
    val content = if (active) ForgeOnAccent else ForgeMuted

    Box(
        modifier = Modifier
            .size(40.dp)
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
