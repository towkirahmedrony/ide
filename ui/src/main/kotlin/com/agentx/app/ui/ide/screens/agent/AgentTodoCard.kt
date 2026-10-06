package com.agentx.app.ui.ide.screens.agent

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentx.app.ui.ide.model.PlanStepStatus
import com.agentx.app.ui.ide.model.PlanStepUiModel
import com.agentx.app.ui.ide.state.TodoSnapshot
import com.agentx.app.ui.theme.ForgeBorder
import com.agentx.app.ui.theme.ForgeDanger
import com.agentx.app.ui.theme.ForgeInk
import com.agentx.app.ui.theme.ForgeMint
import com.agentx.app.ui.theme.ForgeMuted
import com.agentx.app.ui.theme.ForgeSurface

/**
 * The agent's checklist, pinned above the composer. Open while the agent works,
 * folded away when it is finished; tap the header to override.
 */
@Composable
fun AgentTodoCard(snapshot: TodoSnapshot?, modifier: Modifier = Modifier) {
    if (snapshot == null) return
    val steps = snapshot.steps
    val done = steps.count { it.status == PlanStepStatus.DONE }
    val allDone = done == steps.size

    var userToggled by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(snapshot.running && !allDone) }
    LaunchedEffect(snapshot.running, allDone) {
        if (!userToggled) expanded = snapshot.running && !allDone
    }

    val currentTitle = steps.firstOrNull { it.status == PlanStepStatus.ACTIVE }?.title
        ?: steps.firstOrNull { it.status == PlanStepStatus.PENDING }?.title
    val shape = RoundedCornerShape(14.dp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(shape)
            .background(ForgeSurface)
            .border(1.dp, ForgeBorder, shape)
            .animateContentSize(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    userToggled = true
                    expanded = !expanded
                }
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "To-dos",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = ForgeInk,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "$done/${steps.size}",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = ForgeMuted,
            )
            if (!expanded && currentTitle != null && !allDone) {
                Spacer(Modifier.width(10.dp))
                Text(
                    text = currentTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = ForgeMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse to-dos" else "Expand to-dos",
                tint = ForgeMuted,
                modifier = Modifier.size(20.dp).rotate(if (expanded) 0f else -90f),
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
            ) {
                steps.forEach { step -> TodoRow(step, snapshot.running) }
            }
        }
    }
}

@Composable
private fun TodoRow(step: PlanStepUiModel, running: Boolean) {
    val done = step.status == PlanStepStatus.DONE
    val active = step.status == PlanStepStatus.ACTIVE
    val failed = step.status == PlanStepStatus.FAILED
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(modifier = Modifier.padding(top = 2.dp).size(18.dp), contentAlignment = Alignment.Center) {
            when (step.status) {
                PlanStepStatus.DONE ->
                    Icon(Icons.Filled.CheckCircle, null, tint = ForgeMint, modifier = Modifier.size(18.dp))
                PlanStepStatus.FAILED ->
                    Icon(Icons.Filled.Close, null, tint = ForgeDanger, modifier = Modifier.size(16.dp))
                PlanStepStatus.ACTIVE -> ActiveRing(pulse = running)
                PlanStepStatus.PENDING -> Box(
                    modifier = Modifier.size(16.dp).border(1.5.dp, ForgeMuted, CircleShape),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = step.title,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
            textDecoration = if (done) TextDecoration.LineThrough else TextDecoration.None,
            color = when {
                done -> ForgeMuted
                failed -> ForgeDanger
                else -> ForgeInk
            },
        )
    }
}

@Composable
private fun ActiveRing(pulse: Boolean) {
    val pulseAlpha = if (pulse) {
        val transition = rememberInfiniteTransition(label = "todo-pulse")
        val value by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 800), RepeatMode.Reverse),
            label = "todo-pulse-alpha",
        )
        value
    } else {
        1f
    }
    Box(
        modifier = Modifier
            .size(16.dp)
            .alpha(pulseAlpha)
            .border(2.dp, ForgeMint, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(modifier = Modifier.size(6.dp).background(ForgeMint, CircleShape))
    }
}
