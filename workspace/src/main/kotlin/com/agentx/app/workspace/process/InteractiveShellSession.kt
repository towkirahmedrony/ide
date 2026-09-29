package com.agentx.app.workspace.process

import com.agentx.app.workspace.ProcessEnvironment
import com.agentx.app.workspace.WorkspaceError
import com.agentx.app.workspace.WorkspaceErrorCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Lifecycle of a long-lived interactive shell. */
enum class TerminalSessionState {
    IDLE,
    STARTING,
    RUNNING,
    STOPPED,
    EXITED,
    FAILED,
    CANCELLED,
}

data class TerminalSessionId(val value: String) {
    companion object {
        fun random(): TerminalSessionId = TerminalSessionId(UUID.randomUUID().toString())
    }
}

data class ShellLaunchRequest(
    val workingDirectory: String?,
    val workspaceLocation: WorkspaceShellLocation,
    val environment: ProcessEnvironment = ProcessEnvironment(),
    val extraEnvironment: Map<String, String> = emptyMap(),
    val shell: String? = null,
)

data class TerminalEvent(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val kind: ProcessStreamKind,
)

data class TerminalSessionSnapshot(
    val id: TerminalSessionId,
    val state: TerminalSessionState,
    val workingDirectory: String?,
    val workspaceLimitation: String?,
    val exitCode: Int?,
    val error: WorkspaceError?,
    val lines: List<TerminalEvent>,
    val droppedCount: Int,
)

/**
 * One interactive shell process. Commands are written to the same stdin;
 * cwd and exported variables persist until the process exits.
 */
interface InteractiveShellSession {
    val id: TerminalSessionId
    val state: StateFlow<TerminalSessionState>
    val events: SharedFlow<TerminalEvent>
    val workingDirectory: StateFlow<String?>
    val exitCode: Int?
    val error: WorkspaceError?
    val workspaceLimitation: String?

    fun snapshot(): TerminalSessionSnapshot

    suspend fun start(request: ShellLaunchRequest): WorkspaceError?

    fun submit(line: String)

    fun sendControl(signal: ProcessSignal)

    fun resizeHint(columns: Int, rows: Int) = Unit

    fun clearBuffer()

    suspend fun restart(request: ShellLaunchRequest): WorkspaceError?

    fun close()
}

class DefaultInteractiveShellSession(
    override val id: TerminalSessionId = TerminalSessionId.random(),
    private val runtime: ProcessRuntime,
    private val shells: ShellFinder = ShellLocator,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val buffer: TerminalBuffer = TerminalBuffer(),
) : InteractiveShellSession {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val stdinMutex = Mutex()
    private val closed = AtomicBoolean(false)

    private val stateFlow = MutableStateFlow(TerminalSessionState.IDLE)
    private val cwdFlow = MutableStateFlow<String?>(null)
    private val eventFlow = MutableSharedFlow<TerminalEvent>(
        replay = 64,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private var process: StartedProcess? = null
    private var stdoutJob: Job? = null
    private var stderrJob: Job? = null
    private var waitJob: Job? = null
    private var lastRequest: ShellLaunchRequest? = null
    private val generation = AtomicInteger(0)

    override val state: StateFlow<TerminalSessionState> = stateFlow.asStateFlow()
    override val events: SharedFlow<TerminalEvent> = eventFlow.asSharedFlow()
    override val workingDirectory: StateFlow<String?> = cwdFlow.asStateFlow()

    override var exitCode: Int? = null
        private set
    override var error: WorkspaceError? = null
        private set
    override var workspaceLimitation: String? = null
        private set

    override fun snapshot(): TerminalSessionSnapshot = TerminalSessionSnapshot(
        id = id,
        state = stateFlow.value,
        workingDirectory = cwdFlow.value,
        workspaceLimitation = workspaceLimitation,
        exitCode = exitCode,
        error = error,
        lines = buffer.snapshot().map { TerminalEvent(id = it.id, text = it.text, kind = it.kind) },
        droppedCount = buffer.droppedCount,
    )

    override suspend fun start(request: ShellLaunchRequest): WorkspaceError? {
        if (closed.get()) {
            return WorkspaceError(WorkspaceErrorCode.PROCESS_FAILED, "This terminal session is closed.")
        }
        if (stateFlow.value == TerminalSessionState.RUNNING || stateFlow.value == TerminalSessionState.STARTING) {
            return null
        }
        return launch(request)
    }

    override fun submit(line: String) {
        val current = process
        if (current == null || stateFlow.value != TerminalSessionState.RUNNING) {
            emit(system("shell is not running"))
            return
        }
        val payload = if (line.endsWith("\n")) line else "$line\n"
        scope.launch {
            stdinMutex.withLock {
                try {
                    current.writeUtf8(payload)
                } catch (_: Exception) {
                    emit(system("stdin is not writable"))
                }
            }
        }
        maybeTrackDirectory(line)
    }

    override fun sendControl(signal: ProcessSignal) {
        val current = process ?: return
        when (signal) {
            ProcessSignal.INTERRUPT -> {
                current.write(byteArrayOf(0x03))
                emit(system("^C"))
            }
            ProcessSignal.EOF -> {
                current.closeStdin()
                emit(system("^D"))
            }
            ProcessSignal.TERMINATE, ProcessSignal.KILL -> current.sendSignal(signal)
        }
    }

    override fun clearBuffer() {
        buffer.clear()
    }

    override suspend fun restart(request: ShellLaunchRequest): WorkspaceError? {
        stopProcess(TerminalSessionState.STOPPED)
        buffer.clear()
        exitCode = null
        error = null
        workspaceLimitation = null
        return launch(request)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stopProcess(TerminalSessionState.CANCELLED)
        scope.cancel()
    }

    private suspend fun launch(request: ShellLaunchRequest): WorkspaceError? {
        lastRequest = request
        stateFlow.value = TerminalSessionState.STARTING
        exitCode = null
        error = null

        val shell = request.shell ?: shells.find()
        if (shell == null) {
            val failure = WorkspaceError(
                code = WorkspaceErrorCode.SHELL_UNAVAILABLE,
                message = "No usable shell was found. Tried ${ShellLocator.DEFAULT_CANDIDATES.joinToString()}.",
            )
            fail(failure)
            return failure
        }

        val location = request.workspaceLocation
        val working = when (location) {
            is WorkspaceShellLocation.Filesystem -> usableDirectory(location.path) ?: fallbackDirectory()
            is WorkspaceShellLocation.Unavailable -> {
                workspaceLimitation = location.reason
                fallbackDirectory()
            }
        }
        if (location is WorkspaceShellLocation.Filesystem && usableDirectory(location.path) == null) {
            workspaceLimitation = "Working directory ${location.path} is not accessible; using $working."
        }
        cwdFlow.value = working

        val extra = LinkedHashMap<String, String>()
        extra["TERM"] = "dumb"
        extra["PS1"] = "$ "
        extra.putAll(request.extraEnvironment)
        extra["CODER_WORKSPACE"] = when (location) {
            is WorkspaceShellLocation.Filesystem -> location.path
            is WorkspaceShellLocation.Unavailable -> location.displayLocation
        }

        val spec = ProcessSpec(
            executable = shell,
            arguments = emptyList(),
            workingDirectory = working,
            environment = request.environment,
            extraEnvironment = extra,
        )
        val started = try {
            runtime.start(spec)
        } catch (start: ProcessStartException) {
            fail(start.error)
            return start.error
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (cause: Exception) {
            val failure = WorkspaceError(
                code = WorkspaceErrorCode.PROCESS_FAILED,
                message = cause.message ?: "Failed to start the shell.",
                cause = cause,
            )
            fail(failure)
            return failure
        }

        process = started
        val startedGeneration = generation.incrementAndGet()
        stateFlow.value = TerminalSessionState.RUNNING
        emit(system("shell $shell"))
        emit(system("cwd $working"))
        workspaceLimitation?.let { emit(system(it)) }
        startReaders(started)
        waitJob = scope.launch {
            val code = try {
                runInterruptible { started.waitFor() }
            } catch (_: CancellationException) {
                null
            } catch (_: InterruptedException) {
                null
            }
            onProcessExit(startedGeneration, code)
        }
        return null
    }

    private fun startReaders(started: StartedProcess) {
        stdoutJob = scope.launch { pump(started.stdout, ProcessStreamKind.STDOUT) }
        stderrJob = scope.launch { pump(started.stderr, ProcessStreamKind.STDERR) }
    }

    private suspend fun pump(stream: java.io.InputStream, kind: ProcessStreamKind) {
        val bufferBytes = ByteArray(4096)
        val leftover = StringBuilder()
        while (scope.isActive) {
            val read = try {
                runInterruptible { stream.read(bufferBytes) }
            } catch (_: CancellationException) {
                break
            } catch (_: IOException) {
                break
            }
            if (read < 0) break
            if (read == 0) continue
            leftover.append(String(bufferBytes, 0, read, Charsets.UTF_8))
            flushLines(leftover, kind, finish = false)
        }
        flushLines(leftover, kind, finish = true)
    }

    private fun flushLines(leftover: StringBuilder, kind: ProcessStreamKind, finish: Boolean) {
        val text = leftover.toString().replace("\r\n", "\n").replace('\r', '\n')
        leftover.setLength(0)
        val parts = text.split('\n')
        if (parts.size == 1 && !finish) {
            leftover.append(parts[0])
            return
        }
        for (index in 0 until parts.lastIndex) {
            emit(TerminalEvent(text = parts[index], kind = kind))
        }
        val last = parts.last()
        if (finish) {
            if (last.isNotEmpty()) emit(TerminalEvent(text = last, kind = kind))
        } else {
            leftover.append(last)
        }
    }

    private fun onProcessExit(startedGeneration: Int, code: Int?) {
        if (closed.get()) return
        if (startedGeneration != generation.get()) return
        val current = stateFlow.value
        if (current == TerminalSessionState.CANCELLED || current == TerminalSessionState.STOPPED) return
        if (current != TerminalSessionState.RUNNING && current != TerminalSessionState.STARTING) return
        exitCode = code
        stateFlow.value = TerminalSessionState.EXITED
        emit(system("exit ${code ?: "?"}"))
        process = null
    }

    private fun stopProcess(next: TerminalSessionState) {
        generation.incrementAndGet()
        stdoutJob?.cancel()
        stderrJob?.cancel()
        waitJob?.cancel()
        stdoutJob = null
        stderrJob = null
        waitJob = null
        val current = process
        process = null
        current?.destroy(force = true)
        stateFlow.value = next
    }

    private fun fail(failure: WorkspaceError) {
        error = failure
        stateFlow.value = TerminalSessionState.FAILED
        emit(system(failure.userMessage))
    }

    private fun emit(event: TerminalEvent) {
        buffer.append(TerminalBufferLine(id = event.id, text = event.text, kind = event.kind))
        eventFlow.tryEmit(event)
    }

    private fun system(text: String) = TerminalEvent(text = text, kind = ProcessStreamKind.SYSTEM)

    private fun maybeTrackDirectory(line: String) {
        val trimmed = line.trim()
        if (!trimmed.startsWith("cd")) return
        val rest = trimmed.removePrefix("cd").trim()
        if (rest.isEmpty() || rest == "~") {
            cwdFlow.value = processHome()
            return
        }
        if (rest.startsWith("/")) {
            cwdFlow.value = rest.trim('"', '\'')
        }
    }

    private fun usableDirectory(path: String): String? {
        val file = File(path)
        return if (file.isDirectory && file.canRead()) file.absolutePath else null
    }

    private fun fallbackDirectory(): String {
        val tmp = System.getProperty("java.io.tmpdir")
        if (!tmp.isNullOrBlank()) {
            val file = File(tmp)
            if (file.isDirectory) return file.absolutePath
        }
        return "/"
    }

    private fun processHome(): String =
        System.getProperty("user.home") ?: cwdFlow.value ?: fallbackDirectory()
}
