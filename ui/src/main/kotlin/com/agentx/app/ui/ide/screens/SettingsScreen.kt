package com.agentx.app.ui.ide.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.agentx.app.ui.ide.components.IdeCard
import com.agentx.app.ui.ide.components.IdeSectionLabel
import com.agentx.app.ui.ide.components.IdeSpacer
import com.agentx.app.ui.ide.components.IdeTopBar
import com.agentx.app.ui.theme.ForgeCanvas
import com.agentx.app.ui.theme.ForgeInk
import com.agentx.app.ui.theme.ForgeMint
import com.agentx.app.ui.theme.ForgeMuted
import com.agentx.app.ui.theme.ForgePeriwinkle
import com.agentx.app.ui.theme.ForgeSurfaceVariant

/** Configuration areas surfaced by Settings. UI-only for now. */
enum class SettingsSection(
    val id: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
) {
    MODEL("model", "Models", "Saved models, runners and the active connection", Icons.Filled.Memory),
    CONNECTIONS("connections", "Connections", "External services the agent may use through tools", Icons.Filled.Hub),
    AGENT("agent", "Agents", "System prompts for the Main agent and each sub-agent", Icons.Filled.AutoAwesome),
    SKILLS("skills", "Skills", "Installed skills, enablement and agent assignment", Icons.Filled.Extension),
    TOOLS("tools", "Tools", "Enable or disable agent tools", Icons.Filled.Handyman),
    PERMISSIONS("permissions", "Permissions", "Allow, ask or deny per capability", Icons.Filled.Security),
    WORKSPACE("workspace", "Workspace", "Default workspace and runtime options", Icons.Filled.Folder),
    APPEARANCE("appearance", "Appearance", "Theme, editor font and layout", Icons.Filled.Palette),
    ABOUT("about", "About", "App version and developer information", Icons.Filled.Info);

    companion object {
        fun fromId(id: String?): SettingsSection? = entries.firstOrNull { it.id == id }
    }
}

@Composable
fun SettingsScreen(
    appName: String,
    onBack: () -> Unit,
    onSelect: (SettingsSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ForgeCanvas,
        topBar = { IdeTopBar(title = "Settings", subtitle = appName, onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsSection.entries.forEach { section ->
                SettingsRow(section = section, onClick = { onSelect(section) })
            }
            IdeSpacer(8)
        }
    }
}

@Composable
private fun SettingsRow(section: SettingsSection, onClick: () -> Unit) {
    IdeCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(ForgeSurfaceVariant, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(section.icon, contentDescription = null, tint = ForgePeriwinkle)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = section.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = ForgeInk,
                )
                Text(
                    text = section.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ForgeMuted,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = ForgeMuted,
            )
        }
    }
}

/** Placeholder detail for a settings section. */
@Composable
fun SettingsDetailScreen(
    section: SettingsSection,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ForgeCanvas,
        topBar = { IdeTopBar(title = section.title, onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            IdeCard {
                IdeSectionLabel("Coming soon")
                IdeSpacer(6)
                Text(
                    text = section.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ForgeInk,
                )
                IdeSpacer(8)
                Text(
                    text = "Configuration for this area will be added once the underlying layer is implemented.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ForgeMuted,
                )
            }
            IdeCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(ForgeMint, RoundedCornerShape(50)),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "No settings are active yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ForgeMuted,
                    )
                }
            }
        }
    }
}
