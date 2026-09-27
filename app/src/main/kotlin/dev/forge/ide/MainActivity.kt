package dev.forge.ide

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.forge.ide.agent.orchestrator.AgentOrchestrator
import dev.forge.ide.agent.ui.OrchestratorAgentSession
import dev.forge.ide.app.AndroidModelRunnerBrowserHost
import dev.forge.ide.app.rememberAndroidWorkspacePicker
import dev.forge.ide.core.foundation.ServiceKeys
import dev.forge.ide.foundation.Foundation
import dev.forge.ide.model.ModelConfig
import dev.forge.ide.model.manager.ModelManager
import dev.forge.ide.model.android.KeystoreModelSecretStore
import dev.forge.ide.model.android.SharedPreferencesModelPresetStore
import dev.forge.ide.model.runtime.RuntimeOutputBuffer
import dev.forge.ide.ui.ide.ForgeIdeApp
import dev.forge.ide.ui.ide.IdeDependencies
import dev.forge.ide.ui.ide.data.mock.MockGitRepository
import dev.forge.ide.ui.ide.data.mock.MockTerminalSession
import dev.forge.ide.ui.theme.ForgeTheme
import dev.forge.ide.workspace.DefaultWorkspaceManager
import dev.forge.ide.workspace.android.SafWorkspaceBackend
import dev.forge.ide.workspace.android.SharedPreferencesWorkspaceMetadataStore

class MainActivity : ComponentActivity() {

    private lateinit var modelRunnerBrowser: AndroidModelRunnerBrowserHost

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Captured model-runtime output is shared by the Model Runner browser and
        // the manager's endpoint discovery.
        val runtimeOutput = RuntimeOutputBuffer()
        modelRunnerBrowser = AndroidModelRunnerBrowserHost(this, runtimeOutput)
        modelRunnerBrowser.restoreState(savedInstanceState?.getBundle(KEY_RUNNER_STATE))

        // The core foundation boots once: health + layers, model presets, runners
        // and the active connection. Its status is surfaced from Settings → About →
        // Developer information.
        val foundation = Foundation.boot(
            presetStore = SharedPreferencesModelPresetStore(applicationContext),
            secretStore = KeystoreModelSecretStore(applicationContext),
            runtimeOutput = runtimeOutput,
        )

        val modelManager = foundation.services.get<ModelManager>(ServiceKeys.MODEL_MANAGER)

        // Android cannot run foreground network monitoring forever, so the manager
        // is told when the app is actually visible and re-checks on return.
        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    modelManager?.onAppForeground()
                }

                override fun onStop(owner: LifecycleOwner) {
                    modelManager?.onAppBackground()
                }
            },
        )

        setContent {
            ForgeTheme {
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
                            // The agent always uses whatever model the Model Manager
                            // has online; it never learns where that model runs.
                            modelConfig = { modelManagerOrDefault(modelManager) },
                        ),
                        terminal = MockTerminalSession(),
                        git = MockGitRepository(),
                        modelManager = checkNotNull(modelManager) { "Model manager is not registered" },
                        modelRunnerBrowser = modelRunnerBrowser,
                        modelRuntimeOutput = runtimeOutput,
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val browserState = Bundle()
        modelRunnerBrowser.saveState(browserState)
        outState.putBundle(KEY_RUNNER_STATE, browserState)
    }

    override fun onDestroy() {
        // The WebView holds this activity, so it is always released here; its page
        // state was saved above and cookies survive in the WebView store.
        modelRunnerBrowser.release()
        super.onDestroy()
    }

    private companion object {
        const val KEY_RUNNER_STATE = "forge.modelRunner.state"
    }
}

/**
 * The configuration the agent should use.
 *
 * When no model is online the placeholder is intentionally invalid: the agent then
 * reports that no model is configured, instead of quietly calling an endpoint the
 * user never selected. Connecting a model is always an explicit user action.
 */
private fun modelManagerOrDefault(manager: ModelManager?): ModelConfig =
    manager?.activeConfig() ?: ModelConfig(providerId = "", baseUrl = "", model = "")
