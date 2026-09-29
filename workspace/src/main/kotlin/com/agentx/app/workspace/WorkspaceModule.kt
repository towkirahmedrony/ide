package com.agentx.app.workspace

import com.agentx.app.core.foundation.ServiceKeys
import com.agentx.app.core.module.ForgeModule
import com.agentx.app.core.module.ModuleContext
import com.agentx.app.workspace.process.JvmProcessRuntime
import com.agentx.app.workspace.process.ProcessRuntime
import com.agentx.app.workspace.process.RuntimeProcessExecutor

/**
 * Publishes the process runtime used by one-shot command execution.
 *
 * This is the model-facing path: agent command tools reach it only through the Tool Router with
 * COMMAND_EXECUTION, and [RuntimeProcessExecutor] is registered with
 * `allowsArbitrary = false` so it cannot be used to run an arbitrary binary by accident.
 *
 * The human terminal is not registered here. It is the embedded Termux runtime in
 * `:termux-runtime`, whose sessions are started by the user from the Terminal screen and never
 * by the model, so the two authorisation paths stay separate.
 */
class WorkspaceModule(
    private val runtime: ProcessRuntime = JvmProcessRuntime(),
) : ForgeModule {

    override val id: String = "workspace"

    override fun initialize(context: ModuleContext) {
        context.services.register(ServiceKeys.PROCESS_RUNTIME, runtime)
        context.services.register(
            ServiceKeys.PROCESS_EXECUTOR,
            RuntimeProcessExecutor(runtime, allowsArbitrary = false),
        )
    }
}
