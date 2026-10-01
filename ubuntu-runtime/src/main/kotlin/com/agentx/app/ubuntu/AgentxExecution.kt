package com.agentx.app.ubuntu

import java.io.InputStream

/**
 * A guest command that is running, one-shot or long-running.
 *
 * This is the execution seam the brief asks for: a caller can
 * [LocalUbuntuRuntime.execute]`(command, workingDirectory)` and stream `stdout`/`stderr`,
 * collect the exit code, or cancel, without knowing that PRoot is underneath. Future backends
 * (SSH, cloud, Colab) are expected to implement the same shape; the current backend is
 * [LocalUbuntuRuntime].
 *
 * A long-running process (`npm run dev`, `python3 -m http.server 8080`) is *not* destroyed when
 * a caller stops reading: it stays alive until [cancel] or the guest program exits.
 */
interface AgentxExecution {

    /** The guest command as it was requested. */
    val guestCommand: List<String>

    /** Guest working directory, if one was requested. */
    val workingDirectory: String?

    /** The underlying process, for callers that need the host pid. */
    val process: Process

    /** Guest stdout. Reading is the caller's responsibility; it is never drained here. */
    val stdout: InputStream

    /** Guest stderr. */
    val stderr: InputStream

    val isAlive: Boolean get() = process.isAlive

    /** Blocks until exit and returns the guest exit code. */
    fun awaitExit(): Int

    /** Terminates the process and its process group. Safe to call twice. */
    fun cancel()
}

/** A completed one-shot command. */
data class AgentxCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
) {
    val success: Boolean get() = exitCode == 0 && !timedOut
}

internal class ProcessAgentxExecution(
    override val guestCommand: List<String>,
    override val workingDirectory: String?,
    override val process: Process,
) : AgentxExecution {

    override val stdout: InputStream get() = process.inputStream
    override val stderr: InputStream get() = process.errorStream

    override fun awaitExit(): Int = process.waitFor()

    override fun cancel() {
        if (!process.isAlive) return
        // Best-effort teardown of the process this runtime started. The interactive path is
        // torn down by the pty's `TerminalSession.finishIfRunning()` (SIGKILL to the shell pid
        // plus pty close), which is the one the brief's process-group requirement names. This
        // path is for non-interactive agent commands.
        //
        // `Process.descendants()` is intentionally not used: it needs a newer API level than
        // this app's floor, and a build that silently drops it is worse than a documented
        // limitation. See docs/developer-runtime.md.
        process.destroy()
        if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
        }
    }
}

/** What the runtime can be told to run. Kept minimal on purpose. */
data class AgentxCommand(
    val command: List<String>,
    val workingDirectory: String? = null,
    /** Extra guest environment entries; credential-looking names are refused. */
    val environment: Map<String, String> = emptyMap(),
)
