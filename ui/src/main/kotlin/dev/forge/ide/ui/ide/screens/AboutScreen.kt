package dev.forge.ide.ui.ide.screens

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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.forge.ide.core.architecture.LayerDescriptor
import dev.forge.ide.core.health.HealthReport
import dev.forge.ide.ui.FoundationScreen
import dev.forge.ide.ui.ide.components.IdeCard
import dev.forge.ide.ui.ide.components.IdeEmptyState
import dev.forge.ide.ui.ide.components.IdeLabelValue
import dev.forge.ide.ui.ide.components.IdeSectionLabel
import dev.forge.ide.ui.ide.components.IdeSpacer
import dev.forge.ide.ui.ide.components.IdeTopBar
import dev.forge.ide.ui.theme.ForgeCanvas
import dev.forge.ide.ui.theme.ForgeInk
import dev.forge.ide.ui.theme.ForgeMuted

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
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ForgeCanvas,
        topBar = { IdeTopBar(title = "Developer", subtitle = "Foundation status", onBack = onBack) },
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
