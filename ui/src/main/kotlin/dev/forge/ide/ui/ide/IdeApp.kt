package dev.forge.ide.ui.ide

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.forge.ide.core.architecture.LayerDescriptor
import dev.forge.ide.core.health.HealthReport
import dev.forge.ide.ui.ide.nav.IdeDestinations
import dev.forge.ide.ui.ide.screens.AboutScreen
import dev.forge.ide.ui.ide.screens.DeveloperScreen
import dev.forge.ide.ui.ide.screens.HomeScreen
import dev.forge.ide.ui.ide.screens.SettingsDetailScreen
import dev.forge.ide.ui.ide.screens.SettingsScreen
import dev.forge.ide.ui.ide.screens.SettingsSection
import dev.forge.ide.ui.ide.screens.WorkspaceShell
import dev.forge.ide.ui.ide.state.HomeViewModel
import dev.forge.ide.ui.ide.state.IdeViewModelFactory

/**
 * Root of the IDE shell. Owns the navigation graph and hands the swappable
 * data sources in [IdeDependencies] to each screen's state holder.
 */
@Composable
fun ForgeIdeApp(
    dependencies: IdeDependencies,
    modifier: Modifier = Modifier,
    appName: String = "Forge",
    version: String = "0.1.0",
    layers: List<LayerDescriptor> = emptyList(),
    health: HealthReport? = null,
) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = IdeDestinations.HOME,
        modifier = modifier,
    ) {
        composable(IdeDestinations.HOME) {
            val homeViewModel: HomeViewModel = viewModel(
                factory = IdeViewModelFactory { HomeViewModel(dependencies.projects) },
            )
            HomeScreen(
                appName = appName,
                viewModel = homeViewModel,
                onOpenWorkspace = { workspaceId ->
                    navController.navigate(IdeDestinations.workspace(workspaceId))
                },
                onOpenSettings = { navController.navigate(IdeDestinations.SETTINGS) },
            )
        }

        composable(
            route = IdeDestinations.WORKSPACE,
            arguments = listOf(navArgument(IdeDestinations.ARG_WORKSPACE_ID) { type = NavType.StringType }),
        ) { entry ->
            val workspaceId = entry.arguments?.getString(IdeDestinations.ARG_WORKSPACE_ID).orEmpty()
            WorkspaceShell(
                workspaceId = workspaceId,
                dependencies = dependencies,
                onExit = { navController.popBackStack() },
                onOpenSettings = { navController.navigate(IdeDestinations.SETTINGS) },
            )
        }

        composable(IdeDestinations.SETTINGS) {
            SettingsScreen(
                appName = appName,
                onBack = { navController.popBackStack() },
                onSelect = { section ->
                    val route = if (section == SettingsSection.ABOUT) {
                        IdeDestinations.ABOUT
                    } else {
                        IdeDestinations.settingsDetail(section.id)
                    }
                    navController.navigate(route)
                },
            )
        }

        composable(
            route = IdeDestinations.SETTINGS_DETAIL,
            arguments = listOf(navArgument(IdeDestinations.ARG_SECTION_ID) { type = NavType.StringType }),
        ) { entry ->
            val section = SettingsSection.fromId(
                entry.arguments?.getString(IdeDestinations.ARG_SECTION_ID),
            ) ?: SettingsSection.MODEL
            SettingsDetailScreen(section = section, onBack = { navController.popBackStack() })
        }

        composable(IdeDestinations.ABOUT) {
            AboutScreen(
                appName = appName,
                version = version,
                onBack = { navController.popBackStack() },
                onOpenDeveloper = { navController.navigate(IdeDestinations.DEVELOPER) },
            )
        }

        composable(IdeDestinations.DEVELOPER) {
            DeveloperScreen(
                layers = layers,
                health = health,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
