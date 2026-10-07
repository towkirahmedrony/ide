package com.agentx.app.ui.ide.screens.agent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
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
import com.agentx.app.ui.ide.model.PlanStepStatus
import com.agentx.app.ui.ide.model.PlanStepUiModel
import com.agentx.app.ui.ide.state.AgentChatPresentation
import com.agentx.app.ui.ide.state.AgentStage
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

private const val OUTPUT_PREVIEW_LINES = 8

/**
 * One compact status card per assistant turn.
 *
 * Running: the current stage is prominent (`Reading · 4 files · 12s`), the live
 * action sits under it, earlier stages are small and muted.
 * Finished: collapses to `Done · 12.4s · 6 steps`; tap to see every real step.
 * A spinner exists only while the turn is truly running.
 */
@Composable
fun AgentActivityPanel(
    activities: List<AgentActivityUiModel>,
    outcome: AgentTurnOutcome,
    elapsedMillis: Long,
    planSteps: List<PlanStepUiModel> = emptyList(),
    awaitingPermission: Boolean = false,
    responding: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val waiting = awaitingPermission
    val live = outcome == AgentTurnOutcome.RUNNING && !waiting
    val visible = AgentStages.visibleActivities(activities)
    val rows = visible.map { AgentStages.settle(it, outcome, waiting) }
    val plan = AgentStages.settlePlan(planSteps, outcome, waiting)
    val progress = AgentStages.progress(visible, planSteps, outcome)
    val hasBody = rows.isNotEmpty() || plan.size > 1

    var expanded by remember { mutableStateOf(outcome == AgentTurnOutcome.FAILED) }
    var userToggled by remember { mutableStateOf(false) }
    LaunchedEffect(outcome) {
        if (outcome == AgentTurnOutcome.FAILED && !userToggled) expanded = true
    }

    val accent = when {
        waiting -> ForgeAmber
        live -> ForgeMint
        outcome == AgentTurnOutcome.FAILED -> ForgeDanger
        outcome == AgentTurnOutcome.STOPPED -> ForgeAmber
        else -> ForgeMint
    }
    val titleColor = if (!live && !waiting && outcome == AgentTurnOutcome.SUCCESS) ForgeMuted else accent

    val title = when {
        waiting -> "Waiting for permission"
        live && responding && progress.current == AgentStage.THINKING -> "Writing response"
        live -> progress.headline
        outcome == AgentTurnOutcome.SUCCESS -> "Done"
        outcome == AgentTurnOutcome.FAILED -> "Failed"
        else -> "Stopped"
    }
    val timeText: String? = when {
        live || waiting -> AgentChatPresentation.formatElapsedSeconds(elapsedMillis)
        elapsedMillis > 0L -> AgentChatPresentation.formatDuration(elapsedMillis)
        else -> null
    }
    val stepsText = if (!live && !waiting && rows.isNotEmpty()) {
        "${rows.size} step${if (rows.size == 1) "" else "s"}"
    } else {
        null
    }
    val headline = listOfNotNull(title, timeText, stepsText).joinToString(" · ")

    val secondary: String? = when {
        live || waiting -> progress.action
        outcome == AgentTurnOutcome.SUCCESS -> AgentStages.summary(visible)
        outcome == AgentTurnOutcome.FAILED ->
            rows.lastOrNull { it.status == ActivityItemStatus.FAILED }?.let { AgentStages.describe(it) }
        else -> null
    }?.takeIf { it.isNotBlank() }

    val showTrail = (live || waiting || expanded) && progress.reached.size > 1
    val trail = buildAnnotatedString {
        progress.reached.forEachIndexed { index, stage ->
            if (index > 0) {
                withStyle(SpanStyle(color = ForgeMuted.copy(alpha = 0.45f))) { append("  ›  ") }
            }
            val isCurrent = (live || waiting) && stage == progress.current
            val isEndStage = !live && !waiting && outcome != AgentTurnOutcome.SUCCESS && stage == progress.current
            when {
                isCurrent -> withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) {
                    append("● ${stage.label}")
                }
                isEndStage -> withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) {
                    append((if (outcome == AgentTurnOutcome.FAILED) "✕ " else "■ ") + stage.label)
                }
                else -> withStyle(SpanStyle(color = ForgeMuted)) { append("✓ ${stage.label}") }
            }
        }
    }

    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ForgeSurface)
            .border(1.dp, ForgeBorder, shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (hasBody) {
                        Modifier.clickable {
                            userToggled = true
                            expanded = !expanded
                        }
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(modifier = Modifier.padding(top = 1.dp).size(16.dp), contentAlignment = Alignment.Center) {
                when {
                    waiting -> Icon(Icons.Filled.Warning, null, tint = ForgeAmber, modifier = Modifier.size(15.dp))
                    live -> StageSpinner(color = ForgeMint, size = 14.dp)
                    outcome == AgentTurnOutcome.FAILED ->
                        Icon(Icons.Filled.ErrorOutline, null, tint = ForgeDanger, modifier = Modifier.size(15.dp))
                    outcome == AgentTurnOutcome.STOPPED ->
                        Icon(Icons.Filled.Stop, null, tint = ForgeAmber, modifier = Modifier.size(15.dp))
                    else -> Icon(Icons.Filled.CheckCircle, null, tint = ForgeMint, modifier = Modifier.size(15.dp))
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = headline,
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                    fontWeight = FontWeight.SemiBold,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (secondary != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = secondary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.5.sp,
                        color = ForgeMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (showTrail) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = trail,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        maxLines = 3,
                    )
                }
            }
            if (hasBody) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Hide steps" else "Show steps",
                    tint = ForgeMuted,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        AnimatedVisibility(visible = expanded && hasBody) {
            Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(ForgeBorder))
                Spacer(Modifier.height(8.dp))
                if (plan.size > 1) {
                    PlanChecklist(plan)
                    Spacer(Modifier.height(6.dp))
                }
                rows.forEach { row -> StepRow(row) }
            }
        }
    }
}

/** A small rotating arc. It only exists while a turn is genuinely running. */
@Composable
private fun StageSpinner(color: Color, size: Dp) {
    val transition = rememberInfiniteTransition(label = "stage-spinner")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing)),
        label = "stage-spinner-angle",
    )
    Canvas(modifier = Modifier.size(size)) {
        val strokeWidth = 2.dp.toPx()
        drawArc(
            color = color.copy(alpha = 0.22f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            style = Stroke(width = strokeWidth),
        )
        drawArc(
            color = color,
            startAngle = angle,
            sweepAngle = 90f,
            useCenter = false,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
        )
    }
}

@Composable
private fun StatusGlyph(status: ActivityItemStatus, tint: Color) {
    Box(modifier = Modifier.size(16.dp), contentAlignment = Alignment.Center) {
        when (status) {
            ActivityItemStatus.ACTIVE -> StageSpinner(color = tint, size = 12.dp)
            ActivityItemStatus.DONE -> Icon(Icons.Filled.Check, null, tint = ForgeMint, modifier = Modifier.size(14.dp))
            ActivityItemStatus.FAILED -> Icon(Icons.Filled.Close, null, tint = ForgeDanger, modifier = Modifier.size(14.dp))
            ActivityItemStatus.PENDING -> Box(
                modifier = Modifier.size(8.dp).border(1.5.dp, ForgeMuted, CircleShape),
            )
        }
    }
}

private fun verbOf(activity: AgentActivityUiModel): String = when (activity.kind) {
    AgentActivityKind.FILE_READ -> "Read"
    AgentActivityKind.SEARCH -> "Search"
    AgentActivityKind.FILE_WRITE -> "Edit"
    AgentActivityKind.TERMINAL -> "Run"
    AgentActivityKind.SUB_AGENT -> AgentStages.roleName(activity.role)
    AgentActivityKind.THINKING -> "Note"
    AgentActivityKind.WAITING -> "Wait"
    AgentActivityKind.ERROR -> "Error"
    AgentActivityKind.TOOL -> "Tool"
}

@Composable
private fun verbColor(kind: AgentActivityKind): Color = when (kind) {
    AgentActivityKind.FILE_READ, AgentActivityKind.SEARCH, AgentActivityKind.SUB_AGENT -> ForgePeriwinkle
    AgentActivityKind.FILE_WRITE -> ForgeMint
    AgentActivityKind.TERMINAL -> ForgeAmber
    AgentActivityKind.ERROR -> ForgeDanger
    else -> ForgeMuted
}

@Composable
private fun StepRow(activity: AgentActivityUiModel) {
    val failed = activity.status == ActivityItemStatus.FAILED
    val expandable = activity.outputLines.isNotEmpty()
    var open by remember(activity.id) { mutableStateOf(false) }
    val subject = if (activity.kind == AgentActivityKind.TERMINAL && !activity.detail.isNullOrBlank()) {
        "$ ${activity.label}"
    } else {
        activity.label
    }
    val line = buildAnnotatedString {
        withStyle(
            SpanStyle(
                color = verbColor(activity.kind),
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            ),
        ) { append(verbOf(activity)) }
        append("  ")
        withStyle(SpanStyle(color = if (failed) ForgeDanger else ForgeInk)) { append(subject) }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (expandable) Modifier.clickable { open = !open } else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusGlyph(activity.status, verbColor(activity.kind))
            Spacer(Modifier.width(8.dp))
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            activity.elapsedMillis?.takeIf { it > 0L }?.let { elapsed ->
                Spacer(Modifier.width(6.dp))
                Text(
                    text = AgentChatPresentation.formatDuration(elapsed),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = ForgeMuted,
                )
            }
            if (expandable) {
                Icon(
                    imageVector = if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (open) "Hide output" else "Show output",
                    tint = ForgeMuted,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        if (open && expandable) {
            Text(
                text = activity.outputLines.take(OUTPUT_PREVIEW_LINES).joinToString("\n"),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = if (failed) ForgeDanger else ForgeMuted,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, top = 4.dp)
                    .background(ForgeCanvas.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

/** The runtime's own plan, shown only when it has more than one step. */
@Composable
private fun PlanChecklist(steps: List<PlanStepUiModel>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "PLAN · ${AgentChatPresentation.planProgressLabel(steps)}",
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            letterSpacing = 0.4.sp,
            color = ForgeMuted,
        )
        Spacer(Modifier.height(4.dp))
        steps.forEach { step ->
            val status = when (step.status) {
                PlanStepStatus.ACTIVE -> ActivityItemStatus.ACTIVE
                PlanStepStatus.DONE -> ActivityItemStatus.DONE
                PlanStepStatus.FAILED -> ActivityItemStatus.FAILED
                PlanStepStatus.PENDING -> ActivityItemStatus.PENDING
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusGlyph(status, ForgeMint)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = step.title,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = when (step.status) {
                        PlanStepStatus.ACTIVE -> ForgeMint
                        PlanStepStatus.FAILED -> ForgeDanger
                        else -> ForgeMuted
                    },
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
