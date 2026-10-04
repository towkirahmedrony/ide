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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.LaunchedEffect
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
import com.agentx.app.termux.TerminalSessionState
import com.agentx.app.termux.TermuxKeys
import com.agentx.app.termux.TermuxProvisioningState
import com.agentx.app.termux.TermuxTerminalHost
import com.agentx.app.termux.TermuxViewClient
import com.agentx.app.termux.TermuxViewHost
import com.agentx.app.ubuntu.AgentxRuntimeState
import com.agentx.app.ui.ide.state.developerRuntimeStageGuidance
import com.agentx.app.ui.ide.state.noSessionSummary
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

        // A shell that failed to start has no emulator and therefore no buffer to draw, so the
        // screen shows the reason and the way out in its place. Without this the terminal was
        // simply blank: nothing to read, nothing to tap and no keyboard.
        val session = viewModel.activeTerminalSession()
        // A session that failed to start has no emulator, so there is nothing to draw and a blank
        // surface would say nothing. A session that merely exited still has its buffer, so it keeps
        // the terminal and shows the exit banner under it.
        val showRecovery = state.showsRecoveryPanel
        val showTerminal = !state.unavailable && !showRecovery

        if (!state.unavailable && session == null && state.sessionState == null) {
            // A session may appear later (the first open is asynchronous); asking again is what
            // makes reopening the app land on a usable terminal rather than an empty panel.
            LaunchedEffect(state.sessionState, state.sessions.size) { viewModel.ensureSession() }
        }

        // Focus follows a live shell. Losing it on a failed session used to be permanent: even
        // after a good restart the terminal could not be typed into without leaving the screen.
        LaunchedEffect(state.sessionState) {
            if (state.sessionState == TerminalSessionState.RUNNING) {
                terminalView.value?.requestFocus()
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.unavailable -> UnavailableNotice()

                showRecovery -> RecoveryNotice(
                    heading = state.failureHeading ?: "Terminal is not running",
                    detail = state.exitLine ?: noSessionSummary(),
                    facts = state.nativeFacts,
                    diagnostics = state.diagnostics,
                    label = state.restartLabel,
                    onRestart = viewModel::restart,
                )

                showTerminal -> AndroidView(
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
                        val attached = viewModel.activeTerminalSession()
                        if (attached != null && view.currentSession !== attached) {
                            view.attachSession(attached)
                            view.setTerminalCursorBlinkerState(true, true)
                        } else if (attached == null && view.currentSession != null) {
                            // The session this view was showing is gone; detaching stops a stale
                            // emulator from being drawn as if it were still the terminal.
                            view.attachSession(null)
                        }
                    },
                )
            }
        }

        // An unavailable bootstrap is shown before the workspace note: it explains why the
        // Install button is missing, and it is the one the user has to act on.
        val note = state.bootstrapNote ?: state.workspaceNote ?: state.prefixNote
        if (note != null) {
            NoteStrip(
                text = note,
                tone = if (state.workspaceNote != null || state.bootstrapNote != null) ForgeAmber else ForgeMuted,
            )
        }

        // A stopped process is a state the user must be able to leave, so the way out is next to
        // the reason it stopped rather than only in the icon row. When the terminal itself could
        // not be drawn, RecoveryNotice above already carries both, so this would only repeat it.
        if (showTerminal) {
            state.exitLine?.let { line ->
                ExitedBanner(line = line, label = state.restartLabel, onRestart = viewModel::restart)
            }
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
            // Enabled only while a process is attached: after an exit there is nothing to type
            // into, and offering the keyboard would imply otherwise.
            IconButton(onClick = onShowKeyboard, enabled = state.canType) {
                Icon(Icons.Filled.Keyboard, contentDescription = "Show keyboard")
            }
            IconButton(onClick = viewModel::zoomOut) { Icon(Icons.Filled.Remove, contentDescription = "Smaller text") }
            IconButton(onClick = viewModel::zoomIn) { Icon(Icons.Filled.Add, contentDescription = "Larger text") }
            IconButton(
                onClick = viewModel::openSecondaryShell,
                enabled = !state.unavailable,
            ) { Icon(Icons.Filled.Add, contentDescription = "New") }
            // Deliberately not gated on an active handle: after a failed start there may be no
            // session at all, and that is exactly when this action has to work.
            IconButton(
                onClick = viewModel::restart,
                enabled = !state.unavailable,
            ) { Icon(Icons.Filled.Refresh, contentDescription = state.restartLabel) }
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

/**
 * The line and the way out for a process that has stopped.
 *
 * The status is spelled out ("exited with status 1 (a startup failure…)") rather than shown as a
 * bare number, and the process's own output is directly above this strip in the terminal buffer.
 */
@Composable
private fun ExitedBanner(line: String, label: String, onRestart: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ForgeSurfaceVariant)
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = line,
            style = TerminalMetaStyle.copy(color = ForgeDanger),
            maxLines = 3,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = label,
            style = TerminalMetaStyle.copy(color = ForgeMint),
            modifier = Modifier
                .background(ForgeCanvas)
                .clickable { onRestart() }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun ProvisioningBanner(state: TerminalUiState, onInstall: () -> Unit) {
    // The primary developer runtime owns the banner when it is wired in: it is the backend a
    // real shell comes from, and the legacy Termux bootstrap is only the fallback.
    if (state.developerRuntimeAvailable) {
        DeveloperRuntimeBanner(state = state, onInstall = onInstall)
        return
    }
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
            // Naming the stage is the difference between "install failed" and something the user
            // can act on: a checksum failure and a permission failure need different responses.
            val stage = provisioning.stage?.let { "Failed at ${it.wireName}: " }.orEmpty()
            NoteStrip(
                text = "$stage${provisioning.message} " +
                    "${com.agentx.app.ui.ide.state.installStageGuidance(provisioning.stage)} " +
                    "Tap Install to retry.",
                tone = ForgeDanger,
            )
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

/**
 * The first-run experience for the embedded Ubuntu runtime.
 *
 * Nothing is installed silently: the screen names what the runtime is for, shows the download,
 * verification and setup progress as they happen, and offers a single Install action. After a
 * successful install the shell is replaced automatically, so there is no bootstrap command to
 * type.
 */
@Composable
private fun DeveloperRuntimeBanner(state: TerminalUiState, onInstall: () -> Unit) {
    val status = state.developerRuntime
    when {
        status.state == AgentxRuntimeState.DOWNLOADING -> {
            Column(modifier = Modifier.fillMaxWidth().background(ForgeSurfaceVariant)) {
                LinearProgressIndicator(
                    progress = { status.progressPercent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "Downloading Ubuntu ARM64 rootfs \u00b7 ${status.progressPercent}%" +
                        if (status.totalBytes > 0) {
                            " (${status.installedBytes / (1024 * 1024)}/${status.totalBytes / (1024 * 1024)} MB)"
                        } else {
                            ""
                        },
                    style = TerminalMetaStyle.copy(color = ForgeMuted),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }

        status.isBusy -> {
            Column(modifier = Modifier.fillMaxWidth().background(ForgeSurfaceVariant)) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = when (status.state) {
                        AgentxRuntimeState.VERIFYING -> "Verifying SHA-256\u2026"
                        AgentxRuntimeState.EXTRACTING -> "Extracting Ubuntu rootfs\u2026"
                        AgentxRuntimeState.INSTALLING -> "Configuring the developer runtime\u2026"
                        AgentxRuntimeState.VALIDATING -> "Verifying the Ubuntu guest through PRoot\u2026"
                        else -> "Preparing the developer runtime\u2026"
                    },
                    style = TerminalMetaStyle.copy(color = ForgeMuted),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }

        status.state == AgentxRuntimeState.ERROR -> {
            val stage = status.stage?.let { "Failed at ${it.wireName}: " }.orEmpty()
            NoteStrip(
                text = "$stage${status.message.orEmpty()} " +
                    "${developerRuntimeStageGuidance(status.stage)} Tap Install Runtime to retry.",
                tone = ForgeDanger,
            )
        }

        state.developerRuntimeNeedsInstall -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ForgeSurfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text("AgentX Developer Runtime", style = MaterialTheme.typography.labelLarge, color = ForgeInk)
                Text("Ubuntu ARM64", style = TerminalMetaStyle.copy(color = ForgeMint))
                Text(
                    "Required for Terminal, Git, Python, Node and local development.",
                    style = TerminalMetaStyle.copy(color = ForgeMuted),
                )
                TextButton(onClick = onInstall) {
                    Icon(Icons.Filled.Download, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Install Runtime")
                }
            }
        }
    }
}

/**
 * Shown in place of the terminal when there is no emulator to draw.
 *
 * This is the difference between a terminal that failed and a terminal that is broken beyond use:
 * the reason is on screen and the button that rebuilds the session is right under it, so the screen
 * is never a blank panel with a dead keyboard.
 */
@Composable
private fun RecoveryNotice(
    heading: String,
    detail: String,
    facts: List<String>,
    diagnostics: String?,
    label: String,
    onRestart: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            text = heading,
            style = MaterialTheme.typography.titleSmall,
            color = ForgeDanger,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = detail,
            style = TerminalMetaStyle.copy(color = ForgeMuted),
        )

        // The technical detail is shown rather than only logged. A description of the symptom is
        // what made this hard to diagnose in the first place; the paths and the real error are what
        // say which part of the chain actually failed.
        if (facts.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(text = "Runtime", style = TerminalMetaStyle.copy(color = ForgeMuted))
            facts.forEach { fact ->
                Text(
                    text = fact,
                    style = TerminalMetaStyle.copy(color = ForgeMuted),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                )
            }
        }
        if (!diagnostics.isNullOrBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(text = "Last events", style = TerminalMetaStyle.copy(color = ForgeMuted))
            diagnostics.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                Text(
                    text = line,
                    style = TerminalMetaStyle.copy(color = ForgeMuted),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            text = label,
            style = TerminalMetaStyle.copy(color = ForgeMint),
            modifier = Modifier
                .background(ForgeCanvas)
                .clickable { onRestart() }
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
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
    return clip.getItemAt(0).coerceToText(context).toString().takeIf { it.isNotEmpty() }
}

private fun showTerminalKeyboard(context: Context, view: TerminalView) {
    view.post {
        view.requestFocus()
        val keyboard = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        keyboard?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }
}
