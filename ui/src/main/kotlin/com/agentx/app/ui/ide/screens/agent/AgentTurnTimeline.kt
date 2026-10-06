package com.agentx.app.ui.ide.screens.agent

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentx.app.ui.ide.model.ActivityItemStatus
import com.agentx.app.ui.ide.model.AgentActivityKind
import com.agentx.app.ui.ide.model.AgentActivityUiModel
import com.agentx.app.ui.ide.model.AgentTurnOutcome
import com.agentx.app.ui.ide.model.ChatMessageUiModel
import com.agentx.app.ui.ide.model.MessageState
import com.agentx.app.ui.ide.state.AgentChatPresentation
import com.agentx.app.ui.ide.state.AgentStages
import com.agentx.app.ui.ide.state.AgentTurnSegments
import com.agentx.app.ui.ide.state.TurnSegment
import com.agentx.app.ui.theme.ForgeAmber
import com.agentx.app.ui.theme.ForgeBorder
import com.agentx.app.ui.theme.ForgeCanvas
import com.agentx.app.ui.theme.ForgeDanger
import com.agentx.app.ui.theme.ForgeInk
import com.agentx.app.ui.theme.ForgeMint
import com.agentx.app.ui.theme.ForgeMuted
import com.agentx.app.ui.theme.ForgeSurface
import com.agentx.app.ui.theme.ForgeSurfaceVariant

private const val LIVE_PREVIEW_STEPS = 4
private const val OUTPUT_PREVIEW_LINES = 8
private const val USER_COLLAPSED_LINES = 6

// ───────────────────────────── Assistant turn ─────────────────────────────

/**
 * An assistant turn as a plain timeline: the agent's own text and compact,
 * collapsible step groups, in the order they really happened.
 */
@Composable
fun AgentTurnMessage(
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
    val segments = remember(message.rawText, message.activities) {
        AgentTurnSegments.build(message.rawText, message.activities)
    }
    val lastGroupIndex = segments.indexOfLast { it is TurnSegment.Steps }
    val live = streaming && !awaitingPermission
    val hasActiveStep = message.activities.any {
        AgentTurnSegments.isStep(it) && it.status == ActivityItemStatus.ACTIVE
    }

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
        Spacer(Modifier.height(10.dp))

        segments.forEachIndexed { index, segment ->
            if (index > 0) Spacer(Modifier.height(10.dp))
            when (segment) {
                is TurnSegment.Text -> {
                    val blocks = remember(segment.text) { AgentChatPresentation.parseBlocks(segment.text) }
                    SelectionContainer { AgentMarkdownText(blocks, segment.text) }
                }

                is TurnSegment.Steps -> StepsGroup(
                    steps = segment.steps,
                    groupId = segment.id,
                    isLastGroup = index == lastGroupIndex,
                    outcome = outcome,
                    live = live,
                    waiting = awaitingPermission,
                )
            }
        }

        if (live && !hasActiveStep) {
            Spacer(Modifier.height(8.dp))
            ThinkingDots()
        }
        if (awaitingPermission) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Waiting for your permission",
                style = MaterialTheme.typography.labelMedium,
                color = ForgeAmber,
            )
        }

        if (!streaming && !awaitingPermission) {
            val meta = buildList {
                val clock = AgentChatPresentation.formatClockTime(message.timestampMillis)
                if (clock.isNotBlank()) add(clock)
                message.elapsedMillis?.takeIf { it > 0L }?.let { add(AgentChatPresentation.formatDuration(it)) }
                message.modelId?.takeIf { it.isNotBlank() }?.let { add(it.substringAfterLast('/')) }
            }.joinToString(" · ")
            Row(
                modifier = Modifier.padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (outcome == AgentTurnOutcome.STOPPED) {
                    Text(
                        text = "Stopped",
                        style = MaterialTheme.typography.labelSmall,
                        color = ForgeAmber,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .background(ForgeAmber.copy(alpha = 0.12f), RoundedCornerShape(999.dp))
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                    )
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
                if (message.rawText.isNotBlank()) {
                    TimelineIconAction(Icons.Filled.ContentCopy, "Copy reply", onCopy)
                }
                TimelineIconAction(Icons.Filled.Refresh, "Regenerate response", onRegenerate)
            }
        }
    }
}

// ───────────────────────────── Step group ─────────────────────────────

@Composable
private fun StepsGroup(
    steps: List<AgentActivityUiModel>,
    groupId: String,
    isLastGroup: Boolean,
    outcome: AgentTurnOutcome,
    live: Boolean,
    waiting: Boolean,
) {
    // Only the newest group can still be running; older groups are always finished.
    val groupOutcome = if (isLastGroup) outcome else AgentTurnOutcome.SUCCESS
    val settled = steps.map { AgentStages.settle(it, groupOutcome, waiting && isLastGroup) }
    val active = settled.lastOrNull { it.status == ActivityItemStatus.ACTIVE }
    val liveGroup = live && isLastGroup && active != null
    val failed = settled.any { it.status == ActivityItemStatus.FAILED }

    // null = automatic: a running group previews its latest steps, a finished one is collapsed.
    var userChoice by remember(groupId) { mutableStateOf<Boolean?>(null) }
    val showAll = userChoice == true
    val showPreview = userChoice == null && liveGroup

    val headerText = if (liveGroup && active != null) {
        AgentStages.describe(active)
    } else {
        AgentTurnSegments.summary(settled)
    }
    val headerColor = if (failed) ForgeDanger else ForgeMuted
    val pulse = rememberPulse(liveGroup)
    val rotation by animateFloatAsState(
        targetValue = if (showAll || showPreview) 90f else 0f,
        label = "group-chevron",
    )

    Column(modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { userChoice = !showAll }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepIcon(
                step = if (liveGroup && active != null) active else settled.first(),
                tint = headerColor,
                modifier = Modifier.alpha(pulse),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = headerText,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                color = headerColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (!liveGroup && settled.size > 1) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "${settled.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ForgeMuted.copy(alpha = 0.7f),
                    fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = if (showAll) "Hide steps" else "Show steps",
                tint = ForgeMuted,
                modifier = Modifier.size(18.dp).rotate(rotation),
            )
        }

        when {
            showAll -> settled.forEach { StepRow(it, live = liveGroup) }
            showPreview -> settled
                .filter { it !== active }
                .takeLast(LIVE_PREVIEW_STEPS)
                .forEach { StepRow(it, live = true) }
        }
    }
}

@Composable
private fun StepRow(step: AgentActivityUiModel, live: Boolean) {
    val failed = step.status == ActivityItemStatus.FAILED
    val running = live && step.status == ActivityItemStatus.ACTIVE
    val expandable = step.outputLines.isNotEmpty()
    var open by remember(step.id) { mutableStateOf(false) }
    val pulse = rememberPulse(running)
    val tint = if (failed) ForgeDanger else ForgeMuted
    val subject = rowSubject(step)

    val line = buildAnnotatedString {
        if (subject.isEmpty()) {
            withStyle(SpanStyle(color = tint)) { append(step.label) }
        } else {
            withStyle(SpanStyle(color = tint, fontWeight = FontWeight.Medium)) { append(rowVerb(step)) }
            append("  ")
            val italic = step.kind == AgentActivityKind.TERMINAL
            withStyle(
                SpanStyle(
                    color = tint.copy(alpha = if (failed) 1f else 0.85f),
                    fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
                ),
            ) { append(subject) }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (expandable) Modifier.clickable { open = !open } else Modifier)
                .padding(vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepIcon(step = step, tint = tint, modifier = Modifier.alpha(pulse))
            Spacer(Modifier.width(12.dp))
            Text(
                text = line,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (expandable) {
                Icon(
                    imageVector = if (open) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = if (open) "Hide output" else "Show output",
                    tint = ForgeMuted,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (open && expandable) {
            Text(
                text = step.outputLines.take(OUTPUT_PREVIEW_LINES).joinToString("\n"),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                color = if (failed) ForgeDanger else ForgeMuted,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 30.dp, bottom = 6.dp)
                    .background(ForgeSurface, RoundedCornerShape(8.dp))
                    .border(1.dp, ForgeBorder, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

private fun rowVerb(step: AgentActivityUiModel): String {
    val tool = step.toolName.orEmpty().lowercase()
    return when {
        step.kind == AgentActivityKind.TERMINAL -> "Run"
        tool.contains("list") -> "List"
        step.kind == AgentActivityKind.FILE_READ -> "Read"
        step.kind == AgentActivityKind.FILE_WRITE ->
            if (tool.contains("write") || tool.contains("create")) "Write" else "Edit"
        step.kind == AgentActivityKind.SEARCH -> "Search"
        step.kind == AgentActivityKind.SUB_AGENT -> AgentStages.roleName(step.role)
        else -> "Tool"
    }
}

/** The step's real subject (path, command or objective), shortened for one line. */
private fun rowSubject(step: AgentActivityUiModel): String {
    val raw = step.detail?.trim().orEmpty()
    if (raw.isEmpty()) return ""
    if (step.kind == AgentActivityKind.TERMINAL) return raw.lineSequence().first()
    if (raw.contains('/')) {
        val parts = raw.split('/').filter { it.isNotBlank() }
        if (parts.size > 2) return "…/" + parts.takeLast(2).joinToString("/")
    }
    return raw
}

@Composable
private fun StepIcon(step: AgentActivityUiModel, tint: Color, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    val tool = step.toolName.orEmpty().lowercase()
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        when {
            step.kind == AgentActivityKind.TERMINAL -> Text(
                text = ">_",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = tint,
            )
            tool.contains("list") -> StepGlyph(Icons.Filled.FolderOpen, tint)
            step.kind == AgentActivityKind.FILE_READ -> StepGlyph(Icons.Filled.Description, tint)
            step.kind == AgentActivityKind.FILE_WRITE -> StepGlyph(Icons.Filled.Edit, tint)
            step.kind == AgentActivityKind.SEARCH -> StepGlyph(Icons.Filled.Search, tint)
            step.kind == AgentActivityKind.SUB_AGENT -> StepGlyph(Icons.Filled.Person, tint)
            else -> StepGlyph(Icons.Filled.Build, tint)
        }
    }
}

@Composable
private fun StepGlyph(icon: ImageVector, tint: Color) {
    Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
}

/** A gentle fade for whatever is running right now; 1f (no animation) otherwise. */
@Composable
private fun rememberPulse(enabled: Boolean): Float {
    if (!enabled) return 1f
    val transition = rememberInfiniteTransition(label = "step-pulse")
    val value by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 800), RepeatMode.Reverse),
        label = "step-pulse-alpha",
    )
    return value
}

/** Three bouncing dots: the agent is thinking about its next move. */
@Composable
private fun ThinkingDots() {
    val transition = rememberInfiniteTransition(label = "thinking-dots")
    Row(
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(3) { index ->
            val lift by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 500),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(index * 160),
                ),
                label = "dot-$index",
            )
            Box(
                modifier = Modifier
                    .offset(y = (-3f * lift).dp)
                    .size(6.dp)
                    .alpha(0.4f + 0.6f * lift)
                    .background(ForgeMuted, CircleShape),
            )
        }
    }
}

// ───────────────────────────── User bubble ─────────────────────────────

@Composable
fun AgentUserBubble(
    message: ChatMessageUiModel,
    onCopy: () -> Unit,
    onEditSend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember(message.id) { mutableStateOf(message.rawText) }
    var expanded by remember(message.id) { mutableStateOf(false) }
    var overflowing by remember(message.rawText) { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        if (editing) {
            val shape = RoundedCornerShape(12.dp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(ForgeSurface)
                    .border(1.dp, ForgeBorder, shape)
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
            Column(
                modifier = Modifier
                    .widthIn(max = 360.dp)
                    .clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                    .background(ForgeSurfaceVariant)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                SelectionContainer {
                    Text(
                        text = message.rawText,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp),
                        color = ForgeInk,
                        maxLines = if (expanded) Int.MAX_VALUE else USER_COLLAPSED_LINES,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { result -> if (!expanded) overflowing = result.hasVisualOverflow },
                    )
                }
                if (overflowing || expanded) {
                    Row(
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .clickable { expanded = !expanded },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (expanded) "Collapse" else "Expand",
                            style = MaterialTheme.typography.labelMedium,
                            color = ForgeMint,
                        )
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                            tint = ForgeMint,
                            modifier = Modifier.size(16.dp).rotate(if (expanded) 180f else 0f),
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val clock = AgentChatPresentation.formatClockTime(message.timestampMillis)
                if (clock.isNotBlank()) {
                    Text(
                        text = clock,
                        style = MaterialTheme.typography.labelSmall,
                        color = ForgeMuted,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                TimelineIconAction(Icons.Filled.ContentCopy, "Copy message", onCopy)
                TimelineIconAction(Icons.Filled.Edit, "Edit and resend") {
                    editing = true
                    draft = message.rawText
                }
            }
        }
    }
}

@Composable
private fun TimelineIconAction(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
        Icon(icon, contentDescription = description, tint = ForgeMuted, modifier = Modifier.size(15.dp))
    }
}
