package com.agentx.app.ui.ide.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.skills.SkillSource
import com.agentx.app.ui.ide.components.IdeCard
import com.agentx.app.ui.ide.components.IdeSectionLabel
import com.agentx.app.ui.ide.components.IdeSpacer
import com.agentx.app.ui.ide.components.IdeStatusPill
import com.agentx.app.ui.ide.components.IdeTopBar
import com.agentx.app.ui.ide.state.SkillSummary
import com.agentx.app.ui.theme.ForgeCanvas
import com.agentx.app.ui.theme.ForgeDanger
import com.agentx.app.ui.theme.ForgeInk
import com.agentx.app.ui.theme.ForgeMint
import com.agentx.app.ui.theme.ForgeMuted
import com.agentx.app.ui.theme.ForgePeriwinkle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings → Skills. Lists installed skills and offers import/reload. */
@Composable
fun SkillsScreen(
    skills: List<SkillSummary>,
    loading: Boolean,
    message: String?,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onImport: (String, String?) -> Unit,
    onReload: () -> Unit,
    onReset: () -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showImport by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
            }
            if (text.isNullOrBlank()) {
                importError = "Could not read the selected file."
            } else {
                importText = text
                importError = null
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ForgeCanvas,
        topBar = {
            IdeTopBar(
                title = "Skills",
                subtitle = "Installed & enabled",
                onBack = onBack,
                actions = {
                    TextButton(onClick = onReload) { Text("Reload", color = ForgeMint) }
                    TextButton(onClick = { showImport = true; importError = null }) { Text("Import") }
                },
            )
        },
    ) { padding ->
        if (loading && skills.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = ForgeMint)
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            message?.let { text ->
                IdeCard {
                    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = ForgeInk)
                }
            }
            if (skills.isEmpty()) {
                IdeCard {
                    Text(
                        text = "No skills are installed yet. Import a SKILL.md file to add one.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ForgeMuted,
                    )
                }
            }
            skills.forEach { skill ->
                SkillRow(
                    skill = skill,
                    onToggle = { enabled -> onToggle(skill.id, enabled) },
                    onOpen = { onOpen(skill.id) },
                )
            }
            IdeSpacer(8)
            OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
                Text("Reset enablement & assignments")
            }
            IdeSpacer(8)
        }
    }

    if (showImport) {
        AlertDialog(
            onDismissRequest = { showImport = false },
            title = { Text("Import a skill") },
            text = {
                Column {
                    Text(
                        text = "Paste a SKILL.md, or pick a file. The content is parsed as instructions and is never executed.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ForgeMuted,
                    )
                    IdeSpacer(8)
                    OutlinedTextField(
                        value = importText,
                        onValueChange = { importText = it; importError = null },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 6,
                        maxLines = 12,
                        label = { Text("SKILL.md content") },
                    )
                    importError?.let { error ->
                        IdeSpacer(6)
                        Text(text = error, style = MaterialTheme.typography.bodyMedium, color = ForgeDanger)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onImport(importText, null)
                        showImport = false
                        importText = ""
                    },
                    enabled = importText.isNotBlank(),
                ) { Text("Import") }
            },
            dismissButton = {
                TextButton(
                    onClick = { picker.launch(arrayOf("text/markdown", "text/plain", "*/*")) },
                ) { Text("Pick file") }
            },
        )
    }
}

@Composable
private fun SkillRow(skill: SkillSummary, onToggle: (Boolean) -> Unit, onOpen: () -> Unit) {
    IdeCard(modifier = Modifier.clickable(onClick = onOpen)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = skill.name, style = MaterialTheme.typography.titleMedium, color = ForgeInk)
                Text(
                    text = "${skill.source.label()} · ${if (skill.isGlobal) "all agents" else skill.roles.sorted().joinToString(", ")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ForgeMuted,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (!skill.valid) {
                IdeStatusPill(text = "Invalid", color = ForgeDanger)
                Spacer(Modifier.width(6.dp))
            }
            Switch(checked = skill.enabled, onCheckedChange = onToggle)
        }
        if (skill.description.isNotBlank()) {
            IdeSpacer(6)
            Text(text = skill.description, style = MaterialTheme.typography.bodyMedium, color = ForgeMuted)
        }
    }
}

/** Detail view for one skill: description, instructions, assignment and removal. */
@Composable
fun SkillDetailScreen(
    skill: SkillSummary?,
    onBack: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onToggleRole: (String) -> Unit,
    onSetGlobal: (Boolean) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ForgeCanvas,
        topBar = {
            IdeTopBar(
                title = skill?.name ?: "Skill",
                subtitle = skill?.source?.label(),
                onBack = onBack,
            )
        },
    ) { padding ->
        if (skill == null) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(text = "This skill is no longer installed.", color = ForgeMuted)
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            IdeCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        IdeSectionLabel("Enabled")
                        IdeSpacer(4)
                        Text(
                            text = "Only enabled skills are offered to their assigned agents.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ForgeMuted,
                        )
                    }
                    Switch(checked = skill.enabled, onCheckedChange = onToggle)
                }
            }

            IdeCard {
                IdeSectionLabel("Description")
                IdeSpacer(6)
                Text(
                    text = skill.description.ifBlank { "No description provided." },
                    style = MaterialTheme.typography.bodyMedium,
                    color = ForgeInk,
                )
                skill.version?.let { version ->
                    IdeSpacer(4)
                    Text(text = "Version $version", style = MaterialTheme.typography.labelSmall, color = ForgeMuted)
                }
                if (!skill.valid && skill.problems.isNotEmpty()) {
                    IdeSpacer(6)
                    skill.problems.forEach { problem ->
                        Text(text = "· $problem", style = MaterialTheme.typography.bodyMedium, color = ForgeDanger)
                    }
                }
            }

            IdeCard {
                IdeSectionLabel("Assigned agents")
                IdeSpacer(6)
                Text(
                    text = "Empty assignment means the skill applies to every agent.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ForgeMuted,
                )
                IdeSpacer(8)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IdeStatusPill(
                        text = "All agents",
                        color = if (skill.isGlobal) ForgeMint else ForgeMuted,
                        modifier = Modifier.clickable { onSetGlobal(true) },
                    )
                }
                IdeSpacer(6)
                AgentRole.entries.forEach { role ->
                    val assigned = role.name in skill.roles
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleRole(role.name) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IdeStatusPill(
                            text = role.name,
                            color = if (assigned) ForgePeriwinkle else ForgeMuted,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (assigned) "assigned" else "not assigned",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ForgeMuted,
                        )
                    }
                }
            }

            IdeCard {
                IdeSectionLabel("Instructions")
                IdeSpacer(6)
                Text(
                    text = "These instructions are injected as context only for the assigned agents, " +
                        "within the context budget.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ForgeMuted,
                )
                IdeSpacer(8)
                Text(
                    text = skill.instructions,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    color = ForgeInk,
                )
            }

            if (skill.removable) {
                Button(
                    onClick = onRemove,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Remove imported skill")
                }
            }
            IdeSpacer(8)
        }
    }
}

private fun SkillSource.label(): String = when (this) {
    SkillSource.BUILTIN -> "Built-in"
    SkillSource.WORKSPACE -> "Workspace"
    SkillSource.IMPORTED -> "Imported"
}
