package com.agentx.app.ui.ide.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agentx.app.ui.ide.components.IdeCard
import com.agentx.app.ui.ide.components.IdeSpacer
import com.agentx.app.ui.ide.components.IdeSpacerW
import com.agentx.app.ui.ide.components.IdeStatusPill
import com.agentx.app.ui.ide.components.IdeTopBar
import com.agentx.app.ui.ide.data.GitHubRepoItem
import com.agentx.app.ui.ide.state.GitHubReposViewModel
import com.agentx.app.ui.ide.state.RepoVisibilityFilter
import com.agentx.app.ui.theme.ForgeAmber
import com.agentx.app.ui.theme.ForgeCanvas
import com.agentx.app.ui.theme.ForgeDanger
import com.agentx.app.ui.theme.ForgeInk
import com.agentx.app.ui.theme.ForgeMint
import com.agentx.app.ui.theme.ForgeMuted

/**
 * Every repository the connected GitHub account can access, with search and a
 * public/private filter. Opening one clones it into AgentX (or reuses an earlier
 * clone) and makes it the active project.
 */
@Composable
fun GitHubReposScreen(
    viewModel: GitHubReposViewModel,
    onBack: () -> Unit,
    onOpenWorkspace: (String) -> Unit,
    onReconnect: () -> Unit,
) {
    val openedId = viewModel.openedWorkspaceId
    LaunchedEffect(openedId) {
        if (!openedId.isNullOrBlank()) {
            viewModel.consumeOpened()
            onOpenWorkspace(openedId)
        }
    }

    val visible = viewModel.visible
    val subtitle = when {
        viewModel.loading && viewModel.repos.isEmpty() -> "Loading…"
        viewModel.repos.isEmpty() -> "Your GitHub account"
        else -> "${visible.size} of ${viewModel.repos.size}"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ForgeCanvas),
    ) {
        IdeTopBar(
            title = "Repositories",
            subtitle = subtitle,
            onBack = onBack,
            actions = {
                IconButton(onClick = viewModel::refresh, enabled = !viewModel.loading) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Refresh repositories",
                        tint = ForgeMuted,
                    )
                }
            },
        )

        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
            OutlinedTextField(
                value = viewModel.query,
                onValueChange = { viewModel.query = it },
                label = { Text("Search repositories") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            IdeSpacer(8)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RepoVisibilityFilter.entries.forEach { option ->
                    FilterChip(
                        selected = viewModel.filter == option,
                        onClick = { viewModel.filter = option },
                        label = { Text(option.label) },
                    )
                }
            }
            IdeSpacer(6)
            Text(
                text = "Opening a repository clones it into AgentX if it is not there yet.",
                style = MaterialTheme.typography.bodySmall,
                color = ForgeMuted,
            )
        }

        if (viewModel.loading && viewModel.repos.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = ForgeMint)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                viewModel.error?.let { message ->
                    item(key = "error") {
                        IdeCard {
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = ForgeDanger,
                            )
                            IdeSpacer(8)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (viewModel.needsReconnect) {
                                    Button(onClick = onReconnect) { Text("Open GitHub connection") }
                                }
                                OutlinedButton(onClick = viewModel::refresh, enabled = !viewModel.loading) {
                                    Text("Retry")
                                }
                            }
                        }
                    }
                }

                if (viewModel.error == null && viewModel.repos.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            text = "No repositories were found on this account.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ForgeMuted,
                        )
                    }
                } else if (visible.isEmpty() && viewModel.repos.isNotEmpty()) {
                    item(key = "no-match") {
                        Text(
                            text = "No repositories match your search.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ForgeMuted,
                        )
                    }
                }

                items(visible, key = { it.fullName }) { repo ->
                    RepoRow(
                        repo = repo,
                        opening = viewModel.openingName == repo.fullName,
                        enabled = viewModel.openingName == null,
                        onOpen = { viewModel.open(repo.fullName) },
                    )
                }

                if (viewModel.truncated) {
                    item(key = "truncated") {
                        Text(
                            text = "GitHub returned more repositories than this list can hold. " +
                                "Use search to narrow it down.",
                            style = MaterialTheme.typography.bodySmall,
                            color = ForgeAmber,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RepoRow(
    repo: GitHubRepoItem,
    opening: Boolean,
    enabled: Boolean,
    onOpen: () -> Unit,
) {
    IdeCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = repo.fullName,
                    style = MaterialTheme.typography.titleSmall,
                    color = ForgeInk,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IdeSpacer(4)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IdeStatusPill(
                        text = if (repo.isPrivate) "Private" else "Public",
                        color = if (repo.isPrivate) ForgeAmber else ForgeMint,
                    )
                    IdeSpacerW(8)
                    Text(
                        text = repo.defaultBranch,
                        style = MaterialTheme.typography.bodySmall,
                        color = ForgeMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IdeSpacerW(8)
            if (opening) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = ForgeMint,
                    strokeWidth = 2.dp,
                )
            } else {
                Button(onClick = onOpen, enabled = enabled) { Text("Open") }
            }
        }
    }
}
