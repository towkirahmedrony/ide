package com.agentx.app.workspace.process

import com.agentx.app.workspace.ProcessEnvironment
import com.agentx.app.workspace.ProcessExecutor
import com.agentx.app.workspace.ProcessHandle
import com.agentx.app.workspace.ProcessOutput
import com.agentx.app.workspace.ProcessRequest
import com.agentx.app.workspace.ProcessResult
import com.agentx.app.workspace.ProcessState
import com.agentx.app.workspace.WorkspaceError
import com.agentx.app.workspace.WorkspaceErrorCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Starts OS processes. Shared by the human terminal, and later by agent
 * command tools, build, and test runners.
 *
 * Does not inherit the parent environment unless asked, and never copies
 * names that look like secrets.
 */
interface ProcessRuntime {
    fun start(spec: ProcessSpec): StartedProcess
}

data class ProcessSpec(
    val executable: String,
    val arguments: List<String> = emptyList(),
    val workingDirectory: String? = null,
    val environment: ProcessEnvironment = ProcessEnvironment(),
    val extraEnvironment: Map<String, String> = emptyMap(),
    val redirectErrorStream: Boolean = false,
)

interface StartedProcess {
    val alive: Boolean

    fun write(bytes: ByteArray)

    fun writeUtf8(text: String) = write(text.toByteArray(Charsets.UTF_8))

    fun closeStdin()

    fun sendSignal(signal: ProcessSignal)

    fun destroy(force: Boolean = false)

    fun waitFor(): Int

    fun waitFor(timeoutMillis: Long): Int?

    fun readStdout(buffer: ByteArray): Int

    fun readStderr(buffer: ByteArray): Int

    val stdout: InputStream

    val stderr: InputStream
}

enum class ProcessSignal {
    INTERRUPT,
    EOF,
    TERMINATE,
    KILL,
}

/**
 * [ProcessRuntime] backed by [ProcessBuilder]. Works on the JVM and on a
 * normal non-root Android device.
 */
class JvmProcessRuntime : ProcessRuntime {

    override fun start(spec: ProcessSpec): StartedProcess {
        val command = ArrayList<String>(1 + spec.arguments.size)
        command += spec.executable
        command += spec.arguments
        val builder = ProcessBuilder(command)
        builder.redirectErrorStream(spec.redirectErrorStream)
        val working = spec.workingDirectory?.let(::File)
        if (working != null) {
            if (!working.isDirectory) {
                throw ProcessStartException(
                    WorkspaceError(
                        code = WorkspaceErrorCode.INVALID_WORKING_DIRECTORY,
                        message = "Working directory is not a directory: ${working.path}",
                        path = working.path,
                    ),
                )
            }
            builder.directory(working)
        }
        val env = builder.environment()
        if (!spec.environment.inheritParent) {
            env.clear()
        } else {
            env.keys.filter { ProcessEnvironmentBuilder.looksSecret(it) }.forEach(env::remove)
        }
        env.putAll(
            ProcessEnvironmentBuilder.build(
                environment = spec.environment,
                workingDirectory = spec.workingDirectory,
                extra = spec.extraEnvironment,
            ),
        )
        val process = try {
            builder.start()
        } catch (io: IOException) {
            throw ProcessStartException(
                WorkspaceError(
                    code = WorkspaceErrorCode.PROCESS_FAILED,
                    message = io.message ?: "Failed to start ${spec.executable}",
                    cause = io,
                ),
            )
        }
        return JvmStartedProcess(process)
    }
}

class ProcessStartException(val error: WorkspaceError) : RuntimeException(error.message, error.cause)

private class JvmStartedProcess(
    private val process: Process,
) : StartedProcess {

    private val stdinClosed = AtomicBoolean(false)

    override val alive: Boolean get() = process.isAlive

    override val stdout: InputStream get() = process.inputStream

    override val stderr: InputStream get() = process.errorStream

    override fun write(bytes: ByteArray) {
        if (stdinClosed.get() || !process.isAlive) return
        try {
            val out = process.outputStream
            out.write(bytes)
            out.flush()
        } catch (_: IOException) {
            // Broken pipe: the process closed stdin. Callers treat this as
            // stream failure in the session, not as an app crash.
        }
    }

    override fun closeStdin() {
        if (!stdinClosed.compareAndSet(false, true)) return
        runCatching { process.outputStream.close() }
    }

    override fun sendSignal(signal: ProcessSignal) {
        when (signal) {
            ProcessSignal.INTERRUPT -> sendInterrupt()
            ProcessSignal.EOF -> closeStdin()
            ProcessSignal.TERMINATE -> destroy(force = false)
            ProcessSignal.KILL -> destroy(force = true)
        }
    }

    override fun destroy(force: Boolean) {
        if (force) {
            process.destroyForcibly()
        } else {
            process.destroy()
        }
    }

    override fun waitFor(): Int = process.waitFor()

    override fun waitFor(timeoutMillis: Long): Int? {
        val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        return if (finished) process.exitValue() else null
    }

    override fun readStdout(buffer: ByteArray): Int = readStream(process.inputStream, buffer)

    override fun readStderr(buffer: ByteArray): Int = readStream(process.errorStream, buffer)

    private fun sendInterrupt() {
        if (!process.isAlive) return
        write(byteArrayOf(0x03))
        val pid = pidOrNull() ?: return
        runCatching {
            ProcessBuilder("kill", "-INT", pid.toString()).start().waitFor()
        }
    }

    private fun pidOrNull(): Long? = try {
        process.pid()
    } catch (_: Throwable) {
        null
    }

    private fun readStream(stream: InputStream, buffer: ByteArray): Int = try {
        stream.read(buffer)
    } catch (_: IOException) {
        -1
    }

}

/**
 * One-shot [ProcessExecutor] on top of [ProcessRuntime], used by the agent command tools, build
 * and test runners. The human terminal does not go through this path: it owns long-lived pty
 * sessions in `:termux-runtime`, which the Tool Router never reaches.
 */
class RuntimeProcessExecutor(
    private val runtime: ProcessRuntime,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val allowsArbitrary: Boolean = true,
) : ProcessExecutor {

    override val allowsArbitraryExecution: Boolean = allowsArbitrary

    override suspend fun execute(
        request: ProcessRequest,
        onOutput: ((ProcessOutput) -> Unit)?,
    ): ProcessResult {
        if (request.command.isBlank()) {
            return ProcessResult(
                request = request,
                state = ProcessState.FAILED,
                error = WorkspaceError(
                    code = WorkspaceErrorCode.PROCESS_FAILED,
                    message = "No command was provided.",
                ),
            )
        }
        val spec = ProcessSpec(
            executable = request.command,
            arguments = request.arguments,
            workingDirectory = request.workingDirectory,
            environment = request.environment,
        )
        val started = try {
            runtime.start(spec)
        } catch (start: ProcessStartException) {
            return ProcessResult(
                request = request,
                state = ProcessState.FAILED,
                error = start.error,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (cause: Exception) {
            return ProcessResult(
                request = request,
                state = ProcessState.FAILED,
                error = WorkspaceError(
                    code = WorkspaceErrorCode.PROCESS_FAILED,
                    message = cause.message ?: "Failed to start process.",
                    cause = cause,
                ),
            )
        }

        val handle = RuntimeProcessHandle(started, request, dispatcher, onOutput)
        val timeout = request.timeoutMillis
        return try {
            if (timeout != null && timeout > 0) {
                withTimeoutOrNull(timeout) { handle.await() } ?: handle.cancelAndFail("timed out")
            } else {
                handle.await()
            }
        } catch (cancelled: CancellationException) {
            handle.cancel()
            return handle.cancelAndFail("cancelled")
        }
    }
}

private class RuntimeProcessHandle(
    private val process: StartedProcess,
    private val request: ProcessRequest,
    private val dispatcher: CoroutineDispatcher,
    private val onOutput: ((ProcessOutput) -> Unit)?,
) : ProcessHandle {

    private val cancelled = AtomicBoolean(false)
    private val currentState = AtomicReference(ProcessState.RUNNING)

    override val state: ProcessState get() = currentState.get()

    override fun cancel() {
        if (!cancelled.compareAndSet(false, true)) return
        currentState.set(ProcessState.CANCELLED)
        process.destroy(force = true)
    }

    override suspend fun await(): ProcessResult = withContext(dispatcher) {
        coroutineScope {
            val stdout = async { drain(process.stdout, isStderr = false) }
            val stderr = async { drain(process.stderr, isStderr = true) }
            val exit = try {
                runInterruptible { process.waitFor() }
            } catch (cancelled: CancellationException) {
                process.destroy(force = true)
                val out = stdout.await()
                val err = stderr.await()
                currentState.set(ProcessState.CANCELLED)
                return@coroutineScope ProcessResult(
                    request = request,
                    state = ProcessState.CANCELLED,
                    output = ProcessOutput(stdout = out, stderr = err),
                    error = WorkspaceError(
                        code = WorkspaceErrorCode.PROCESS_FAILED,
                        message = "Process was cancelled.",
                        cause = cancelled,
                    ),
                )
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                process.destroy(force = true)
                val out = stdout.await()
                val err = stderr.await()
                currentState.set(ProcessState.CANCELLED)
                return@coroutineScope ProcessResult(
                    request = request,
                    state = ProcessState.CANCELLED,
                    output = ProcessOutput(stdout = out, stderr = err),
                    error = WorkspaceError(
                        code = WorkspaceErrorCode.PROCESS_FAILED,
                        message = "Process was interrupted.",
                        cause = interrupted,
                    ),
                )
            }
            val out = stdout.await()
            val err = stderr.await()
            if (cancelled.get()) {
                ProcessResult(
                    request = request,
                    state = ProcessState.CANCELLED,
                    output = ProcessOutput(stdout = out, stderr = err),
                    exitCode = exit,
                )
            } else {
                currentState.set(ProcessState.COMPLETED)
                ProcessResult(
                    request = request,
                    state = ProcessState.COMPLETED,
                    output = ProcessOutput(stdout = out, stderr = err),
                    exitCode = exit,
                )
            }
        }
    }

    suspend fun cancelAndFail(reason: String): ProcessResult {
        cancel()
        val output = runCatching { await() }.getOrNull()?.output ?: ProcessOutput()
        return ProcessResult(
            request = request,
            state = ProcessState.CANCELLED,
            output = output,
            error = WorkspaceError(
                code = WorkspaceErrorCode.PROCESS_FAILED,
                message = "Process $reason.",
            ),
        )
    }

    private suspend fun drain(stream: InputStream, isStderr: Boolean): String {
        val collected = StringBuilder()
        val buffer = ByteArray(4096)
        while (true) {
            val read = try {
                runInterruptible { stream.read(buffer) }
            } catch (_: CancellationException) {
                break
            } catch (_: IOException) {
                break
            }
            if (read < 0) break
            if (read == 0) continue
            val chunk = String(buffer, 0, read, Charsets.UTF_8)
            collected.append(chunk)
            onOutput?.invoke(
                if (isStderr) ProcessOutput(stderr = chunk) else ProcessOutput(stdout = chunk),
            )
        }
        return collected.toString()
    }
}
