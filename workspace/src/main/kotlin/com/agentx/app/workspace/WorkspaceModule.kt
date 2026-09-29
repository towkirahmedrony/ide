package com.agentx.app.workspace

import com.agentx.app.core.foundation.ServiceKeys
import com.agentx.app.core.module.ForgeModule
import com.agentx.app.core.module.ModuleContext
import com.agentx.app.workspace.process.DefaultTerminalSessionManager
import com.agentx.app.workspace.process.JvmProcessRuntime
import com.agentx.app.workspace.process.ProcessRuntime
import com.agentx.app.workspace.process.RuntimeProcessExecutor
import com.agentx.app.workspace.process.TerminalSessionManager

/**
 * Publishes the process runtime used by the human terminal. Agent command
 * tools must still go through the Tool Router; this module never grants
 * them COMMAND_EXECUTION on its own.
 */
class WorkspaceModule(
    private val runtime: ProcessRuntime = JvmProcessRuntime(),
    private val workspaceManager: WorkspaceManager? = null,
) : ForgeModule {

    override val id: String = "workspace"

    override fun initialize(context: ModuleContext) {
        val sessions: TerminalSessionManager = DefaultTerminalSessionManager(
            runtime = runtime,
            workspaceManager = workspaceManager,
        )
        context.services.register(ServiceKeys.PROCESS_RUNTIME, runtime)
        context.services.register(ServiceKeys.TERMINAL_SESSION_MANAGER, sessions)
        context.services.register(
            ServiceKeys.PROCESS_EXECUTOR,
            RuntimeProcessExecutor(runtime, allowsArbitrary = false),
        )
    }
}
