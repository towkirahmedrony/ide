package com.agentx.app.ui.ide.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.agentx.app.termux.TermuxKeys
import com.agentx.app.termux.TermuxProvisioningState
import com.agentx.app.termux.TermuxTerminalHost
import com.agentx.app.termux.TermuxViewClient
import com.agentx.app.termux.TermuxViewHost
import com.agentx.app.ui.ide.state.TerminalUiState
import com.agentx.app.ui.ide.state.TerminalViewModel
import com.agentx.app.ui.theme.ForgeAmber
import com.agentx.app.ui.theme.ForgeCanvas
import com.agentx.app.ui.theme.ForgeDanger
import com.agentx.app.ui.theme.ForgeInk
import com.agentx.app.ui.theme.ForgeMint
import com.agentx.app.ui.theme.ForgeMuted
import com.agentx.app.ui.theme.ForgeSurfaceVariant
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import java.util.concurrent.atomic.AtomicBoolean

private val TerminalMetaStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 11.sp,
)

private data class ExtraKey(val label: String, val bytes: () -> ByteArray)

private val EXTRA_KEYS = listOf(
    ExtraKey("ESC") { TermuxKeys.ESCAPE },
    ExtraKey("TAB") { TermuxKeys.TAB },
    ExtraKey("^C") { TermuxKeys.CTRL_C },
    ExtraKey("^D") { TermuxKeys.CTRL_D },
    ExtraKey("^Z") { TermuxKeys.CTRL_Z },
    ExtraKey("^L") { TermuxKeys.CTRL_L },
    ExtraKey("\u2190") { TermuxKeys.ARROW_LEFT },
    ExtraKey("\u2191") { TermuxKeys.ARROW_UP },
    ExtraKey("\u2193") { TermuxKeys.ARROW_DOWN },
    ExtraKey("\u2192") { TermuxKeys.ARROW_RIGHT },
    ExtraKey("HOME") { TermuxKeys.HOME },
    ExtraKey("END") { TermuxKeys.END },
    ExtraKey("PGUP") { TermuxKeys.PAGE_UP },
    ExtraKey("PGDN") { TermuxKeys.PAGE_DOWN },
    ExtraKey("/") { "/".toByteArray() },
    ExtraKey("-") { "-".toByteArray() },
    ExtraKey("|") { "|".toByteArray() },
    ExtraKey("~") { "~".toByteArray() },
)

@Composable
fun TerminalScreen(
    viewModel: TerminalViewModel,
    modifier: Modifier = Modifier,
) {
    val state = viewModel.uiState
    val context = LocalContext.current

    val terminalView = remember { mutableStateOf<TerminalView?>(null) }
    val redrawPending = remember { AtomicBoolean(false) }
    val controlDown = remember { mutableStateOf(false) }
    val altDown = remember { mutableStateOf(false) }

    val viewHost = remember {
        object : TermuxViewHost {
            override fun onScale(scale: Float): Float {
                if (scale > 1.1f) viewModel.zoomIn() else if (scale < 0.9f) viewModel.zoomOut()
                return 1.0f
            }

            override fun onSingleTapUp(event: MotionEvent) {
                terminalView.value?.let { showTerminalKeyboard(context, it) }
            }
            override fun onLongPress(event: MotionEvent): Boolean = false
            override fun readControlKey(): Boolean = controlDown.value
            override fun readAltKey(): Boolean = altDown.value
            override fun readShiftKey(): Boolean = false
            override fun readFnKey(): Boolean = false
            override fun onCopyModeChanged(copyMode: Boolean) = Unit
        }
    }

    val terminalHost = remember {
        object : TermuxTerminalHost {
            override fun onScreenUpdated(session: TerminalSession) {
                val view = terminalView.value ?: return
                if (redrawPending.compareAndSet(false, true)) {
                    view.post {
                        redrawPending.set(false)
                        terminalView.value?.onScreenUpdated()
                    }
                }
            }

            override fun onSessionFinished(session: TerminalSession) {
                terminalView.value?.post {
                    terminalView.value?.onScreenUpdated()
                    viewModel.refreshFromHost()
                    controlDown.value = false
                    altDown.value = false
                } ?: viewModel.refreshFromHost()
            }

            override fun onTitleChanged(session: TerminalSession) {
                terminalView.value?.post { viewModel.refreshFromHost() } ?: viewModel.refreshFromHost()
            }
            override fun onColorsChanged(session: TerminalSession) {
                terminalView.value?.post { terminalView.value?.invalidate() }
            }
            override fun onCopyTextToClipboard(text: String) = copyToClipboard(context, text)
            override fun onPasteTextFromClipboard(session: TerminalSession?) {
                clipboardText(context)?.let { text ->
                    val bytes = text.toByteArray(Charsets.UTF_8)
                    session?.write(bytes, 0, bytes.size)
                }
            }
            override fun onBell() = Unit
            override fun onShellPid(session: TerminalSession, pid: Int) = Unit
        }
    }

    DisposableEffect(viewModel) {
        viewModel.bindTerminalHost(terminalHost)
        onDispose { viewModel.unbindTerminalHost(terminalHost) }
    }

    Column(modifier = modifier.fillMaxSize().background(ForgeCanvas)) {
        TerminalHeader(
            state = state,
            viewModel = viewModel,
            onShowKeyboard = { terminalView.value?.let { showTerminalKeyboard(context, it) } },
        )

        ProvisioningBanner(state = state, onInstall = viewModel::provision)

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (state.unavailable) {
                UnavailableNotice()
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        TerminalView(ctx, null).apply {
                            setTerminalViewClient(TermuxViewClient(viewHost))
                            setBackgroundColor(0xFF101418.toInt())
                            setTextSize(state.fontSizePx)
                            isFocusable = true
                            isFocusableInTouchMode = true
                            requestFocus()
                            terminalView.value = this
                        }
                    },
                    update = { view ->
                        view.setTextSize(state.fontSizePx)
                        val session = viewModel.activeTerminalSession()
                        if (session != null && view.currentSession !== session) {
                            view.attachSession(session)
                            view.setTerminalCursorBlinkerState(true, true)
                        }
                    },
                )
            }
        }

        val note = state.workspaceNote ?: state.prefixNote
        if (note != null) {
            NoteStrip(text = note, tone = if (state.workspaceNote != null) ForgeAmber else ForgeMuted)
        }

        ExtraKeyRow(
            controlDown = controlDown.value,
            altDown = altDown.value,
            onToggleControl = { controlDown.value = !controlDown.value },
            onToggleAlt = { altDown.value = !altDown.value },
            onKey = { key -> viewModel.send(key.bytes()) },
        )
    }
}

@Composable
private fun TerminalHeader(
    state: TerminalUiState,
    viewModel: TerminalViewModel,
    onShowKeyboard: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ForgeSurfaceVariant)
            .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Terminal \u00b7 ${state.statusLabel}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ForgeMuted,
                )
                Text(
                    text = state.workingDirectory.ifBlank { "(starting)" },
                    style = TerminalMetaStyle.copy(color = ForgeMint),
                    maxLines = 1,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                )
            }
            IconButton(onClick = onShowKeyboard, enabled = state.activeHandle != null) {
                Icon(Icons.Filled.Keyboard, contentDescription = "Show keyboard")
            }
            IconButton(onClick = viewModel::zoomOut) { Icon(Icons.Filled.Remove, contentDescription = "Smaller text") }
            IconButton(onClick = viewModel::zoomIn) { Icon(Icons.Filled.Add, contentDescription = "Larger text") }
            IconButton(
                onClick = viewModel::openScratchShell,
                enabled = !state.unavailable,
            ) { Icon(Icons.Filled.Add, contentDescription = "New") }
            IconButton(
                onClick = viewModel::restart,
                enabled = state.activeHandle != null,
            ) { Icon(Icons.Filled.Refresh, contentDescription = "Restart") }
            IconButton(
                onClick = viewModel::terminateActive,
                enabled = state.activeHandle != null,
            ) { Icon(Icons.Filled.Stop, contentDescription = "Kill") }
        }
        if (state.sessions.size > 1) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(vertical = 2.dp),
            ) {
                items(state.sessions, key = { it.handle }) { session ->
                    val selected = session.handle == state.activeHandle
                    Text(
                        text = session.label + if (session.running) "" else " (exited)",
                        style = TerminalMetaStyle.copy(color = if (selected) ForgeMint else ForgeMuted),
                        modifier = Modifier
                            .background(ForgeCanvas)
                            .clickable { viewModel.selectSession(session.handle) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProvisioningBanner(state: TerminalUiState, onInstall: () -> Unit) {
    val provisioning = state.provisioning
    when {
        provisioning is TermuxProvisioningState.Downloading -> {
            LinearProgressIndicator(
                progress = { provisioning.percent / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        provisioning is TermuxProvisioningState.Verifying ||
            provisioning is TermuxProvisioningState.Extracting -> {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        provisioning is TermuxProvisioningState.Failed -> {
            NoteStrip(text = "${provisioning.message} Tap Install to retry.", tone = ForgeDanger)
        }

        state.canInstall && state.prefixNote == null -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ForgeSurfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Termux packages are not installed yet.",
                    style = TerminalMetaStyle.copy(color = ForgeMuted),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onInstall) {
                    Icon(Icons.Filled.Download, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Install")
                }
            }
        }
    }
}

@Composable
private fun UnavailableNotice() {
    Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Text(
            text = "The embedded Termux terminal is not available in this build. " +
                "The Agent and Editor tabs are unaffected.",
            style = TerminalMetaStyle.copy(color = ForgeMuted),
        )
    }
}

@Composable
private fun NoteStrip(text: String, tone: androidx.compose.ui.graphics.Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ForgeSurfaceVariant)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text = text, style = TerminalMetaStyle.copy(color = tone))
    }
}

@Composable
private fun ExtraKeyRow(
    controlDown: Boolean,
    altDown: Boolean,
    onToggleControl: () -> Unit,
    onToggleAlt: () -> Unit,
    onKey: (ExtraKey) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().background(ForgeSurfaceVariant)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModifierKey("CTRL", active = controlDown, onClick = onToggleControl)
            ModifierKey("ALT", active = altDown, onClick = onToggleAlt)
            Spacer(Modifier.weight(1f))
            Text(
                text = "Tap the terminal to type",
                style = TerminalMetaStyle.copy(color = ForgeMuted),
            )
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth().height(48.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        ) {
            items(EXTRA_KEYS, key = { it.label }) { key ->
                TextButton(
                    onClick = { onKey(key) },
                    modifier = Modifier.background(ForgeCanvas),
                ) {
                    Text(
                        text = key.label,
                        style = TerminalMetaStyle.copy(color = ForgeInk),
                    )
                }
            }
        }
    }
}

@Composable
private fun ModifierKey(label: String, active: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.background(if (active) ForgeMint else ForgeCanvas)) {
        Text(
            text = label,
            style = TerminalMetaStyle.copy(color = if (active) ForgeCanvas else ForgeMuted),
        )
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText("terminal", text))
}

private fun clipboardText(context: Context): String? {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    val clip = manager.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.itemAt(0).coerceToText(context).toString().takeIf { it.isNotEmpty() }
}

private fun showTerminalKeyboard(context: Context, view: TerminalView) {
    view.post {
        view.requestFocus()
        val keyboard = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        keyboard?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }
}
