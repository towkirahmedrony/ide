package com.agentx.app.ui.ide.screens

import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.agentx.app.model.runtime.ModelLifecycleState
import com.agentx.app.model.runtime.ModelRuntimeStatus
import com.agentx.app.ui.ide.components.IdeCard
import com.agentx.app.ui.ide.components.IdeDot
import com.agentx.app.ui.ide.components.IdeEmptyState
import com.agentx.app.ui.ide.components.IdeLabelValue
import com.agentx.app.ui.ide.components.IdeSectionLabel
import com.agentx.app.ui.ide.components.IdeSpacer
import com.agentx.app.ui.ide.components.IdeStatusPill
import com.agentx.app.ui.ide.components.IdeTopBar
import com.agentx.app.ui.theme.ForgeAmber
import com.agentx.app.ui.theme.ForgeCanvas
import com.agentx.app.ui.theme.ForgeDanger
import com.agentx.app.ui.theme.ForgeInk
import com.agentx.app.ui.theme.ForgeMint
import com.agentx.app.ui.theme.ForgeMuted
import com.agentx.app.ui.theme.ForgePeriwinkle

/**
 * Model Runner: a focused browser for the notebook that hosts the runtime, plus
 * the connection state of the model it serves.
 *
 * Two facts this screen states plainly instead of hiding:
 * 1. The browser is a control surface. The agent reaches the model through the
 *    detected endpoint, never through this WebView, so closing it does not stop
 *    the model.
 * 2. Google alone controls how long a Colab runtime lives. Android cannot keep it
 *    alive, so "the runtime is gone" is a normal state with a clear next step.
 *
 * Navigation is restricted to the notebook and Google sign-in hosts by the host
 * implementation; a blocked attempt is reported here rather than failing silently.
 */
@Composable
fun ModelRunnerScreen(
    presetName: String,
    notebookUrl: String,
    status: ModelRuntimeStatus,
    browserAvailable: Boolean,
    browserFallbackTitle: String,
    browserFallbackMessage: String,
    externalActionLabel: String?,
    onOpenExternally: (() -> Unit)?,
    blockedHost: String?,
    capturedLines: Int,
    busy: Boolean,
    message: String?,
    onBack: () -> Unit,
    onReconnect: () -> Unit,
    onCheck: () -> Unit,
    onStop: () -> Unit,
    onCaptureOutput: (String) -> Unit,
    onClearOutput: () -> Unit,
    onDismissBlocked: () -> Unit,
    onDismissMessage: () -> Unit,
    onCreateView: () -> WebView?,
    onSessionDetached: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var captureOpen by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }

    // The session flag is bookkeeping for the UI only; it never gates the model.
    DisposableEffect(presetName) {
        onDispose { onSessionDetached() }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ForgeCanvas,
        topBar = {
            IdeTopBar(
                title = presetName,
                subtitle = "Model Runner",
                onBack = onBack,
                actions = {
                    IconButton(onClick = onCheck) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Check the model connection")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                IdeCard {
                    Row {
                        IdeDot(statusColor(status.state))
                        Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(
                                text = notebookUrl,
                                style = MaterialTheme.typography.labelSmall,
                                color = ForgeMuted,
                            )
                            Text(
                                text = status.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (status.state == ModelLifecycleState.FAILED) {
                                    ForgeDanger
                                } else {
                                    ForgeInk
                                },
                            )
                        }
                        IdeStatusPill(status.state.displayName, statusColor(status.state))
                    }
                    IdeSpacer(8)
                    IdeLabelValue("Endpoint", status.endpoint?.url ?: "not detected yet")
                    IdeLabelValue("Connection", "used by the agent directly, not through this browser")

                    IdeSpacer(8)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onReconnect, enabled = !busy) { Text("Reconnect") }
                        OutlinedButton(onClick = onCheck, enabled = !busy) { Text("Check") }
                        OutlinedButton(onClick = onStop, enabled = !busy) { Text("Stop") }
                    }
                }

                IdeCard {
                    IdeSectionLabel("How this works")
                    IdeSpacer(6)
                    Text(
                        text = "This browser only manages the runtime. The agent talks to the model " +
                            "API endpoint above, so leaving this screen does not disconnect the model.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ForgeMuted,
                    )
                    IdeSpacer(6)
                    Text(
                        text = "Google decides how long a Colab runtime lives. Android cannot start it, " +
                            "keep it alive, or restart it — if it stops, the model is reported as stopped " +
                            "instead of pretending to be online.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ForgeAmber,
                    )
                }

                if (blockedHost != null) {
                    IdeCard {
                        Row {
                            Text(
                                text = "Blocked navigation to $blockedHost. The Model Runner only opens " +
                                    "your notebook and Google sign-in pages.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = ForgeAmber,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = onDismissBlocked) { Text("OK") }
                        }
                    }
                }

                IdeCard {
                    IdeSectionLabel("Runtime output")
                    IdeSpacer(6)
                    Text(
                        text = "$capturedLines captured line(s). Endpoint detection reads these lines; " +
                            "if your notebook prints the tunnel URL and the app has not seen it, paste the " +
                            "output below.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ForgeMuted,
                    )
                    IdeSpacer(8)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { captureOpen = true }) { Text("Paste output") }
                        OutlinedButton(onClick = onClearOutput) { Text("Clear") }
                    }
                }
            }

            IdeSpacer(2)

            if (browserAvailable) {
                AndroidView(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    factory = { context ->
                        val view = onCreateView()
                        if (view == null) {
                            android.widget.TextView(context).apply { text = "No browser available" }
                        } else {
                            // Re-attaching a retained WebView must not leave it parented.
                            (view.parent as? ViewGroup)?.removeView(view)
                            view
                        }
                    },
                )
            } else {
                IdeEmptyState(
                    icon = Icons.Filled.Web,
                    title = browserFallbackTitle,
                    message = browserFallbackMessage,
                    actionLabel = externalActionLabel,
                    onAction = onOpenExternally,
                )
            }
        }
    }

    if (captureOpen) {
        AlertDialog(
            onDismissRequest = { captureOpen = false },
            title = { Text("Paste runtime output") },
            text = {
                Column {
                    Text(
                        text = "Paste the lines your runtime printed. A tunnel endpoint among them is " +
                            "detected and health-checked.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ForgeMuted,
                    )
                    Spacer(Modifier.size(8.dp))
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Runtime output") },
                        minLines = 5,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        captureOpen = false
                        onCaptureOutput(draft)
                        draft = ""
                    },
                ) { Text("Capture") }
            },
            dismissButton = { TextButton(onClick = { captureOpen = false }) { Text("Cancel") } },
        )
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = onDismissMessage,
            title = { Text("Model Runner") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = onDismissMessage) { Text("OK") } },
        )
    }
}

@Composable
private fun statusColor(state: ModelLifecycleState): Color = when (state) {
    ModelLifecycleState.ONLINE -> ForgeMint
    ModelLifecycleState.DEGRADED -> ForgeAmber
    ModelLifecycleState.STARTING,
    ModelLifecycleState.CONNECTING,
    ModelLifecycleState.CHECKING,
    ModelLifecycleState.STOPPING,
    -> ForgePeriwinkle

    ModelLifecycleState.FAILED -> ForgeDanger
    else -> ForgeMuted
}
