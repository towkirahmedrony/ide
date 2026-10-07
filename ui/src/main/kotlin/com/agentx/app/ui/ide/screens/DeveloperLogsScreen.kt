package com.agentx.app.ui.ide.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Subject
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentx.app.termux.DeveloperLogFilter
import com.agentx.app.termux.DeveloperLogLevel
import com.agentx.app.termux.DeveloperLogLine
import com.agentx.app.ui.ide.components.IdeEmptyState
import com.agentx.app.ui.ide.components.IdeTopBar
import com.agentx.app.ui.ide.state.DeveloperLogsViewModel
import com.agentx.app.ui.theme.ForgeAmber
import com.agentx.app.ui.theme.ForgeCanvas
import com.agentx.app.ui.theme.ForgeDanger
import com.agentx.app.ui.theme.ForgeInk
import com.agentx.app.ui.theme.ForgeMint
import com.agentx.app.ui.theme.ForgeMuted
import java.io.File

private const val SHARE_FILE_NAME = "agentx-terminal-diagnostics.txt"

@Composable
fun DeveloperLogsScreen(
    viewModel: DeveloperLogsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val raw by viewModel.lines.collectAsState()
    val visible = remember(raw, viewModel.filter, viewModel.query) {
        viewModel.displayed(raw)
    }
    val empty = raw.isEmpty()
    var confirmClear by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(visible.size, viewModel.autoScroll) {
        if (viewModel.autoScroll && visible.isNotEmpty()) {
            listState.scrollToItem(visible.lastIndex)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ForgeCanvas,
        topBar = {
            IdeTopBar(
                title = "Logs",
                subtitle = "Developer",
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
            ) {
                TextButton(onClick = { confirmClear = true }) { Text("Clear") }
                TextButton(onClick = { copyLog(context, viewModel.fullLog()) }) { Text("Copy") }
                TextButton(onClick = { shareLog(context, viewModel.fullLog()) }) { Text("Share") }
                TextButton(onClick = viewModel::captureSnapshot) { Text("Capture Snapshot") }
                // Where the app's private storage went, measured on this device. Kept separate from
                // the snapshot because it has to walk the installed runtime.
                TextButton(onClick = viewModel::captureStorageAudit) { Text("Storage") }
                TextButton(onClick = viewModel::toggleAutoScroll) {
                    Text(
                        text = if (viewModel.autoScroll) "Auto-scroll on" else "Auto-scroll off",
                        color = if (viewModel.autoScroll) ForgeMint else ForgeMuted,
                    )
                }
            }
            OutlinedTextField(
                value = viewModel.query,
                onValueChange = viewModel::updateQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                placeholder = { Text("Search") },
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DeveloperLogFilter.entries.forEach { item ->
                    FilterChip(
                        selected = viewModel.filter == item,
                        onClick = { viewModel.updateFilter(item) },
                        label = { Text(item.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ForgeMint.copy(alpha = 0.18f),
                            selectedLabelColor = ForgeMint,
                            labelColor = ForgeMuted,
                        ),
                    )
                }
            }
            if (empty) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    IdeEmptyState(
                        icon = Icons.Filled.Subject,
                        title = "No developer logs yet.",
                        message = "Open Terminal to start collecting diagnostics.",
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    itemsIndexed(
                        items = visible,
                        key = { index, line -> "$index:${line.raw.hashCode()}" },
                    ) { _, line ->
                        Text(
                            text = line.display,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                color = logColor(line),
                            ),
                        )
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear logs") },
            text = { Text("This removes the persistent developer log from this device.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clear()
                        confirmClear = false
                    },
                ) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun logColor(line: DeveloperLogLine): Color = when (line.level) {
    DeveloperLogLevel.ERROR -> ForgeDanger
    DeveloperLogLevel.WARN -> ForgeAmber
    DeveloperLogLevel.DEBUG -> ForgeMuted
    else -> ForgeInk
}

private fun copyLog(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText("developer logs", text))
}

private fun shareLog(context: Context, text: String) {
    runCatching { File(context.cacheDir, SHARE_FILE_NAME).writeText(text) }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, SHARE_FILE_NAME)
        putExtra(Intent.EXTRA_TITLE, SHARE_FILE_NAME)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, SHARE_FILE_NAME))
    }
}
