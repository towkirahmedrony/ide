package com.agentx.app.ui.ide.screens.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.widget.Toast
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
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.agentx.app.context.AgentAttachmentKind
import com.agentx.app.ui.ide.model.AgentActivityKind
import com.agentx.app.ui.ide.model.AttachmentUiModel
import com.agentx.app.ui.ide.model.AgentTurnOutcome
import com.agentx.app.ui.ide.model.ChatMessageKind
import com.agentx.app.ui.ide.model.ChatMessageUiModel
import com.agentx.app.ui.ide.model.InlineSpan
import com.agentx.app.ui.ide.model.MessageBlock
import com.agentx.app.ui.ide.model.MessageState
import com.agentx.app.ui.ide.model.ToolActivityUiModel
import com.agentx.app.ui.ide.model.ToolRunStatus
import com.agentx.app.ui.ide.model.UiError
import com.agentx.app.ui.ide.state.AgentChatPresentation
import com.agentx.app.ui.ide.state.AgentStages
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
    awaitingPermission: Boolean = false,
) {
    when (message.kind) {
        ChatMessageKind.SYSTEM -> AgentSystemNote(message, modifier)
        ChatMessageKind.USER -> AgentUserBubble(message, onCopy, onEditSend, modifier)
        ChatMessageKind.TOOL -> AgentToolCardMessage(message, modifier)
        ChatMessageKind.ERROR -> AgentErrorCard(message, onCopy, onRetry, modifier)
        ChatMessageKind.ASSISTANT -> AgentTurnMessage(message, onCopy, onRegenerate, awaitingPermission, modifier)
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

    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        // What the message was sent with, shown above it. Attachments are part of the turn's
        // request rather than the stored transcript, so a restored conversation simply has none.
        if (message.attachments.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.End,
            ) {
                message.attachments.forEach { attachment ->
                    SentAttachmentCard(attachment)
                    Spacer(Modifier.height(4.dp))
                }
                Spacer(Modifier.height(2.dp))
            }
        }
        if (editing) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(ForgeSurface)
                    .border(1.dp, ForgeBorder, RoundedCornerShape(12.dp))
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
                    TextButton(onClick = { editing = false; onEditSend(draft) }) { Text("Resend") }
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .widthIn(max = 340.dp)
                    .clip(RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp))
                    .background(ForgeSurfaceVariant)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                SelectionContainer {
                    Text(
                        text = message.rawText,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp),
                        color = ForgeInk,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val clock = AgentChatPresentation.formatClockTime(message.timestampMillis)
                if (clock.isNotBlank()) {
                    MetaText(clock)
                    Spacer(Modifier.width(4.dp))
                }
                IconAction(Icons.Filled.ContentCopy, "Copy message", onCopy)
                IconAction(Icons.Filled.Edit, "Edit and resend") {
                    editing = true
                    draft = message.rawText
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
    awaitingPermission: Boolean,
    modifier: Modifier = Modifier,
) {
    val streaming = message.state == MessageState.STREAMING
    val outcome = when {
        streaming -> AgentTurnOutcome.RUNNING
        message.state == MessageState.FAILED -> AgentTurnOutcome.FAILED
        message.state == MessageState.STOPPED -> AgentTurnOutcome.STOPPED
        else -> AgentTurnOutcome.SUCCESS
    }
    val dotColor = when (outcome) {
        AgentTurnOutcome.FAILED -> ForgeDanger
        AgentTurnOutcome.STOPPED -> ForgeAmber
        else -> ForgeMint
    }
    val steps = AgentStages.visibleActivities(message.activities)
    // A plain chat reply with no real work shows no panel once it is finished.
    val showPanel = streaming ||
        awaitingPermission ||
        outcome != AgentTurnOutcome.SUCCESS ||
        steps.any { it.kind != AgentActivityKind.THINKING } ||
        message.planSteps.size > 1

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(7.dp).background(dotColor, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(
                text = "AgentX",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = ForgeMuted,
            )
        }
        Spacer(Modifier.height(8.dp))

        if (showPanel) {
            AgentActivityPanel(
                activities = message.activities,
                outcome = outcome,
                elapsedMillis = message.elapsedMillis ?: 0L,
                planSteps = message.planSteps,
                awaitingPermission = awaitingPermission,
                responding = message.rawText.isNotEmpty(),
            )
            Spacer(Modifier.height(10.dp))
        }

        if (message.rawText.isNotEmpty()) {
            SelectionContainer { AgentMarkdownText(message.blocks, message.rawText) }
        }

        if (message.filesChanged.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            CodeChangesCard(changed = message.filesChanged)
        }

        if (!streaming && !awaitingPermission) {
            val meta = buildList {
                val clock = AgentChatPresentation.formatClockTime(message.timestampMillis)
                if (clock.isNotBlank()) add(clock)
                message.elapsedMillis?.takeIf { it > 0L }?.let { add(AgentChatPresentation.formatDuration(it)) }
                message.modelId?.takeIf { it.isNotBlank() }?.let { add(it.substringAfterLast('/')) }
            }.joinToString(" · ")
            Row(
                modifier = Modifier.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (message.state == MessageState.STOPPED) {
                    StateChip("Stopped", ForgeAmber)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = ForgeMuted,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconAction(Icons.Filled.ContentCopy, "Copy reply", onCopy)
                IconAction(Icons.Filled.Refresh, "Regenerate response", onRegenerate)
            }
        }
    }
}

@Composable
private fun IconAction(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
        Icon(icon, contentDescription = description, tint = ForgeMuted, modifier = Modifier.size(15.dp))
    }
}

/**
 * Compact code-changes card fed only by the runtime's real [changed] file list.
 */
@Composable
private fun CodeChangesCard(changed: List<String>) {
    var expanded by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val shown = if (expanded) changed else changed.take(3)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ForgeSurface)
            .border(1.dp, ForgeBorder, shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "✎", color = ForgeMint, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${changed.size} file${if (changed.size == 1) "" else "s"} changed",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = ForgeInk,
                modifier = Modifier.weight(1f),
            )
            if (changed.size > 3) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse changes" else "Expand changes",
                    tint = ForgeMuted,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
            shown.forEach { path ->
                Text(
                    text = path,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = ForgeMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
            if (!expanded && changed.size > 3) {
                Text(
                    text = "+${changed.size - 3} more",
                    style = MaterialTheme.typography.labelSmall,
                    color = ForgeMuted,
                    modifier = Modifier
                        .clickable { expanded = true }
                        .padding(top = 2.dp),
                )
            }
        }
    }
}

// ───────────────────────────── System / tool / error ───────────────────────────

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
                        color = ForgeInk,
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

private fun label(language: String?) = language?.takeIf { it.isNotBlank() } ?: "text"

private fun highlighted(code: String, language: String?) = code

private fun isSafeLink(url: String): Boolean = runCatching { Uri.parse(url) }.getOrNull()?.scheme in setOf("http", "https")

/** Copies [text] to the clipboard only. It never opens a share sheet. */
fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    // Android 13+ shows its own "copied" confirmation.
    if (Build.VERSION.SDK_INT < 33) {
        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun MessageTimestamp(timestampMillis: Long?) {
    val text = timestampMillis?.let { AgentChatPresentation.formatClockTime(it) }.orEmpty()
    if (text.isNotBlank()) MetaText(text)
}

@Composable
private fun CopyAction(onCopy: () -> Unit, label: String) {
    TextButton(onClick = onCopy) {
        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(label)
    }
}

@Composable
private fun MetaText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = ForgeMuted,
        fontFamily = FontFamily.Monospace,
    )
}

@Composable
private fun StateChip(label: String, color: Color) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(999.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

/**
 * One file a sent message carried.
 *
 * Only the file's name and type are shown: the path is how AgentX finds it, not something the user
 * needs to read, and the agent can be asked about the file by name.
 */
@Composable
private fun SentAttachmentCard(attachment: AttachmentUiModel) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .widthIn(max = 340.dp)
            .clip(shape)
            .background(ForgeSurface)
            .border(1.dp, ForgeBorder, shape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = when (attachment.kind) {
                AgentAttachmentKind.IMAGE -> Icons.Filled.Image
                AgentAttachmentKind.DOCUMENT -> Icons.Filled.Description
                AgentAttachmentKind.FILE -> Icons.Filled.AttachFile
            },
            contentDescription = null,
            tint = ForgeMuted,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = attachment.displayName,
            style = MaterialTheme.typography.labelSmall,
            color = ForgeInk,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 180.dp),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = attachment.typeLabel,
            style = MaterialTheme.typography.labelSmall,
            color = ForgeMuted,
            maxLines = 1,
        )
    }
}
