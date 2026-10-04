package com.agentx.app.ui.ide.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentx.app.git.GitRepositoryState
import com.agentx.app.ui.ide.components.IdeCard
import com.agentx.app.ui.ide.components.IdeEmptyState
import com.agentx.app.ui.ide.components.IdeLabelValue
import com.agentx.app.ui.ide.components.IdeSectionLabel
import com.agentx.app.ui.ide.components.IdeSpacer
import com.agentx.app.ui.ide.components.IdeStatusPill
import com.agentx.app.ui.ide.model.GitBranchInfo
import com.agentx.app.ui.ide.model.GitChange
import com.agentx.app.ui.ide.model.GitLogInfo
import com.agentx.app.ui.ide.model.GitRemoteInfo
import com.agentx.app.ui.ide.state.GitUiState
import com.agentx.app.ui.ide.state.GitViewModel
import com.agentx.app.ui.theme.ForgeAmber
import com.agentx.app.ui.theme.ForgeCanvas
import com.agentx.app.ui.theme.ForgeDanger
import com.agentx.app.ui.theme.ForgeInk
import com.agentx.app.ui.theme.ForgeMint
import com.agentx.app.ui.theme.ForgeMuted
import com.agentx.app.ui.theme.ForgePeriwinkle

@Composable
fun GitScreen(
    viewModel: GitViewModel,
    modifier: Modifier = Modifier,
) {
    val state = viewModel.uiState

    Box(modifier = modifier.fillMaxSize().background(ForgeCanvas)) {
        when {
            state.loading -> CircularProgressIndicator(
                color = ForgeMint,
                modifier = Modifier.align(Alignment.Center),
            )

            state.error != null -> IdeEmptyState(
                icon = Icons.Filled.AccountTree,
                title = "Git unavailable",
                message = state.error,
                actionLabel = "Retry",
                onAction = viewModel::refresh,
                modifier = Modifier.align(Alignment.Center),
            )

            !state.available -> IdeEmptyState(
                icon = Icons.Filled.AccountTree,
                title = "Not a Git repository",
                message = state.repositoryMessage
                    ?: "This project is not a Git repository.",
                actionLabel = "Retry",
                onAction = viewModel::refresh,
                modifier = Modifier.align(Alignment.Center),
            )

            else -> GitContent(state = state, viewModel = viewModel)
        }
    }
}

@Composable
private fun GitContent(state: GitUiState, viewModel: GitViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
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
            if (state.busy) {
                CircularProgressIndicator(
                    color = ForgeMint,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
            }
            TextButton(onClick = viewModel::refresh, enabled = !state.busy) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Refresh")
            }
        }

        state.message?.let { message ->
            MessageStrip(message = message, onDismiss = viewModel::dismissMessage)
        }

        BranchCard(state = state, onSwitch = viewModel::checkout)

        ChangesCard(state = state, onStage = viewModel::stage)

        DiffCard(title = "Working tree diff", diff = state.workingDiff)
        if (state.stagedCount > 0) {
            DiffCard(title = "Staged diff", diff = state.stagedDiff)
        }

        if (state.hasRemote) {
            RemoteCard(state = state, onPull = viewModel::pull, onPush = viewModel::push)
        }

        CommitCard(state = state, viewModel = viewModel)

        if (state.log.isNotEmpty()) {
            LogCard(log = state.log)
        }

        IdeSpacer(8)
    }
}

@Composable
private fun MessageStrip(message: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ForgeMint.copy(alpha = 0.10f))
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.labelMedium,
            color = ForgeMint,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
}

@Composable
private fun BranchCard(state: GitUiState, onSwitch: (String) -> Unit) {
    IdeCard {
        IdeSectionLabel("Branch")
        IdeSpacer(6)
        IdeLabelValue(label = "Current", value = state.branch ?: detachedLabel(state.repositoryState))
        state.upstream?.let { IdeLabelValue(label = "Tracking", value = it) }
        if (state.ahead > 0 || state.behind > 0) {
            IdeLabelValue(label = "Ahead / behind", value = "${state.ahead} / ${state.behind}")
        }

        val switchable = state.branches.filter { !it.remote && !it.current }
        if (switchable.isNotEmpty()) {
            IdeSpacer(8)
            IdeSectionLabel("Switch branch")
            switchable.forEach { branch ->
                BranchRow(branch = branch, enabled = !state.busy, onSwitch = onSwitch)
            }
        }
    }
}

@Composable
private fun BranchRow(branch: GitBranchInfo, enabled: Boolean, onSwitch: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = branch.name,
            style = MaterialTheme.typography.bodyMedium,
            color = ForgeInk,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { onSwitch(branch.name) }, enabled = enabled) { Text("Switch") }
    }
}

@Composable
private fun ChangesCard(state: GitUiState, onStage: (String) -> Unit) {
    IdeCard {
        IdeSectionLabel("Changed files")
        IdeSpacer(6)
        if (state.changes.isEmpty()) {
            Text(
                text = "No changes detected.",
                style = MaterialTheme.typography.bodyMedium,
                color = ForgeMuted,
            )
            return@IdeCard
        }
        Text(
            text = "${state.stagedCount} staged · ${state.unstagedCount} unstaged",
            style = MaterialTheme.typography.labelSmall,
            color = ForgeMuted,
        )
        state.changes.forEach { change ->
            GitChangeRow(change = change, enabled = !state.busy, onStage = onStage)
        }
    }
}

@Composable
private fun GitChangeRow(change: GitChange, enabled: Boolean, onStage: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = change.path,
                style = MaterialTheme.typography.bodyMedium,
                color = ForgeInk,
            )
            change.originalPath?.let { original ->
                Text(
                    text = "from $original",
                    style = MaterialTheme.typography.labelSmall,
                    color = ForgeMuted,
                )
            }
            if (change.staged) {
                Text(
                    text = "staged",
                    style = MaterialTheme.typography.labelSmall,
                    color = ForgeMint,
                )
            }
        }
        IdeStatusPill(text = change.status, color = changeColor(change.status))
        if (change.unstaged) {
            Spacer(Modifier.width(6.dp))
            TextButton(onClick = { onStage(change.path) }, enabled = enabled) { Text("Stage") }
        }
    }
}

@Composable
private fun DiffCard(title: String, diff: String) {
    IdeCard {
        IdeSectionLabel(title)
        IdeSpacer(6)
        if (diff.isBlank()) {
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
}

@Composable
private fun RemoteCard(state: GitUiState, onPull: () -> Unit, onPush: () -> Unit) {
    IdeCard {
        IdeSectionLabel("Remote")
        IdeSpacer(6)
        state.remotes.forEach { remote -> RemoteRow(remote) }
        IdeSpacer(10)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onPull, enabled = !state.busy) { Text("Pull") }
            OutlinedButton(onClick = onPush, enabled = !state.busy) { Text("Push") }
        }
        if (!state.hasUpstream) {
            IdeSpacer(6)
            Text(
                text = "This branch has no upstream yet; configure one in the terminal to pull or push.",
                style = MaterialTheme.typography.labelSmall,
                color = ForgeMuted,
            )
        }
    }
}

@Composable
private fun RemoteRow(remote: GitRemoteInfo) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = remote.name,
            style = MaterialTheme.typography.bodyMedium,
            color = ForgeInk,
            modifier = Modifier.width(90.dp),
        )
        Text(
            text = remote.url,
            style = MaterialTheme.typography.bodyMedium,
            color = ForgeMuted,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CommitCard(state: GitUiState, viewModel: GitViewModel) {
    IdeCard {
        IdeSectionLabel("Commit")
        IdeSpacer(8)
        OutlinedTextField(
            value = state.commitMessage,
            onValueChange = viewModel::editCommitMessage,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Commit message") },
            isError = state.commitError != null,
        )
        state.commitError?.let { error ->
            IdeSpacer(4)
            Text(text = error, style = MaterialTheme.typography.labelSmall, color = ForgeDanger)
        }
        IdeSpacer(10)
        Button(
            onClick = viewModel::commit,
            enabled = !state.busy && state.commitMessage.isNotBlank() && state.stagedCount > 0,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.busy) "Working" else "Commit staged changes")
        }
        IdeSpacer(6)
        Text(
            text = if (state.stagedCount == 0) {
                "Nothing is staged yet. Stage a file above to include it in the commit."
            } else {
                "${state.stagedCount} staged file(s) will be committed. Unstaged changes are left alone."
            },
            style = MaterialTheme.typography.labelSmall,
            color = ForgeMuted,
        )
    }
}

@Composable
private fun LogCard(log: List<GitLogInfo>) {
    IdeCard {
        IdeSectionLabel("Recent commits")
        IdeSpacer(6)
        log.forEach { entry ->
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(
                    text = "${entry.shortHash}  ${entry.subject}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ForgeInk,
                )
                Text(
                    text = "${entry.author} · ${entry.date}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ForgeMuted,
                )
            }
        }
    }
}

private fun detachedLabel(state: GitRepositoryState?): String = when (state) {
    GitRepositoryState.NO_COMMITS -> "no commits yet"
    GitRepositoryState.DETACHED -> "detached HEAD"
    GitRepositoryState.BARE -> "bare repository"
    else -> "—"
}

private fun changeColor(status: String) = when (status) {
    "modified" -> ForgeAmber
    "added" -> ForgeMint
    "deleted" -> ForgeDanger
    "untracked" -> ForgePeriwinkle
    "renamed", "copied" -> ForgePeriwinkle
    "unmerged" -> ForgeDanger
    else -> ForgeMuted
}
