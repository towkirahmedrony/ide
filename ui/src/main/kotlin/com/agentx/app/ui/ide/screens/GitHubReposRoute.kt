package com.agentx.app.ui.ide.screens

import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.agentx.app.integrations.connection.ConnectionType
import com.agentx.app.ui.ide.IdeDependencies
import com.agentx.app.ui.ide.nav.IdeDestinations
import com.agentx.app.ui.ide.state.GitHubReposViewModel
import com.agentx.app.ui.ide.state.IdeViewModelFactory

/** Adds the "all my GitHub repositories" page to the IDE navigation graph. */
fun addGitHubReposRoute(
    builder: NavGraphBuilder,
    dependencies: IdeDependencies,
    navController: NavController,
) {
    builder.composable(IdeDestinations.GITHUB_REPOS) {
        val reposViewModel: GitHubReposViewModel = viewModel(
            key = "github-repos",
            factory = IdeViewModelFactory { GitHubReposViewModel(dependencies.githubRepositories) },
        )
        GitHubReposScreen(
            viewModel = reposViewModel,
            onBack = { navController.popBackStack() },
            onOpenWorkspace = { workspaceId -> navController.navigate(IdeDestinations.workspace(workspaceId)) },
            onReconnect = {
                navController.navigate(IdeDestinations.serviceDetails(ConnectionType.GITHUB.name))
            },
        )
    }
}
