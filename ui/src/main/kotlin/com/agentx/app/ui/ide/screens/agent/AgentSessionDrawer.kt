package com.agentx.app.ui.ide.screens.agent

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentx.app.ui.ide.model.AgentSessionUiModel
import com.agentx.app.ui.ide.state.AgentChatPresentation
import com.agentx.app.ui.theme.ForgeCanvas
import com.agentx.app.ui.theme.ForgeDanger
import com.agentx.app.ui.theme.ForgeInk
import com.agentx.app.ui.theme.ForgeMint
import com.agentx.app.ui.theme.ForgeMuted
import com.agentx.app.ui.theme.ForgeSurface
import com.agentx.app.ui.theme.ForgeSurfaceVariant

/**
 * The Agent session sidebar. It renders the persistent session list owned by the
 * Agent session port; it never keeps a second copy of history of its own.
 */
@Composable
fun AgentSessionDrawerContent(
    sessions: List<AgentSessionUiModel>,
    activeSessionId: String?,
    nowMillis: Long,
    onNewSession: () -> Unit,
    onOpenSession: (String) -> Unit,
    onRenameSession: (String, String) -> Unit,
    onDeleteSession: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var renaming by remember { mutableStateOf<AgentSessionUiModel?>(null) }
    var deleting by remember { mutableStateOf<AgentSessionUiModel?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ForgeSurface)
            .padding(horizontal = 12.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Agent",
                style = MaterialTheme.typography.titleMedium,
                color = ForgeInk,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${sessions.size} session${if (sessions.size == 1) "" else "s"}",
                style = MaterialTheme.typography.labelSmall,
                color = ForgeMuted,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(ForgeMint, RoundedCornerShape(12.dp))
                .clickable(onClick = onNewSession),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = ForgeCanvas, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(text = "New Session", color = ForgeCanvas, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(14.dp))

        if (sessions.isEmpty()) {
            Text(
                text = "No saved sessions yet. Start a task and it will be saved here.",
                style = MaterialTheme.typography.bodySmall,
                color = ForgeMuted,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(sessions, key = { it.id }) { session ->
                    AgentSessionRow(
                        session = session,
                        active = session.id == activeSessionId,
                        nowMillis = nowMillis,
                        onOpen = { onOpenSession(session.id) },
                        onRename = { renaming = session },
                        onDelete = { deleting = session },
                    )
                }
            }
        }
    }

    renaming?.let { target ->
        RenameSessionDialog(
            initial = target.title,
            onDismiss = { renaming = null },
            onConfirm = { title ->
                renaming = null
                onRenameSession(target.id, title)
            },
        )
    }

    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete session?") },
            text = {
                Text(
                    "\"${target.title}\" and its saved conversation will be removed permanently.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = null
                        onDeleteSession(target.id)
                    },
                ) { Text("Delete", color = ForgeDanger) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun AgentSessionRow(
    session: AgentSessionUiModel,
    active: Boolean,
    nowMillis: Long,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (active) ForgeSurfaceVariant else ForgeSurface, shape)
            .border(1.dp, if (active) ForgeMint.copy(alpha = 0.4f) else ForgeSurfaceVariant, shape)
            .clickable(onClick = onOpen)
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(if (active) ForgeMint else ForgeMuted.copy(alpha = 0.5f), CircleShape),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = session.title.ifBlank { "New session" },
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 14.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                ),
                color = ForgeInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val relative = AgentChatPresentation.relativeTime(session.updatedAtMillis, nowMillis)
            val meta = buildString {
                if (session.messageCount > 0) {
                    append(session.messageCount).append(if (session.messageCount == 1) " message" else " messages")
                }
                if (relative.isNotEmpty()) {
                    if (isNotEmpty()) append(" · ")
                    append(relative)
                }
            }
            if (meta.isNotEmpty()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = ForgeMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onRename, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Filled.Edit,
                contentDescription = "Rename ${session.title}",
                tint = ForgeMuted,
                modifier = Modifier.size(16.dp),
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "Delete ${session.title}",
                tint = ForgeMuted,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun RenameSessionDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename session") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ForgeSurfaceVariant, RoundedCornerShape(10.dp))
                    .border(1.dp, ForgeSurfaceVariant, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = ForgeInk, fontSize = 15.sp),
                    cursorBrush = SolidColor(ForgeMint),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(draft.trim()) },
                enabled = draft.trim().isNotEmpty(),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
