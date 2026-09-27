package dev.forge.ide.workspace

/** Lifecycle of a process execution request. */
enum class ProcessState {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

/**
 * Environment for a process. Inheriting the parent environment is opt-in so a
 * future executor never leaks secrets to a command by accident.
 */
data class ProcessEnvironment(
    val variables: Map<String, String> = emptyMap(),
    val inheritParent: Boolean = false,
)

/**
 * A request to run a command inside a workspace. [workingDirectory] is
 * workspace-relative; cancellation is cooperative through the calling
 * coroutine.
 */
data class ProcessRequest(
    val command: String,
    val arguments: List<String> = emptyList(),
    val workingDirectory: String? = null,
    val environment: ProcessEnvironment = ProcessEnvironment(),
    val timeoutMillis: Long? = null,
)

/** Combined output of a process run. */
data class ProcessOutput(
    val stdout: String = "",
    val stderr: String = "",
)

/** Result of a process run. */
data class ProcessResult(
    val request: ProcessRequest,
    val state: ProcessState,
    val output: ProcessOutput = ProcessOutput(),
    val exitCode: Int? = null,
    val error: WorkspaceError? = null,
) {
    val isSuccess: Boolean get() = state == ProcessState.COMPLETED && exitCode == 0
}

/**
 * Handle to a running process, so callers can observe state or cancel it. The
 * default executors are suspending; a streaming implementation can expose a
 * handle alongside the coroutine running the command.
 */
interface ProcessHandle {
    val state: ProcessState

    suspend fun await(): ProcessResult

    fun cancel()
}

/**
 * Executes commands for a workspace.
 *
 * No real shell is connected yet: [allowsArbitraryExecution] is `false` for
 * every implementation and the security policy will be added in a later task.
 */
interface ProcessExecutor {

    /** Whether this executor may run arbitrary, unvetted commands. Always false for now. */
    val allowsArbitraryExecution: Boolean

    /**
     * Runs [request]. Implementations that can stream may invoke [onOutput] with
     * incremental output; cancellation is cooperative via the calling coroutine.
     */
    suspend fun execute(
        request: ProcessRequest,
        onOutput: ((ProcessOutput) -> Unit)? = null,
    ): ProcessResult
}

/**
 * Safe executor that never launches a real process. Used by tests and the UI to
 * exercise the abstraction without granting any execution capability.
 */
class StubProcessExecutor : ProcessExecutor {

    override val allowsArbitraryExecution: Boolean = false

    override suspend fun execute(
        request: ProcessRequest,
        onOutput: ((ProcessOutput) -> Unit)?,
    ): ProcessResult = ProcessResult(
        request = request,
        state = ProcessState.FAILED,
        output = ProcessOutput(),
        exitCode = null,
        error = WorkspaceError(
            code = WorkspaceErrorCode.PROCESS_EXECUTION_UNAVAILABLE,
            message = "Command execution is not enabled yet.",
        ),
    )
}
