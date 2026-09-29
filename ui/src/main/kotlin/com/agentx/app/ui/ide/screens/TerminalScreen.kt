package com.agentx.app.ui.ide.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentx.app.ui.ide.model.TerminalLineKind
import com.agentx.app.ui.ide.state.TerminalViewModel
import com.agentx.app.ui.theme.ForgeAmber
import com.agentx.app.ui.theme.ForgeCanvas
import com.agentx.app.ui.theme.ForgeDanger
import com.agentx.app.ui.theme.ForgeInk
import com.agentx.app.ui.theme.ForgeMint
import com.agentx.app.ui.theme.ForgeMuted
import com.agentx.app.ui.theme.ForgeSurfaceVariant
import com.agentx.app.workspace.process.TerminalSessionState

private val TerminalTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    lineHeight = 18.sp,
)

@Composable
fun TerminalScreen(
    viewModel: TerminalViewModel,
    modifier: Modifier = Modifier,
) {
    val state = viewModel.uiState
    val listState = rememberLazyListState()

    LaunchedEffect(state.lines.size) {
        if (state.lines.isNotEmpty()) {
            listState.animateScrollToItem(state.lines.lastIndex)
        }
    }

    Column(modifier = modifier.fillMaxSize().background(ForgeCanvas)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ForgeSurfaceVariant)
                .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Terminal · ${state.statusLabel}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ForgeMuted,
                )
                val cwd = state.workingDirectory.ifBlank { "(no cwd)" }
                Text(
                    text = cwd,
                    style = TerminalTextStyle.copy(color = ForgeMint, fontSize = 11.sp),
                    maxLines = 1,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                )
            }
            TextButton(onClick = viewModel::interrupt, enabled = state.shellAlive) {
                Icon(Icons.Filled.Stop, contentDescription = "Ctrl+C")
                Spacer(Modifier.width(6.dp))
                Text("Ctrl+C")
            }
            TextButton(onClick = viewModel::clear) {
                Icon(Icons.Filled.DeleteSweep, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Clear")
            }
            TextButton(onClick = viewModel::restart, enabled = !state.busy) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Restart")
            }
        }

        SelectionContainer(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(state.lines, key = { it.id }) { line ->
                    Text(
                        text = line.text,
                        style = TerminalTextStyle.copy(color = lineColor(line.kind)),
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ForgeSurfaceVariant)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "$",
                style = TerminalTextStyle.copy(color = ForgeMint),
            )
            OutlinedTextField(
                value = state.input,
                onValueChange = viewModel::onInputChange,
                modifier = Modifier
                    .weight(1f)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when {
                            event.isCtrlPressed && event.key == Key.C -> {
                                viewModel.interrupt()
                                true
                            }
                            event.isCtrlPressed && event.key == Key.D -> {
                                viewModel.eof()
                                true
                            }
                            event.key == Key.Tab -> {
                                viewModel.insertTab()
                                true
                            }
                            event.key == Key.DirectionUp -> {
                                viewModel.historyPrevious()
                                true
                            }
                            event.key == Key.DirectionDown -> {
                                viewModel.historyNext()
                                true
                            }
                            event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                                viewModel.submit()
                                true
                            }
                            else -> false
                        }
                    },
                singleLine = true,
                enabled = !state.busy,
                textStyle = TerminalTextStyle.copy(color = ForgeInk),
                placeholder = { Text("command", color = ForgeMuted) },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { viewModel.submit() }),
            )
            Button(
                onClick = viewModel::submit,
                enabled = !state.busy && state.sessionState != TerminalSessionState.STARTING,
            ) {
                if (state.busy || state.sessionState == TerminalSessionState.STARTING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = ForgeCanvas,
                    )
                } else {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Run")
                }
            }
        }
    }
}

private fun lineColor(kind: TerminalLineKind) = when (kind) {
    TerminalLineKind.INPUT -> ForgeMint
    TerminalLineKind.OUTPUT -> ForgeInk
    TerminalLineKind.ERROR -> ForgeDanger
    TerminalLineKind.SYSTEM -> ForgeAmber
}
