package com.agentx.app.ui.ide.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.agentx.app.core.architecture.LayerDescriptor
import com.agentx.app.core.health.HealthReport
import com.agentx.app.ui.FoundationScreen
import com.agentx.app.ui.ide.components.IdeCard
import com.agentx.app.ui.ide.components.IdeEmptyState
import com.agentx.app.ui.ide.components.IdeLabelValue
import com.agentx.app.ui.ide.components.IdeSectionLabel
import com.agentx.app.ui.ide.components.IdeSpacer
import com.agentx.app.ui.ide.components.IdeTopBar
import com.agentx.app.ui.theme.ForgeCanvas
import com.agentx.app.ui.theme.ForgeInk
import com.agentx.app.ui.theme.ForgeMint
import com.agentx.app.ui.theme.ForgeMuted

@Composable
fun AboutScreen(
    appName: String,
    version: String,
    onBack: () -> Unit,
    onOpenDeveloper: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ForgeCanvas,
        topBar = { IdeTopBar(title = "About", onBack = onBack) },
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
                Text(
                    text = appName.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                IdeSpacer(6)
                Text(
                    text = appName,
                    style = MaterialTheme.typography.headlineLarge,
                    color = ForgeInk,
                )
                IdeSpacer(4)
                Text(
                    text = "Android-first, agentic AI IDE.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ForgeMuted,
                )
                IdeSpacer(12)
                IdeLabelValue(label = "Version", value = version)
                IdeLabelValue(label = "Status", value = "Early development")
            }

            IdeCard {
                IdeSectionLabel("Architecture")
                IdeSpacer(6)
                Text(
                    text = "UI → Agent Core → Model Gateway → Tool Bus → Execution Backend. " +
                        "The current build wires the shell on top of contract-only layers.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ForgeMuted,
                )
            }

            Button(onClick = onOpenDeveloper) {
                Icon(Icons.Filled.Insights, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Developer information")
            }
        }
    }
}

@Composable
fun DeveloperScreen(
    layers: List<LayerDescriptor>,
    health: HealthReport?,
    onBack: () -> Unit,
    onOpenLogs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ForgeCanvas,
        topBar = {
            IdeTopBar(
                title = "Developer",
                subtitle = "Foundation status",
                onBack = onBack,
                actions = {
                    TextButton(onClick = onOpenLogs) { Text("Logs", color = ForgeMint) }
                },
            )
        },
    ) { padding ->
        if (health == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                IdeEmptyState(
                    icon = Icons.Filled.Insights,
                    title = "Foundation status unavailable",
                    message = "Startup health information was not provided to the UI.",
                )
            }
        } else {
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                FoundationScreen(layers = layers, health = health)
            }
        }
    }
}
