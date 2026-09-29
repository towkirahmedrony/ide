package com.agentx.app.workspace.process

import com.agentx.app.workspace.ProcessEnvironment
import com.agentx.app.workspace.WorkspaceError
import com.agentx.app.workspace.WorkspaceManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns interactive shell sessions so a recreated terminal screen does not
 * spawn a second process. Multiple sessions are supported; one is active.
 */
interface TerminalSessionManager {
    val active: InteractiveShellSession?

    fun get(id: TerminalSessionId): InteractiveShellSession?

    suspend fun sessionForWorkspace(
        workspaceId: String,
        request: ShellLaunchRequest,
    ): InteractiveShellSession

    suspend fun restart(id: TerminalSessionId, request: ShellLaunchRequest): WorkspaceError?

    fun close(id: TerminalSessionId)

    fun closeAll()
}

class DefaultTerminalSessionManager(
    private val runtime: ProcessRuntime,
    private val workspaceManager: WorkspaceManager? = null,
    private val shells: ShellFinder = ShellLocator,
) : TerminalSessionManager {

    private val sessions = ConcurrentHashMap<String, InteractiveShellSession>()
    private val byWorkspace = ConcurrentHashMap<String, TerminalSessionId>()
    private val mutex = Mutex()

    @Volatile
    override var active: InteractiveShellSession? = null
        private set

    override fun get(id: TerminalSessionId): InteractiveShellSession? = sessions[id.value]

    override suspend fun sessionForWorkspace(
        workspaceId: String,
        request: ShellLaunchRequest,
    ): InteractiveShellSession = mutex.withLock {
        val existingId = byWorkspace[workspaceId]
        val existing = existingId?.let { sessions[it.value] }
        if (existing != null && existing.state.value in setOf(
                TerminalSessionState.IDLE,
                TerminalSessionState.STARTING,
                TerminalSessionState.RUNNING,
            )
        ) {
            active = existing
            return@withLock existing
        }
        existing?.close()
        if (existingId != null) sessions.remove(existingId.value)
        val session = DefaultInteractiveShellSession(runtime = runtime, shells = shells)
        sessions[session.id.value] = session
        byWorkspace[workspaceId] = session.id
        active = session
        session.start(request)
        session
    }

    override suspend fun restart(id: TerminalSessionId, request: ShellLaunchRequest): WorkspaceError? {
        val session = sessions[id.value] ?: return null
        return session.restart(request)
    }

    override fun close(id: TerminalSessionId) {
        val session = sessions.remove(id.value)
        byWorkspace.entries.removeIf { it.value == id }
        if (active?.id == id) active = null
        session?.close()
    }

    override fun closeAll() {
        sessions.values.forEach { it.close() }
        sessions.clear()
        byWorkspace.clear()
        active = null
    }

    fun launchRequestForCurrentWorkspace(
        extraEnvironment: Map<String, String> = emptyMap(),
    ): ShellLaunchRequest {
        val current = workspaceManager?.current
        val metadata = current?.workspace?.metadata
        val location = WorkspaceShellLocations.resolve(
            handle = null,
            displayLocation = metadata?.displayLocation,
        )
        return ShellLaunchRequest(
            workingDirectory = (location as? WorkspaceShellLocation.Filesystem)?.path,
            workspaceLocation = location,
            environment = ProcessEnvironment(inheritParent = false),
            extraEnvironment = extraEnvironment,
        )
    }
}
