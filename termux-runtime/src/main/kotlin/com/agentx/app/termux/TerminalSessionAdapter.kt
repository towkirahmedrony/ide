package com.agentx.app.termux

import android.util.Log
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Binds the vendored Termux [TerminalSession] to [TermuxSession].
 *
 * `TerminalSession` is `final`, so it cannot be subclassed; wrapping it keeps the manager and
 * its tests independent of the PTY while the UI still gets the real object it must hand to
 * `TerminalView.attachSession`.
 *
 * The constructor stays cheap and must run on the main thread, because the vendored class creates
 * its `Handler` there and every emulator callback is posted through it. Launching the process —
 * the `fork`/`exec` behind `updateSize` — is what moves off that thread, so a slow or wedged PTY
 * allocation can no longer block the UI, and so a launch that never returns the process can be
 * timed out and turned into a recoverable failure.
 */
class TerminalSessionAdapter(
    val delegate: TerminalSession,
    override val executable: String? = null,
    override val temporarySystemShell: Boolean = executable == TermuxShellResolver.SYSTEM_SHELL,
    private val columns: Int = DEFAULT_COLUMNS,
    private val rows: Int = DEFAULT_ROWS,
    private val cellWidthPixels: Int = 0,
    private val cellHeightPixels: Int = 0,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val startupTimeoutMillis: Long = DEFAULT_STARTUP_TIMEOUT_MILLIS,
    private val stopTimeoutMillis: Long = DEFAULT_STOP_TIMEOUT_MILLIS,
) : TermuxSession {

    /**
     * Owned by this session, not by a caller: the process outlives the screen that started it, and
     * cancelling this in [finish] is what releases the launch/timeout bookkeeping.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var started = false

    /** Set once the PTY reported a pid, which is this session's "the shell came up" signal. */
    @Volatile
    private var reachedRunning = false

    @Volatile
    private var stopping = false

    /** Set when cleanup completed without the delegate ever reporting an exit. */
    @Volatile
    private var forcedStopped = false

    @Volatile
    private var failureValue: String? = null

    @Volatile
    private var loggedState: TerminalSessionState = TerminalSessionState.IDLE

    private var startupGuard: Job? = null
    private var stopGuard: Job? = null
    private val lock = Any()

    override val handle: String get() = delegate.mHandle

    override val state: TerminalSessionState
        get() {
            val next = if (forcedStopped) {
                TerminalSessionState.STOPPED
            } else {
                terminalSessionState(
                    failure = failureValue,
                    started = started,
                    stopping = stopping,
                    pid = delegate.pid,
                    reachedRunning = reachedRunning,
                )
            }
            val previous = loggedState
            if (previous != next) {
                loggedState = next
                logSessionTransition(
                    from = previous,
                    to = next,
                    handle = handle,
                    pid = delegate.pid.takeIf { it > 0 },
                    reason = failureValue,
                )
            }
            return next
        }

    override val exitStatus: Int get() = delegate.exitStatus

    override val failure: String? get() = failureValue

    override val title: String? get() = delegate.title

    override val workingDirectory: String? get() = delegate.cwd

    /**
     * Starts the process and confirms it came up.
     *
     * Never throws. A PTY that cannot be created, an executable Android refuses, or a launch that
     * does not return are all folded into [TerminalSessionState.FAILED] on this same object, so the
     * session list always holds something the UI can name and restart.
     */
    override fun start() {
        synchronized(lock) {
            if (started) return
            started = true
        }
        Log.i(TAG, "start handle=$handle executable=$executable")
        TerminalDiagnostics.record(
            TAG,
            "start handle=$handle executable=$executable cwd=${delegate.cwd ?: "(none)"}",
        )
        DeveloperLogger.info(DeveloperLogCategory.PROCESS, "process creation started handle=$handle")
        DeveloperLogger.info(DeveloperLogCategory.PTY, "PTY create START handle=$handle")
        state

        scope.launch {
            // Armed before the launch, not after it. The case this guard exists for is a pty
            // allocation that never returns — and if control never returns from the block below,
            // arming it afterwards never happens either, so the timeout could only ever fire for a
            // launch that had already succeeded. A launch that works reports RUNNING in
            // milliseconds, long before the deadline, so this can still only fire on a stuck one.
            armStartupTimeout()
            try {
                // The fork/exec happens inside here. `updateSize` initialises the emulator on its
                // first call, so a view that attached first has already done this; the second call
                // only resizes. Either way this is the call that can throw.
                withContext(ioDispatcher) {
                    delegate.updateSize(columns, rows, cellWidthPixels, cellHeightPixels)
                }
                val pid = delegate.pid
                TerminalDiagnostics.record(TAG, "pty created handle=$handle pid=$pid")
                DeveloperLogger.info(DeveloperLogCategory.PTY, "PTY create SUCCESS handle=$handle")
                DeveloperLogger.info(DeveloperLogCategory.PTY, "PTY master FD created handle=$handle")
                DeveloperLogger.info(DeveloperLogCategory.PTY, "PTY reader START handle=$handle")
                DeveloperLogger.info(DeveloperLogCategory.PTY, "PTY writer START handle=$handle")
                DeveloperLogger.info(DeveloperLogCategory.PROCESS, "process created handle=$handle pid=$pid")
                DeveloperLogger.info(DeveloperLogCategory.PROCESS, "PID=$pid")
                if (pid <= 0) {
                    // Named as a pty failure: the log has to be able to say which stage of the
                    // launch failed, because creating the pty and starting the guest program in it
                    // are different problems with different fixes.
                    DeveloperLogger.error(
                        DeveloperLogCategory.PTY,
                        "PTY create FAILED handle=$handle pid=$pid reason=no shell pid reported",
                    )
                    fail("the pty did not report a shell pid", pid = pid, exit = null)
                    return@launch
                }
                if (failureValue != null || forcedStopped) {
                    // This launch returned only after the startup guard had already given up on it.
                    // The shell it has just created would otherwise run on with nobody owning it:
                    // no screen, no handle the UI can act on, and nothing left that would ever stop
                    // it. Killing it here is what keeps a timed-out launch from leaking a process.
                    DeveloperLogger.warn(
                        DeveloperLogCategory.PTY,
                        "PTY create SUCCESS handle=$handle pid=$pid after the startup guard gave up; " +
                            "stopping it",
                    )
                    runCatching { delegate.finishIfRunning() }
                    return@launch
                }
                reachedRunning = true
                Log.i(TAG, "RUNNING handle=$handle pid=$pid")
                TerminalDiagnostics.record(TAG, "RUNNING handle=$handle pid=$pid")
                DeveloperLogger.info(DeveloperLogCategory.TERMINAL, "Shell startup handle=$handle pid=$pid")
                state
            } catch (error: Throwable) {
                // Throwable, not Exception: the pty is reached through JNI, so the failures that
                // matter here are Errors — UnsatisfiedLinkError, NoClassDefFoundError,
                // ExceptionInInitializerError. Only Exception was caught before, which is why a
                // missing or unloadable native library looked like "no session" rather than an error.
                DeveloperLogger.error(
                    DeveloperLogCategory.PTY,
                    "PTY create FAILED handle=$handle ${error.javaClass.simpleName}: " +
                        (error.message?.takeIf { it.isNotBlank() } ?: "(no message)"),
                )
                fail(
                    reason = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName,
                    pid = delegate.pid,
                    exit = null,
                    error = error,
                )
            }
        }
    }

    /**
     * Safety net, not the mechanism: the launch above reports its own outcome. This exists so a
     * PTY allocation that never returns cannot leave the session in [TerminalSessionState.STARTING]
     * forever, which is the state the UI would otherwise wait on indefinitely.
     */
    private fun armStartupTimeout() {
        if (startupTimeoutMillis <= 0) return
        synchronized(lock) {
            startupGuard?.cancel()
            startupGuard = scope.launch {
                delay(startupTimeoutMillis)
                if (state == TerminalSessionState.STARTING) {
                    fail("the shell did not report a status within ${startupTimeoutMillis}ms", delegate.pid, null)
                }
            }
        }
    }

    private fun fail(reason: String, pid: Int, exit: Int?, error: Throwable? = null) {
        synchronized(lock) {
            if (failureValue != null) return
            failureValue = reason
            stopping = true
        }
        Log.e(TAG, "FAILED handle=$handle pid=$pid exit=${exit ?: "n/a"} reason=$reason")
        if (error != null) {
            TerminalDiagnostics.recordFailure(TAG, "session failed to start handle=$handle", error)
            DeveloperLogger.error(
                DeveloperLogCategory.ERROR,
                "process start failure handle=$handle pid=$pid",
                error,
            )
            if (error.message.orEmpty().contains("EIO", ignoreCase = true)) {
                DeveloperLogger.error(DeveloperLogCategory.PTY, "PTY EIO handle=$handle")
            }
        } else {
            TerminalDiagnostics.record(
                TAG,
                "session failed to start handle=$handle pid=$pid reason=$reason",
            )
            DeveloperLogger.error(
                DeveloperLogCategory.ERROR,
                "process start failure handle=$handle pid=$pid reason=$reason",
            )
        }
        state
        // The shell is not coming up, so nothing may be left attached to the pty.
        runCatching { delegate.finishIfRunning() }
        armStopTimeout()
    }

    override fun write(bytes: ByteArray, offset: Int, count: Int) {
        if (state != TerminalSessionState.RUNNING) return
        delegate.write(bytes, offset, count)
    }

    override fun updateSize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        // A dead session must not be resized back to life: the vendored class initialises the
        // emulator — and so spawns the process — on its first call, and a view attaching to a
        // session that already failed would otherwise relaunch it behind the lifecycle's back.
        if (state == TerminalSessionState.FAILED || state == TerminalSessionState.STOPPED) return
        // A `TerminalView` calls this from the UI thread the moment it attaches, and the first call
        // is what forks the process. A throw here would cross into Compose, so it becomes a session
        // failure like any other instead.
        runCatching { delegate.updateSize(columns, rows, cellWidthPixels, cellHeightPixels) }
            .onFailure { error ->
                fail(
                    error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName,
                    delegate.pid,
                    null,
                    error,
                )
            }
    }

    /**
     * Stops the process and releases the pty.
     *
     * Two mechanisms, because one is not enough here. `finishIfRunning` SIGKILLs the child's whole
     * process group (the JNI forked it with `setsid`), which covers PRoot and anything still in that
     * group. A job the guest shell put in its own process group — a foreground `python3 -m
     * http.server`, say — is instead reached by the pty hangup: closing the master sends SIGHUP to
     * the pty's foreground group, and the vendored class closes it on the exit path.
     *
     * Reaping is the vendored waiter thread's job; it is the only thread allowed to `waitpid` this
     * pid, which is why this method never blocks on the process itself.
     */
    override fun finish() {
        val pid = delegate.pid
        Log.i(TAG, "stop handle=$handle pid=$pid state=$state")
        synchronized(lock) {
            stopping = true
        }
        state
        DeveloperLogger.info(DeveloperLogCategory.PTY, "PTY close handle=$handle pid=$pid")
        runCatching { delegate.finishIfRunning() }

        // A session that never produced a pid has no waiter thread and therefore no exit callback
        // coming, so there is nothing to wait for.
        if (pid <= 0) {
            forcedStopped = true
            state
            release()
            return
        }
        armStopTimeout()
    }

    /**
     * Bounded: a process that never gets reaped must not hold the UI in
     * [TerminalSessionState.STOPPING] forever. The vendored exit path still owns the pty
     * descriptor; this only stops the session claiming to be busy with it.
     */
    private fun armStopTimeout() {
        if (stopTimeoutMillis <= 0) return
        synchronized(lock) {
            stopGuard?.cancel()
            stopGuard = scope.launch {
                delay(stopTimeoutMillis)
                if (!forcedStopped) {
                    forcedStopped = true
                    Log.w(TAG, "stop did not settle handle=$handle pid=${delegate.pid}; treating as stopped")
                }
                release()
            }
        }
    }

    /**
     * Drops the launch/timeout bookkeeping for good. A session that has stopped must not keep a
     * coroutine scope — and therefore a dispatcher reference — alive behind it.
     */
    private fun release() {
        synchronized(lock) {
            startupGuard?.cancel()
            stopGuard?.cancel()
            startupGuard = null
            stopGuard = null
        }
        scope.cancel()
    }

    companion object {
        private const val TAG = "TerminalSession"

        /** Before a `TerminalView` reports a real size. */
        const val DEFAULT_COLUMNS: Int = 80
        const val DEFAULT_ROWS: Int = 24

        /**
         * Only reached when the PTY never reports a pid. A working launch returns in milliseconds,
         * so this costs nothing in the normal path.
         */
        const val DEFAULT_STARTUP_TIMEOUT_MILLIS: Long = 15_000L

        /** How long a killed process may take to be reaped before the session is called stopped. */
        const val DEFAULT_STOP_TIMEOUT_MILLIS: Long = 5_000L
    }
}
