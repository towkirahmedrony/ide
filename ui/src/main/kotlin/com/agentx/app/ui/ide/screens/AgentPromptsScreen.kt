package com.agentx.app.ui.ide.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.ui.ide.components.IdeCard
import com.agentx.app.ui.ide.components.IdeSpacer
import com.agentx.app.ui.ide.components.IdeStatusPill
import com.agentx.app.ui.ide.components.IdeTopBar
import com.agentx.app.ui.ide.state.AgentPromptSummary
import com.agentx.app.ui.theme.ForgeCanvas
import com.agentx.app.ui.theme.ForgeInk
import com.agentx.app.ui.theme.ForgeMint
import com.agentx.app.ui.theme.ForgeMuted
import com.agentx.app.ui.theme.ForgePeriwinkle

/**
 * Settings → Agents. Lists the Main agent and every sub-agent, showing whether
 * each currently uses the built-in default or a user override.
 */
@Composable
fun AgentPromptsScreen(
    agents: List<AgentPromptSummary>,
    loading: Boolean,
    onBack: () -> Unit,
    onOpen: (AgentRole) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ForgeCanvas,
        topBar = { IdeTopBar(title = "Agents", subtitle = "System prompts", onBack = onBack) },
    ) { padding ->
        if (loading && agents.isEmpty()) {
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
            IdeCard {
                Text(
                    text = "Each agent resolves its system prompt through one central manager. " +
                        "Edit a prompt to override its built-in default; reset to fall back to the default.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ForgeMuted,
                )
            }
            agents.forEach { agent ->
                AgentRow(agent = agent, onClick = { onOpen(agent.role) })
            }
            IdeSpacer(8)
        }
    }
}

@Composable
private fun AgentRow(agent: AgentPromptSummary, onClick: () -> Unit) {
    IdeCard(modifier = Modifier.clickable(onClick = onClick)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = agent.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = ForgeInk,
                    )
                    Text(
                        text = "Role · ${agent.role.name}",
                        style = MaterialTheme.typography.labelSmall,
                        color = ForgeMuted,
                    )
                }
                Spacer(Modifier.width(8.dp))
                IdeStatusPill(
                    text = if (agent.isCustom) "Custom" else "Default",
                    color = if (agent.isCustom) ForgePeriwinkle else ForgeMuted,
                )
            }
            IdeSpacer(6)
            Text(
                text = agent.description,
                style = MaterialTheme.typography.bodyMedium,
                color = ForgeMuted,
            )
        }
    }
}
