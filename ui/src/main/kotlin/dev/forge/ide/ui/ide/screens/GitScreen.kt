package dev.forge.ide.ui.ide.screens

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.forge.ide.ui.ide.components.IdeCard
import dev.forge.ide.ui.ide.components.IdeEmptyState
import dev.forge.ide.ui.ide.components.IdeLabelValue
import dev.forge.ide.ui.ide.components.IdeSectionLabel
import dev.forge.ide.ui.ide.components.IdeSpacer
import dev.forge.ide.ui.ide.components.IdeStatusPill
import dev.forge.ide.ui.ide.model.GitChange
import dev.forge.ide.ui.ide.state.GitUiState
import dev.forge.ide.ui.ide.state.GitViewModel
import dev.forge.ide.ui.theme.ForgeAmber
import dev.forge.ide.ui.theme.ForgeCanvas
import dev.forge.ide.ui.theme.ForgeInk
import dev.forge.ide.ui.theme.ForgeMint
import dev.forge.ide.ui.theme.ForgeMuted
import dev.forge.ide.ui.theme.ForgePeriwinkle
import dev.forge.ide.ui.theme.ForgeSurfaceVariant

@Composable
fun GitScreen(
    viewModel: GitViewModel,
    modifier: Modifier = Modifier,
) {
    val state = viewModel.uiState

    when {
        state.loading -> Box(
            modifier = modifier.fillMaxSize().background(ForgeCanvas),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = ForgeMint)
        }

        state.error != null -> Box(
            modifier = modifier.fillMaxSize().background(ForgeCanvas),
            contentAlignment = Alignment.Center,
        ) {
            IdeEmptyState(
                icon = Icons.Filled.AccountTree,
                title = "Git unavailable",
                message = state.error,
                actionLabel = "Retry",
                onAction = viewModel::refresh,
            )
        }

        else -> {
            val snapshot = state.snapshot
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .background(ForgeCanvas)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Source control",
                        style = MaterialTheme.typography.titleMedium,
                        color = ForgeInk,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = viewModel::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Refresh")
                    }
                }

                if (snapshot == null || !snapshot.available) {
                    RepositoryUnavailableBanner()
                }

                IdeCard {
                    IdeSectionLabel("Branch")
                    IdeSpacer(6)
                    IdeLabelValue(label = "Current branch", value = snapshot?.branch ?: "—")
                }

                IdeCard {
                    IdeSectionLabel("Changed files")
                    IdeSpacer(6)
                    val changes = snapshot?.changes.orEmpty()
                    if (changes.isEmpty()) {
                        Text(
                            text = "No changes detected.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ForgeMuted,
                        )
                    } else {
                        changes.forEach { change -> GitChangeRow(change) }
                    }
                }

                IdeCard {
                    IdeSectionLabel("Diff")
                    IdeSpacer(6)
                    val diff = snapshot?.diff
                    if (diff.isNullOrBlank()) {
                        Text(
                            text = "No diff to show.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ForgeMuted,
                        )
                    } else {
                        Text(
                            text = diff,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = ForgeInk,
                            ),
                        )
                    }
                }

                IdeCard {
                    IdeSectionLabel("Commit")
                    IdeSpacer(8)
                    OutlinedTextField(
                        value = "",
                        onValueChange = {},
                        enabled = false,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Commit message") },
                    )
                    IdeSpacer(10)
                    Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                        Text("Commit")
                    }
                    IdeSpacer(6)
                    Text(
                        text = "Git operations are not implemented yet.",
                        style = MaterialTheme.typography.labelSmall,
                        color = ForgeMuted,
                    )
                }

                IdeSpacer(8)
            }
        }
    }
}

@Composable
private fun RepositoryUnavailableBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ForgeAmber.copy(alpha = 0.12f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Repository status is not available for this workspace. The Git layer will provide it in a later task.",
            style = MaterialTheme.typography.bodyMedium,
            color = ForgeInk,
        )
    }
}

@Composable
private fun GitChangeRow(change: GitChange) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = change.path,
            style = MaterialTheme.typography.bodyMedium,
            color = ForgeInk,
            modifier = Modifier.weight(1f),
        )
        IdeStatusPill(text = change.status, color = changeColor(change.status))
    }
}

private fun changeColor(status: String) = when (status) {
    "modified" -> ForgeAmber
    "added" -> ForgeMint
    "deleted" -> dev.forge.ide.ui.theme.ForgeDanger
    "untracked" -> ForgePeriwinkle
    else -> ForgeMuted
}
