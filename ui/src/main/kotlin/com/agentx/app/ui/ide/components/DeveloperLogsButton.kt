package com.agentx.app.ui.ide.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Subject
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.agentx.app.ui.theme.ForgeBorder
import com.agentx.app.ui.theme.ForgeMint
import com.agentx.app.ui.theme.ForgeSurface
import kotlin.math.roundToInt

private val ButtonSize = 44.dp
private val EdgeInset = 12.dp

/**
 * Floating shortcut into the Developer Logs screen.
 *
 * It lives above the navigation graph, so it is reachable from every screen, and it
 * can be dragged anywhere. Its position is remembered across recomposition and
 * configuration changes. Tapping it opens the existing logs destination.
 */
@Composable
fun DeveloperLogsButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Developer logs",
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val sizePx = with(density) { ButtonSize.toPx() }
        val edgePx = with(density) { EdgeInset.toPx() }
        val boundsWidthPx = with(density) { maxWidth.toPx() }
        val boundsHeightPx = with(density) { maxHeight.toPx() }
        val maxX = (boundsWidthPx - sizePx - edgePx).coerceAtLeast(0f)
        val maxY = (boundsHeightPx - sizePx - edgePx).coerceAtLeast(0f)

        // A negative sentinel means "not placed yet"; without it the button would
        // snap to the top-left before the first layout pass reports real bounds.
        var offsetX by rememberSaveable { mutableStateOf(-1f) }
        var offsetY by rememberSaveable { mutableStateOf(-1f) }

        val defaultX = maxX
        val defaultY = maxY * 0.62f
        val currentX = (if (offsetX < 0f) defaultX else offsetX).coerceIn(0f, maxX)
        val currentY = (if (offsetY < 0f) defaultY else offsetY).coerceIn(0f, maxY)

        Box(
            modifier = Modifier
                .offset { IntOffset(currentX.roundToInt(), currentY.roundToInt()) }
                .size(ButtonSize)
                .shadow(6.dp, CircleShape)
                .background(ForgeSurface, CircleShape)
                .border(1.dp, ForgeBorder, CircleShape)
                .semantics {
                    contentDescription = label
                    role = Role.Button
                }
                .pointerInput(maxX, maxY) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val baseX = if (offsetX < 0f) defaultX else offsetX
                        val baseY = if (offsetY < 0f) defaultY else offsetY
                        offsetX = (baseX + dragAmount.x).coerceIn(0f, maxX)
                        offsetY = (baseY + dragAmount.y).coerceIn(0f, maxY)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { onClick() }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Subject,
                contentDescription = null,
                tint = ForgeMint,
            )
        }
    }
}
