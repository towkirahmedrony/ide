package com.agentx.app.ui.ide.screens.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentx.app.ui.ide.model.ActivityItemStatus
import com.agentx.app.ui.ide.model.AgentActivityKind
import com.agentx.app.ui.ide.model.AgentActivityUiModel
import com.agentx.app.ui.ide.model.AgentTurnOutcome
import com.agentx.app.ui.ide.model.ChatMessageKind
import com.agentx.app.ui.ide.model.ChatMessageUiModel
import com.agentx.app.ui.ide.model.InlineSpan
import com.agentx.app.ui.ide.model.MessageBlock
import com.agentx.app.ui.ide.model.MessageState
import com.agentx.app.ui.ide.model.PlanStepStatus
import com.agentx.app.ui.ide.model.PlanStepUiModel
import com.agentx.app.ui.ide.model.ToolActivityUiModel
import com.agentx.app.ui.ide.model.ToolRunStatus
import com.agentx.app.ui.ide.model.UiError
import com.agentx.app.ui.ide.state.AgentChatPresentation
import com.agentx.app.ui.ide.state.CodeToken
import com.agentx.app.ui.ide.state.CodeTokenKind
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
import kotlinx.coroutines.delay

private const val COLLAPSE_LINE_THRESHOLD = 18

/** Renders one chat entry. All actions are supplied by the screen. */
@Composable
fun AgentMessageItem(
    message: ChatMessageUiModel,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    onRetry: () -> Unit,
    onEditSend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (message.kind) {
        ChatMessageKind.SYSTEM -> AgentSystemNote(message, modifier)
        ChatMessageKind.USER -> AgentUserMessage(message, onCopy, onEditSend, modifier)
        ChatMessageKind.TOOL -> AgentToolCardMessage(message, modifier)
        ChatMessageKind.ERROR -> AgentErrorCard(message, onCopy, onRetry, modifier)
        ChatMessageKind.ASSISTANT -> AgentAssistantMessage(message, onCopy, onRegenerate, modifier)
    }
}

// ───────────────────────────── User ─────────────────────────────

@Composable
private fun AgentUserMessage(
    message: ChatMessageUiModel,
    onCopy: () -> Unit,
    onEditSend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember(message.id) { mutableStateOf(message.rawText) }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
    ) {
        if (editing) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(ForgeSurface)
                    .border(1.dp, ForgeBorder, RoundedCornerShape(16.dp))
                    .padding(12.dp),
            ) {
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = ForgeInk, fontSize = 15.sp),
                    cursorBrush = SolidColor(ForgeMint),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { editing = false; draft = message.rawText }) { Text("Cancel") }
                    TextButton(
                        onClick = {
                            editing = false
                            onEditSend(draft)
                        },
                    ) { Text("Resend") }
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .widthIn(max = 340.dp)
                    .background(
                        ForgePeriwinkle.copy(alpha = 0.18f),
                        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp),
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                SelectionContainer { AgentMarkdownText(message.blocks, message.rawText) }
            }
        }

        if (!editing) {
            Row(
                modifier = Modifier.padding(top = 2.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MessageTimestamp(message.timestampMillis)
                CopyAction(onCopy = onCopy, label = "Copy message")
                IconButton(
                    onClick = { editing = true; draft = message.rawText },
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = "Edit and resend",
                        tint = ForgeMuted,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }
    }
}

// ───────────────────────────── Assistant ─────────────────────────────

@Composable
private fun AgentAssistantMessage(
    message: ChatMessageUiModel,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AgentAvatar()
        Column(modifier = Modifier.weight(1f).padding(top = 3.dp)) {
            if (message.activities.isNotEmpty()) {
                AgentActivityPanel(
                    activities = message.activities,
                    outcome = when {
                        message.state == MessageState.STREAMING -> AgentTurnOutcome.RUNNING
                        message.state == MessageState.FAILED -> AgentTurnOutcome.FAILED
                        message.state == MessageState.STOPPED -> AgentTurnOutcome.STOPPED
                        else -> AgentTurnOutcome.SUCCESS
                    },
                    elapsedMillis = message.elapsedMillis ?: 0L,
                    planSteps = message.planSteps,
                )
                Spacer(Modifier.height(10.dp))
            }

            if (message.rawText.isEmpty() && message.state == MessageState.STREAMING) {
                TypingDots()
            } else {
                SelectionContainer { AgentMarkdownText(message.blocks, message.rawText) }
            }

            if (message.rawText.isNotEmpty() || message.state != MessageState.STREAMING) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (message.state == MessageState.STOPPED) {
                        StateChip("Stopped", ForgeAmber)
                        Spacer(Modifier.width(6.dp))
                    }
                    MessageTimestamp(message.timestampMillis)
                    MessageMeta(message)
                    CopyAction(onCopy = onCopy, label = "Copy response")
                    IconButton(onClick = onRegenerate, modifier = Modifier.size(34.dp)) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "Regenerate response",
                            tint = ForgeMuted,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageMeta(message: ChatMessageUiModel) {
    message.elapsedMillis?.takeIf { it > 0L }?.let { elapsed ->
        MetaText(AgentChatPresentation.formatDuration(elapsed))
    }
    message.modelId?.takeIf { it.isNotBlank() }?.let { model ->
        MetaText(model)
    }
}

// ───────────────────────────── System / tool / error ─────────────────────────────

@Composable
private fun AgentSystemNote(message: ChatMessageUiModel, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Text(
            text = message.rawText.ifBlank { "—" },
            style = MaterialTheme.typography.labelSmall,
            color = ForgeMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .background(ForgeSurfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun AgentToolCardMessage(message: ChatMessageUiModel, modifier: Modifier = Modifier) {
    val tool = message.tool ?: return
    Row(modifier = modifier.fillMaxWidth().padding(start = 38.dp)) {
        AgentToolCard(tool)
    }
}

@Composable
fun AgentToolCard(tool: ToolActivityUiModel, modifier: Modifier = Modifier) {
    val tint = when (tool.status) {
        ToolRunStatus.RUNNING -> ForgeMint
        ToolRunStatus.COMPLETED -> ForgeMint
        ToolRunStatus.FAILED -> ForgeDanger
        ToolRunStatus.DENIED -> ForgeAmber
        ToolRunStatus.CANCELLED -> ForgeAmber
    }
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ForgeSurface)
            .border(1.dp, ForgeBorder, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(modifier = Modifier.padding(top = 1.dp).size(16.dp), contentAlignment = Alignment.Center) {
            when (tool.status) {
                ToolRunStatus.RUNNING -> CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = tint,
                )
                ToolRunStatus.FAILED, ToolRunStatus.DENIED, ToolRunStatus.CANCELLED -> Icon(
                    Icons.Filled.Close,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(16.dp),
                )
                ToolRunStatus.COMPLETED -> Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = tool.displayName,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    color = ForgeInk,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = toolStatusLabel(tool),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = tint,
                )
            }
            tool.detail?.let { detail ->
                Spacer(Modifier.height(3.dp))
                Text(
                    text = detail,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = ForgeMuted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            tool.summary?.let { summary ->
                Spacer(Modifier.height(3.dp))
                Text(
                    text = summary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = if (tool.status == ToolRunStatus.FAILED) ForgeDanger else ForgeMuted,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun toolStatusLabel(tool: ToolActivityUiModel): String {
    val label = when (tool.status) {
        ToolRunStatus.RUNNING -> "Running"
        ToolRunStatus.COMPLETED -> "Completed"
        ToolRunStatus.FAILED -> "Failed"
        ToolRunStatus.DENIED -> "Denied"
        ToolRunStatus.CANCELLED -> "Cancelled"
    }
    val elapsed = tool.elapsedMillis?.takeIf { it > 0L } ?: return label
    return "$label · ${AgentChatPresentation.formatDuration(elapsed)}"
}

@Composable
private fun AgentErrorCard(
    message: ChatMessageUiModel,
    onCopy: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val error = message.error ?: UiError("Request failed", message.rawText)
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ForgeDanger.copy(alpha = 0.08f))
            .border(1.dp, ForgeDanger.copy(alpha = 0.35f), shape)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = ForgeDanger,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = error.title,
                style = MaterialTheme.typography.titleMedium,
                color = ForgeDanger,
            )
        }
        if (error.message.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(text = error.message, style = MaterialTheme.typography.bodySmall, color = ForgeInk)
        }
        // A failed turn keeps its activity history attached and expanded so the
        // failing step is inspectable; errors are never hidden.
        if (message.activities.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            AgentActivityPanel(
                activities = message.activities,
                outcome = AgentTurnOutcome.FAILED,
                elapsedMillis = message.elapsedMillis ?: 0L,
                planSteps = message.planSteps,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (error.retryable) {
                TextButton(onClick = onRetry) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Retry")
                }
            }
            CopyAction(onCopy = onCopy, label = "Copy error")
            MessageTimestamp(message.timestampMillis)
            error.code?.takeIf { it.isNotBlank() }?.let { MetaText(it) }
        }
    }
}

// ───────────────────────────── Agent activity ─────────────────────────────

/**
 * The collapsible Agent Activity block for one assistant turn.
 *
 * While the turn runs it is expanded by default and shows every real execution
 * step (thinking summaries, tools, terminal commands, sub-agents) under a live
 * `Working · 7s` headline. On success it collapses automatically to a
 * `✓ Completed · 12.4s · 6 steps` summary the user can re-open; failures stay
 * visible so the error is inspectable. The collapsed state is controlled from
 * the outside so the ViewModel, not recomposition, decides the default.
 */
@Composable
fun AgentActivityPanel(
    activities: List<AgentActivityUiModel>,
    outcome: AgentTurnOutcome,
    elapsedMillis: Long,
    /** The runtime's plan for this turn; empty when it produced none. */
    planSteps: List<PlanStepUiModel> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val running = outcome == AgentTurnOutcome.RUNNING
    // Auto behaviour only, no per-item timers: the headline time comes from the
    // turn's own elapsed clock and frozen per-item durations when finished.
    var expanded by remember { mutableStateOf(running) }
    var userToggled by remember { mutableStateOf(false) }

    // Auto-expand while working, auto-collapse on success — but never fight a
    // manual toggle the user made.
    LaunchedEffect(running, activities.size) {
        if (!userToggled) expanded = running || outcome == AgentTurnOutcome.FAILED
    }

    val headline = remember(activities, outcome, elapsedMillis) {
        AgentChatPresentation.activityHeadline(
            outcome = outcome,
            elapsedMillis = elapsedMillis,
            activeStepCount = activities.size,
            activeStepLabel = AgentChatPresentation.activeStepLabel(activities),
            failedLabel = activities.lastOrNull { it.status == ActivityItemStatus.FAILED }?.label,
        )
    }
    val headlineColor = when {
        running -> ForgeMint
        outcome == AgentTurnOutcome.FAILED -> ForgeDanger
        outcome == AgentTurnOutcome.STOPPED -> ForgeAmber
        else -> ForgeMint
    }
    Column(
        modifier = modifier
            .fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    userToggled = true
                    expanded = !expanded
                }
                .padding(vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                running -> CircularProgressIndicator(
                    modifier = Modifier.size(13.dp),
                    strokeWidth = 2.dp,
                    color = ForgeMint,
                )

                outcome == AgentTurnOutcome.FAILED -> Icon(
                    Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = ForgeDanger,
                    modifier = Modifier.size(15.dp),
                )

                outcome == AgentTurnOutcome.STOPPED -> Icon(
                    Icons.Filled.Stop,
                    contentDescription = null,
                    tint = ForgeAmber,
                    modifier = Modifier.size(14.dp),
                )

                else -> Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = ForgeMint,
                    modifier = Modifier.size(15.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = headline,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                color = headlineColor,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse agent activity" else "Expand agent activity",
                tint = ForgeMuted,
                modifier = Modifier.size(18.dp),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp)) {
                // The runtime's plan first, then the real execution steps that
                // carried it out. No plan means no plan block.
                if (planSteps.isNotEmpty()) {
                    PlanStepsBlock(planSteps)
                    if (activities.isNotEmpty()) Spacer(Modifier.height(6.dp))
                }
                activities.forEach { activity -> AgentActivityRow(activity) }
            }
        }
    }
}

/**
 * The Agent Runtime's plan for this turn.
 *
 * Every row is a step the runtime actually produced, shown with the runtime's own
 * status: ✓ done, ● active, ○ pending, ✕ failed. Nothing is added, reordered or
 * advanced here, so a plan can never claim progress the agent did not make.
 */
@Composable
private fun PlanStepsBlock(steps: List<PlanStepUiModel>) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Text(
            text = "Plan · ${AgentChatPresentation.planProgressLabel(steps)}",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = ForgeMuted,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(4.dp))
        steps.forEach { step ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.size(14.dp), contentAlignment = Alignment.Center) {
                    when (step.status) {
                        PlanStepStatus.ACTIVE -> CircularProgressIndicator(
                            modifier = Modifier.size(11.dp),
                            strokeWidth = 2.dp,
                            color = ForgeMint,
                        )
                        PlanStepStatus.DONE -> Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = ForgeMint,
                            modifier = Modifier.size(13.dp),
                        )
                        PlanStepStatus.FAILED -> Icon(
                            Icons.Filled.Close,
                            contentDescription = null,
                            tint = ForgeDanger,
                            modifier = Modifier.size(13.dp),
                        )
                        // A hollow dot for "not started yet" — drawn rather than
                        // taken from an icon set that may not ship this glyph.
                        PlanStepStatus.PENDING -> Box(
                            modifier = Modifier
                                .size(9.dp)
                                .border(1.5.dp, ForgeMuted, CircleShape),
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = step.title,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = when (step.status) {
                        PlanStepStatus.ACTIVE -> ForgeMint
                        PlanStepStatus.FAILED -> ForgeDanger
                        PlanStepStatus.DONE -> ForgeMuted
                        PlanStepStatus.PENDING -> ForgeMuted
                    },
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** One structured execution step. Terminal, tool and sub-agent rows expand. */
@Composable
private fun AgentActivityRow(activity: AgentActivityUiModel) {
    val failed = activity.status == ActivityItemStatus.FAILED
    val tint = when {
        failed -> ForgeDanger
        activity.status == ActivityItemStatus.ACTIVE -> ForgeMint
        activity.kind == AgentActivityKind.SUB_AGENT -> ForgePeriwinkle
        else -> ForgeMint
    }
    val expandable = activity.outputLines.isNotEmpty() || !activity.detail.isNullOrBlank()
    var showOutput by remember(activity.id) { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (expandable) Modifier.clickable { showOutput = !showOutput } else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                when (activity.status) {
                    ActivityItemStatus.ACTIVE -> CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 2.dp,
                        color = tint,
                    )
                    ActivityItemStatus.DONE -> Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(14.dp),
                    )
                    ActivityItemStatus.FAILED -> Icon(
                        Icons.Filled.Close,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(14.dp),
                    )
                    ActivityItemStatus.PENDING -> Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = ForgeMuted,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            val label = when {
                activity.kind == AgentActivityKind.SUB_AGENT && activity.role != null ->
                    "${activity.role} · ${activity.label}"                 activity.kind == AgentActivityKind.TERMINAL -> "$ ${activity.label}"
                activity.kind == AgentActivityKind.FILE_WRITE -> "✎ ${activity.label}"
                else -> activity.label
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = if (failed) ForgeDanger else ForgeInk,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            activity.elapsedMillis?.takeIf { it > 0L }?.let { elapsed ->
                Text(
                    text = AgentChatPresentation.formatDuration(elapsed),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = ForgeMuted,
                )
            }
            if (expandable) {
                Icon(
                    imageVector = if (showOutput) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (showOutput) "Hide output" else "Show output",
                    tint = ForgeMuted,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        if (showOutput && expandable) {
            // Bounded preview only; secrets were redacted by the presentation layer.
            val detail = activity.detail?.takeIf { it.isNotBlank() }
            val output = activity.outputLines.take(TERMINAL_PREVIEW_LINES)
            Text(
                text = buildString {
                    detail?.let { append(it) }
                    if (detail != null && output.isNotEmpty()) append("\n\n")
                    append(output.joinToString("\n"))
                },
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = if (failed) ForgeDanger else ForgeMuted,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, top = 4.dp)
                    .background(ForgeCanvas.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

private const val TERMINAL_PREVIEW_LINES = 8

// ───────────────────────────── Markdown ─────────────────────────────

@Composable
fun AgentMarkdownText(
    blocks: List<MessageBlock>,
    fallbackText: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (blocks.isEmpty()) {
            if (fallbackText.isNotEmpty()) {
                Text(
                    text = fallbackText,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 23.sp),
                    color = ForgeInk,
                )
            }
            return@Column
        }
        blocks.forEachIndexed { index, block ->
            if (index > 0) Spacer(Modifier.height(10.dp))
            when (block) {
                is MessageBlock.Paragraph -> Text(
                    text = annotated(block.spans),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 23.sp),
                    color = ForgeInk,
                )

                is MessageBlock.Heading -> Text(
                    text = annotated(block.spans),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = headingFontSize(block.level),
                        lineHeight = headingLineHeight(block.level),
                    ),
                    color = ForgeInk,
                )

                is MessageBlock.Quote -> Row {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .heightIn(min = 18.dp)
                            .background(ForgePeriwinkle.copy(alpha = 0.6f), RoundedCornerShape(2.dp)),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = annotated(block.spans),
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp),
                        color = ForgeMuted,
                    )
                }

                is MessageBlock.BulletList -> Column {
                    block.items.forEach { item ->
                        Row(modifier = Modifier.padding(vertical = 2.dp)) {
                            Text("•", color = ForgeMint, fontSize = 15.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = annotated(item),
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp),
                                color = ForgeInk,
                            )
                        }
                    }
                }

                is MessageBlock.NumberedList -> Column {
                    block.items.forEachIndexed { number, item ->
                        Row(modifier = Modifier.padding(vertical = 2.dp)) {
                            Text(
                                text = "${number + 1}.",
                                color = ForgeMint,
                                fontSize = 15.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = annotated(item),
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp),
                                color = ForgeInk,
                            )
                        }
                    }
                }

                is MessageBlock.Code -> AgentCodeBlock(block.language, block.code)
            }
        }
    }
}

private fun headingFontSize(level: Int) = when (level) {
    1 -> 20.sp
    2 -> 18.sp
    3 -> 16.sp
    else -> 15.sp
}

private fun headingLineHeight(level: Int) = when (level) {
    1 -> 26.sp
    2 -> 24.sp
    3 -> 22.sp
    else -> 21.sp
}

@Composable
private fun annotated(spans: List<InlineSpan>): AnnotatedString = buildAnnotatedString {
    spans.forEach { span ->
        when (span) {
            is InlineSpan.Text -> append(span.text)
            is InlineSpan.Code -> withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = ForgeMint,
                    background = ForgeSurfaceVariant,
                ),
            ) { append(span.text) }

            is InlineSpan.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(span.text) }
            is InlineSpan.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(span.text) }
            is InlineSpan.Link -> {
                if (isSafeLink(span.url)) {
                    withLink(LinkAnnotation.Url(span.url)) {
                        withStyle(SpanStyle(color = ForgePeriwinkle, textDecoration = TextDecoration.Underline)) {
                            append(span.text)
                        }
                    }
                } else {
                    withStyle(SpanStyle(color = ForgePeriwinkle)) { append(span.text) }
                }
            }
        }
    }
}

// ───────────────────────────── Code block ─────────────────────────────

@Composable
fun AgentCodeBlock(language: String?, code: String, modifier: Modifier = Modifier) {
    var expanded by remember(code) { mutableStateOf(false) }
    var copied by remember(code) { mutableStateOf(false) }
    val context = LocalContext.current
    val lineCount = remember(code) { code.count { it == '\n' } + 1 }
    val collapsible = lineCount > COLLAPSE_LINE_THRESHOLD
    val shape = RoundedCornerShape(12.dp)
    val scroll = rememberScrollState()

    LaunchedEffect(copied) {
        if (copied) {
            delay(1600)
            copied = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ForgeCanvas)
            .border(1.dp, ForgeBorder, shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ForgeSurfaceVariant.copy(alpha = 0.6f))
                .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label(language),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = ForgeMuted,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "$lineCount lines",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = ForgeMuted,
            )
            IconButton(
                onClick = { copyToClipboard(context, "Agent code", code); copied = true },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                    contentDescription = if (copied) "Code copied" else "Copy code",
                    tint = if (copied) ForgeMint else ForgeMuted,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = if (collapsible && !expanded) 300.dp else Dp.Unspecified)
                .clipToBounds(),
        ) {
            Row(modifier = Modifier.horizontalScroll(scroll)) {
                SelectionContainer {
                    Text(
                        text = highlighted(code, language),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp,
                        softWrap = false,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }
        if (collapsible) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = ForgeMuted,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (expanded) "Collapse" else "Show all $lineCount lines",
                    style = MaterialTheme.typography.labelSmall,
                    color = ForgeMuted,
                )
            }
        }
    }
}

@Composable
private fun highlighted(code: String, language: String?): AnnotatedString {
    val tokens = remember(code, language) { AgentChatPresentation.highlightCode(code, language) }
    return buildAnnotatedString {
        tokens.forEach { token -> withStyle(SpanStyle(color = tokenColor(token))) { append(token.text) } }
    }
}

private fun tokenColor(token: CodeToken): Color = when (token.kind) {
    CodeTokenKind.PLAIN -> ForgeInk
    CodeTokenKind.KEYWORD -> ForgePeriwinkle
    CodeTokenKind.STRING -> ForgeMint
    CodeTokenKind.COMMENT -> ForgeMuted
    CodeTokenKind.NUMBER -> ForgeAmber
    CodeTokenKind.TYPE -> ForgeAmber
}

private fun label(language: String?): String = language?.takeIf { it.isNotBlank() }?.uppercase() ?: "CODE"

// ───────────────────────────── Shared bits ─────────────────────────────

@Composable
fun AgentAvatar(size: Dp = 28.dp) {
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(ForgeMint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.AutoAwesome,
            contentDescription = null,
            tint = ForgeMint,
            modifier = Modifier.size(size * 0.57f),
        )
    }
}

@Composable
fun TypingDots() {
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
            Box(modifier = Modifier.size(7.dp).background(ForgeMuted.copy(alpha = alpha), CircleShape))
        }
    }
}

@Composable
private fun CopyAction(onCopy: () -> Unit, label: String) {
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1400)
            copied = false
        }
    }
    IconButton(
        onClick = { onCopy(); copied = true },
        modifier = Modifier.size(34.dp),
    ) {
        Icon(
            imageVector = if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
            contentDescription = label,
            tint = if (copied) ForgeMint else ForgeMuted,
            modifier = Modifier.size(15.dp),
        )
    }
}

@Composable
private fun StateChip(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun MessageTimestamp(timestampMillis: Long) {
    val text = AgentChatPresentation.formatClockTime(timestampMillis)
    if (text.isNotEmpty()) MetaText(text)
}

@Composable
private fun MetaText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = ForgeMuted,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

private fun isSafeLink(url: String): Boolean {
    val lower = url.lowercase()
    return lower.startsWith("http://") || lower.startsWith("https://") ||
        lower.startsWith("mailto:") || lower.startsWith("tel:")
}

/** Opens a safe link externally. Only http(s)/mailto/tel are accepted. */
fun openExternalLink(context: Context, url: String) {
    if (!isSafeLink(url)) return
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Clipboard helper; the app uses the platform clipboard rather than Compose's. */
fun copyToClipboard(context: Context, label: String, text: String) {
    if (text.isEmpty()) return
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText(label, text))
}
