package dev.forge.ide

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import dev.forge.ide.agent.orchestrator.AgentOrchestrator
import dev.forge.ide.agent.ui.OrchestratorAgentSession
import dev.forge.ide.app.rememberAndroidWorkspacePicker
import dev.forge.ide.core.foundation.ServiceKeys
import dev.forge.ide.foundation.Foundation
import dev.forge.ide.model.ModelConfig
import dev.forge.ide.ui.ide.ForgeIdeApp
import dev.forge.ide.ui.ide.IdeDependencies
import dev.forge.ide.ui.ide.data.mock.MockGitRepository
import dev.forge.ide.ui.ide.data.mock.MockTerminalSession
import dev.forge.ide.ui.theme.ForgeTheme
import dev.forge.ide.workspace.DefaultWorkspaceManager
import dev.forge.ide.workspace.android.SafWorkspaceBackend
import dev.forge.ide.workspace.android.SharedPreferencesWorkspaceMetadataStore

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ForgeTheme {
                // The core foundation still boots (health + layers); its status
                // is surfaced from Settings → About → Developer information.
                val foundation = remember { Foundation.boot() }

                // Workspace access is real: the Storage Access Framework opens the
                // folder the user picks and only the minimum metadata is persisted.
                val workspacePicker = rememberAndroidWorkspacePicker()
                val dependencies = remember(workspacePicker, foundation) {
                    val orchestrator = foundation.services.get<AgentOrchestrator>(ServiceKeys.AGENT_ORCHESTRATOR)
                    IdeDependencies(
                        workspaceManager = DefaultWorkspaceManager(
                            backend = SafWorkspaceBackend(applicationContext),
                            store = SharedPreferencesWorkspaceMetadataStore(applicationContext),
                        ),
                        workspacePicker = workspacePicker,
                        agent = OrchestratorAgentSession(
                            orchestrator = checkNotNull(orchestrator) { "Agent orchestrator is not registered" },
                            modelConfig = {
                                val endpoint = foundation.config.modelGateway.endpoint
                                ModelConfig(
                                    providerId = "openai-compatible",
                                    baseUrl = endpoint ?: "http://127.0.0.1:11434/v1",
                                    model = "local-model",
                                )
                            },
                        ),
                        terminal = MockTerminalSession(),
                        git = MockGitRepository(),
                    )
                }

                ForgeIdeApp(
                    dependencies = dependencies,
                    appName = foundation.config.appName,
                    version = "0.1.0",
                    layers = foundation.layers,
                    health = foundation.health,
                )
            }
        }
    }
}
