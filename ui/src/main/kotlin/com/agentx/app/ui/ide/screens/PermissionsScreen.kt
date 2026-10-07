package com.agentx.app.ui.ide.screens

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.agentx.app.ui.ide.components.IdeCard
import com.agentx.app.ui.ide.components.IdeSectionLabel
import com.agentx.app.ui.ide.components.IdeSpacer
import com.agentx.app.ui.ide.components.IdeSpacerW
import com.agentx.app.ui.ide.components.IdeStatusPill
import com.agentx.app.ui.ide.components.IdeTopBar
import com.agentx.app.ui.ide.permissions.AgentPermission
import com.agentx.app.ui.ide.permissions.AgentPermissionState
import com.agentx.app.ui.ide.permissions.AndroidPermissionReader
import com.agentx.app.ui.ide.permissions.PermissionAction
import com.agentx.app.ui.ide.permissions.PermissionCategory
import com.agentx.app.ui.ide.permissions.PermissionSettingsTarget
import com.agentx.app.ui.ide.permissions.PermissionStatus
import com.agentx.app.ui.theme.ForgeAmber
import com.agentx.app.ui.theme.ForgeCanvas
import com.agentx.app.ui.theme.ForgeDanger
import com.agentx.app.ui.theme.ForgeInk
import com.agentx.app.ui.theme.ForgeMint
import com.agentx.app.ui.theme.ForgeMuted

/**
 * Settings → Permissions.
 *
 * Shows every Android permission and special access AgentX actually declares, with its real
 * status, the feature that needs it, and the one action that can grant it. The status is read
 * from the platform, never assumed, and is re-read whenever the screen resumes or an action
 * returns, so a grant made in Android Settings is reflected immediately.
 */
@Composable
fun PermissionsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val reader = remember(context) { AndroidPermissionReader(context) }

    // A counter, not the list itself, so a resume or an action re-reads the platform state.
    var refreshToken by remember(context) { mutableIntStateOf(0) }
    val states = remember(reader, refreshToken) { reader.snapshot() }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refreshToken++ }

    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { refreshToken++ }

    fun openSettings(target: PermissionSettingsTarget) {
        // Some devices do not expose every screen; try the best match, then the next, then the
        // app's own details page. A page that cannot be opened is skipped, never reported as done.
        reader.settingsIntents(target).forEach { intent ->
            try {
                settingsLauncher.launch(intent)
                return
            } catch (_: ActivityNotFoundException) {
                // Not on this device — fall through to the next screen.
            }
        }
    }

    fun request(permission: AgentPermission) {
        reader.markRequested(permission)
        try {
            permissionLauncher.launch(permission.androidName)
        } catch (_: ActivityNotFoundException) {
            // No handler for the request dialog: the system settings screen can still grant it.
            openSettings(permission.settingsTarget)
        }
    }

    // Re-read the real state whenever the screen returns to the foreground.
    val lifecycle = reader.lifecycle
    DisposableEffect(lifecycle) {
        val owner = lifecycle ?: return@DisposableEffect onDispose {}
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshToken++
        }
        owner.addObserver(observer)
        onDispose { owner.removeObserver(observer) }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ForgeCanvas,
        topBar = {
            IdeTopBar(
                title = "Permissions",
                subtitle = "Android access AgentX uses",
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IdeCard {
                IdeSectionLabel("How this works")
                IdeSpacer(6)
                Text(
                    text = "AgentX only asks for what its features use. Every access below is " +
                        "declared in the app's manifest and shown with the feature that needs it. " +
                        "Optional access can be denied without breaking the rest of the app.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ForgeMuted,
                )
            }

            PermissionCategory.entries.forEach { category ->
                val rows = states.filter { it.permission.category == category }
                if (rows.isEmpty()) return@forEach
                IdeSpacer(2)
                IdeSectionLabel(category.sectionTitle)
                Text(
                    text = category.sectionNote,
                    style = MaterialTheme.typography.labelSmall,
                    color = ForgeMuted,
                )
                rows.forEach { state ->
                    PermissionRow(
                        state = state,
                        onAction = { current ->
                            when (current.action) {
                                PermissionAction.NONE -> Unit
                                PermissionAction.REQUEST -> request(current.permission)
                                PermissionAction.OPEN_SETTINGS ->
                                    openSettings(current.permission.settingsTarget)
                            }
                        },
                    )
                }
            }

            IdeSpacer(2)
            IdeCard {
                IdeSectionLabel("Not Android permissions")
                IdeSpacer(6)
                Text(
                    text = "Model providers and services such as GitHub are authorized with your " +
                        "own account, not with an Android permission. Manage those under " +
                        "Settings → Connections.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ForgeMuted,
                )
            }
            IdeSpacer(8)
        }
    }
}

@Composable
private fun PermissionRow(
    state: AgentPermissionState,
    onAction: (AgentPermissionState) -> Unit,
) {
    IdeCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = state.permission.label,
                        style = MaterialTheme.typography.titleMedium,
                        color = ForgeInk,
                    )
                    if (state.permission.optional) {
                        IdeSpacerW(8)
                        IdeStatusPill(text = "Optional", color = ForgeAmber)
                    }
                }
                Text(
                    text = state.permission.feature,
                    style = MaterialTheme.typography.labelSmall,
                    color = ForgeMuted,
                )
            }
            IdeSpacerW(8)
            IdeStatusPill(text = statusLabel(state.status), color = statusColor(state.status))
        }

        IdeSpacer(8)
        Text(
            text = state.permission.purpose,
            style = MaterialTheme.typography.bodyMedium,
            color = ForgeMuted,
        )

        statusHint(state)?.let { hint ->
            IdeSpacer(6)
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = statusColor(state.status),
            )
        }

        if (state.action != PermissionAction.NONE) {
            IdeSpacer(10)
            Button(onClick = { onAction(state) }) {
                Text(actionLabel(state))
            }
        }
    }
}

private fun statusLabel(status: PermissionStatus): String = when (status) {
    PermissionStatus.GRANTED -> "Granted"
    PermissionStatus.DENIED -> "Denied"
    PermissionStatus.PERMANENTLY_DENIED -> "Don't ask again"
    PermissionStatus.NOT_APPLICABLE -> "Not applicable"
    PermissionStatus.UNAVAILABLE -> "Unavailable"
}

private fun statusColor(status: PermissionStatus): Color = when (status) {
    PermissionStatus.GRANTED -> ForgeMint
    PermissionStatus.DENIED, PermissionStatus.PERMANENTLY_DENIED -> ForgeDanger
    PermissionStatus.NOT_APPLICABLE -> ForgeMuted
    PermissionStatus.UNAVAILABLE -> ForgeAmber
}

private fun statusHint(state: AgentPermissionState): String? = when (state.status) {
    PermissionStatus.NOT_APPLICABLE -> "Not used on this Android version."
    PermissionStatus.PERMANENTLY_DENIED -> "Android will not ask again; grant it in Settings."
    PermissionStatus.UNAVAILABLE -> "Blocked by a device policy or the current profile."
    PermissionStatus.DENIED ->
        if (state.permission.category == PermissionCategory.SPECIAL) {
            "Grant it in Android Settings."
        } else {
            null
        }

    PermissionStatus.GRANTED -> null
}

private fun actionLabel(state: AgentPermissionState): String =
    if (state.action == PermissionAction.REQUEST) {
        "Grant"
    } else {
        when (state.permission.settingsTarget) {
            PermissionSettingsTarget.ALL_FILES -> "Open All files access"
            PermissionSettingsTarget.NOTIFICATIONS -> "Open notification settings"
            PermissionSettingsTarget.APP_DETAILS -> "Open app settings"
        }
    }
