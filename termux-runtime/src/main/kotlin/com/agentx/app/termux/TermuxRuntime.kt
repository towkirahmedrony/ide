package com.agentx.app.termux

import android.content.Context
import android.os.Build
import android.util.Log
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The embedded Termux runtime: prefix layout, userland provisioning and live PTY sessions.
 *
 * One instance per process, created by the application and handed to the UI. It owns no
 * `GlobalScope`; the scope it is given belongs to the app's composition root, so cancelling that
 * scope shuts provisioning down instead of leaking a download.
 *
 * The runtime deliberately does **not** know about the Agent Tool System. Human terminal input
 * goes through [sessions]; model-issued commands keep going through the Tool Router's
 * COMMAND_EXECUTION permission path. They share the same Termux filesystem, not the same
 * authorisation.
 */
class TermuxRuntime(
    context: Context,
    /** Default columns/rows before a `TerminalView` reports its real size. */
    private val defaultColumns: Int = DEFAULT_COLUMNS,
    private val defaultRows: Int = DEFAULT_ROWS,
    private val transcriptRows: Int = DEFAULT_TRANSCRIPT_ROWS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clockLabel: () -> String = { System.currentTimeMillis().toString() },
) {

    private val appContext = context.applicationContext

    /**
     * Owned by this instance, not `GlobalScope`: the runtime outlives Activities and
     * ViewModels, and [release] is what ends it. A provisioning download therefore survives a
     * rotation instead of being cancelled half way.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Layout of the runtime inside this app's private data directory. */
    val paths: TermuxPaths = TermuxPaths.forAppDataDir(
        appContext.filesDir.canonicalFile.parentFile?.absolutePath
            ?: appContext.filesDir.absolutePath.removeSuffix("/files"),
    )

    /** Whether the official Termux artifacts can live in [paths]. */
    val prefixSupport: TermuxPrefixSupport = TermuxPrefixPolicy.evaluate(paths)

    init {
        // The sink lives in app storage so the last run survives a process death, which several
        // failures in this chain cause and which takes logcat with it.
        TerminalDiagnostics.attach(File(appContext.filesDir, DIAGNOSTICS_FILE))
        DeveloperLogger.attach(File(appContext.filesDir, DeveloperLogger.RELATIVE_PATH))
        TerminalDiagnostics.record(
            TAG,
            "TermuxRuntime created filesDir=${appContext.filesDir.absolutePath} abis=${supportedAbis()}",
        )
        TerminalDiagnostics.record(TAG, "legacy prefix support=${prefixSupport.javaClass.simpleName}")
        DeveloperLogger.info(
            DeveloperLogCategory.TERMINAL,
            "TermuxRuntime created filesDir=${appContext.filesDir.absolutePath} abis=${supportedAbis()}",
        )
    }

    private val installer = TermuxBootstrapInstaller(
        paths = paths,
        supportedAbis = supportedAbis(),
    )

    private val provisioningFlow = MutableStateFlow<TermuxProvisioningState>(TermuxProvisioningState.Idle)
    val provisioning: StateFlow<TermuxProvisioningState> = provisioningFlow.asStateFlow()

    /** Set by the terminal screen while it is on screen. See [TermuxSessionClient]. */
    @Volatile
    var terminalHost: TermuxTerminalHost? = null

    /**
     * The client every session is created with.
     *
     * The host is resolved per callback, and [onSessionFinished] is told about an exit even when no
     * screen is bound — the session list, the derived state, the command record and the keep-alive
     * service all belong to the runtime, so a shell that ends while the terminal tab is closed must
     * still be accounted for. Nothing else calls [onSessionFinished], which is why the callback is
     * wired here rather than left to the screen.
     */
    private val sessionClient = TermuxSessionClient(
        hostProvider = { terminalHost },
        onExit = { session -> onSessionFinished(session) },
    )

    /** Invoked whenever the session list changes, for observers that are not Compose. */
    @Volatile
    var onSessionsChanged: (List<TermuxSessionSnapshot>) -> Unit = {}

    val sessions: TermuxSessionManager = TermuxSessionManager(
        factory = ::createSession,
        onSessionsChanged = { snapshots -> onSessionsChanged(snapshots) },
    )

    /** True when `$PREFIX` already holds a shell. */
    fun isUserlandInstalled(): Boolean = installer.isInstalled()

    /**
     * Downloads and installs the Termux userland when it is missing.
     *
     * Idempotent and safe to call from several places: a second call while one is running joins
     * the same underlying work through [provisioning], and an already installed prefix returns
     * immediately.
     */
    fun provision() {
        val current = provisioningFlow.value
        if (current is TermuxProvisioningState.Downloading ||
            current is TermuxProvisioningState.Verifying ||
            current is TermuxProvisioningState.Extracting
        ) {
            return
        }
        scope.launch {
            val result = withContext(ioDispatcher) {
                installer.provision { state -> provisioningFlow.value = state }
            }
            if (result is TermuxProvisioning.Installed) {
                installer.writeEnvironmentFile(
                    environmentFor(workingDirectory = paths.home, extra = emptyMap()),
                )
                sessions.restartTemporarySystemShells { snapshot ->
                    specFor(
                        workspaceKey = snapshot.workspaceKey,
                        binding = TermuxWorkspaceBinding.Direct(
                            path = snapshot.workingDirectory?.takeIf { it.startsWith("/") } ?: paths.home,
                            displayLocation = snapshot.workingDirectory ?: paths.home,
                        ),
                    )
                }
            }
            if (result is TermuxProvisioning.Unsupported) {
                Log.w(TAG, "Termux userland is unsupported here: ${result.reason}")
            }
            if (result is TermuxProvisioning.ArtifactUnavailable) {
                Log.w(TAG, "Termux userland is not built yet: ${result.reason}")
            }
            installer.createRuntimeDirectories()
        }
    }

    /** Environment for a session, including the Termux environment file's contents. */
    fun environmentFor(
        workingDirectory: String?,
        extra: Map<String, String>,
    ): Array<String> = TermuxEnvironment.build(
        paths = paths,
        workingDirectory = workingDirectory,
        androidEnv = System.getenv(),
        extra = extra,
    )

    /**
     * Resolves where a workspace should run.
     *
     * A real filesystem path is used directly. A SAF workspace is used only when it has already
     * been mirrored into the Termux home; otherwise the caller is told why not, so the UI can say
     * so instead of running commands in the wrong directory.
     */
    fun bindingFor(
        workspaceId: String,
        handle: String?,
        displayLocation: String?,
    ): TermuxWorkspaceBinding = TermuxWorkspaceBindings.resolve(
        handle = handle,
        displayLocation = displayLocation,
        workspaceId = workspaceId,
        paths = paths,
        isDirectory = { path -> File(path).let { it.isDirectory && it.canRead() } },
    )

    /**
     * Builds the spec for a workspace.
     *
     * The shell is `$PREFIX/bin/<login|bash|…>` when the userland is installed and
     * `/system/bin/sh` until then, so the terminal is usable — with a real pty — even before a
     * bootstrap is in place.
     */
    /**
     * @param forceTemporarySystemShell skips the prefix entirely and starts Android's own
     *   `/system/bin/sh`.
     *
     *   The primary terminal backend is the embedded Ubuntu developer runtime. While that runtime
     *   is still being downloaded/installed, the terminal must still give a real pty — but it must
     *   **not** start `$PREFIX/bin/login` from app-private storage. Android forbids executing an
     *   app-private binary on a modern `targetSdk`, which is exactly what produced
     *   `exec(".../files/usr/bin/login"): Permission denied`. The legacy prefix is therefore
     *   bypassed when the developer runtime owns the terminal.
     */
    fun specFor(
        workspaceKey: String,
        binding: TermuxWorkspaceBinding,
        extraEnvironment: Map<String, String> = emptyMap(),
        forceTemporarySystemShell: Boolean = false,
    ): TermuxShellSpec {
        val workingDirectory = when (binding) {
            is TermuxWorkspaceBinding.Direct -> binding.path
            is TermuxWorkspaceBinding.Mirrored -> binding.termuxPath
            // The workspace could not be bound, so the shell runs at $HOME rather than not at
            // all: an unreadable or unmirrorable workspace must not cost the user their terminal.
            is TermuxWorkspaceBinding.Home -> binding.path
            is TermuxWorkspaceBinding.Unavailable -> paths.home
        }
        val resolved = if (forceTemporarySystemShell) {
            // Deliberately not `TermuxShellResolver.resolve`: the prefix may hold a `login`
            // binary that Android will refuse to exec, and the caller has already decided that
            // the embedded Ubuntu runtime is the shell that matters.
            TermuxShellResolver.Resolved(
                executable = TermuxShellResolver.SYSTEM_SHELL,
                processName = "sh",
                login = false,
                kind = TermuxShellResolver.Kind.TEMPORARY_SYSTEM,
                reason = "The AgentX developer runtime (Ubuntu ARM64) is not installed yet.",
            )
        } else {
            TermuxShellResolver.resolve(
                paths = paths,
                isExecutable = { path -> File(path).let { it.isFile && it.canExecute() } },
                prefixSupport = prefixSupport,
                allowTemporarySystemShell = true,
            )
        }

        val environment = environmentFor(
            workingDirectory = workingDirectory,
            extra = extraEnvironment,
        )
        return TermuxShellSpec(
            workspaceKey = workspaceKey,
            executable = resolved.executable,
            processName = resolved.processName,
            arguments = listOf(resolved.processName),
            workingDirectory = workingDirectory,
            environment = environment,
            transcriptRows = transcriptRows,
            temporarySystemShell = resolved.isTemporarySystemShell,
            fullTermux = resolved.isFullTermux,
        )
    }

    /**
     * Returns a running session for [workspaceKey], starting one only if needed.
     *
     * [keepAlive] is passed to [TermuxSessionService] so a running development server is not
     * killed while the app is in the background.
     */
    fun openSession(spec: TermuxShellSpec, keepAlive: Boolean = true): TermuxSession? {
        DeveloperLogger.info(DeveloperLogCategory.SESSION, "Session creation started workspace=${spec.workspaceKey}")
        val session = sessions.open(spec) ?: return null
        if (keepAlive) TermuxSessionService.ensureRunning(appContext)
        return session
    }

    /** Kills the process and stops the keep-alive service when no session is left. */
    fun terminate(handle: String) {
        sessions.terminate(handle)
        if (sessions.sessions().none { it.isRunning }) TermuxSessionService.stopIfIdle(appContext)
    }

    fun terminateAll() {
        sessions.terminateAll()
        TermuxSessionService.stopIfIdle(appContext)
    }

    /**
     * Ends the runtime for good: kills every shell, stops the keep-alive service and cancels
     * the provisioning scope. Only for process shutdown — calling it because an Activity was
     * recreated would kill a running development server.
     */
    fun release() {
        sessions.release()
        TermuxSessionService.stopIfIdle(appContext)
        scope.cancel()
        synchronized(LOCK) {
            if (instance === this) instance = null
        }
    }

    /** Called by the session client when a shell exits on its own. */
    fun onSessionFinished(session: TerminalSession) {
        sessions.onSessionFinished(session.mHandle)
        if (sessions.sessions().none { it.isRunning }) TermuxSessionService.stopIfIdle(appContext)
    }

    /**
     * Builds the session object. It does **not** launch the process: [TermuxSession.start] does
     * that, off the main thread, and records a failure on the session itself instead of throwing.
     *
     * Constructing rather than launching here is also what makes `STARTING` observable: the
     * vendored session reports `isRunning` for a process that was never spawned, so "created" and
     * "running" have to be distinguished by whoever owns the lifecycle.
     */
    private fun createSession(spec: TermuxShellSpec): TermuxSession {
        // The exact invocation, recorded before anything can fail: this is the line that answers
        // "what was PRoot actually asked to run".
        TerminalDiagnostics.record(
            TAG,
            "session spec workspace=${spec.workspaceKey} executable=${spec.executable} " +
                "cwd=${spec.workingDirectory} temporarySystemShell=${spec.temporarySystemShell} " +
                "fullTermux=${spec.fullTermux}",
        )
        TerminalDiagnostics.record(TAG, "argv=${spec.arguments.joinToString(" ")}")
        // Variable *names* only. The environment carries tokens and endpoint URLs, and the
        // question here is only whether PRoot received the variables it needs.
        TerminalDiagnostics.record(
            TAG,
            "env keys=${spec.environment.map { it.substringBefore('=') }.sorted().joinToString()}",
        )
        DeveloperLogger.info(DeveloperLogCategory.ENV, "Environment construction")
        DeveloperLogger.logEnvironment(spec.environment)
        DeveloperLogger.info(DeveloperLogCategory.PROCESS, "Command construction")
        DeveloperLogger.info(DeveloperLogCategory.PROCESS, "Starting process")
        DeveloperLogger.logProcessLaunch(
            executable = spec.executable,
            arguments = spec.arguments,
            workingDirectory = spec.workingDirectory,
        )
        val terminal = try {
            TerminalSession(
                spec.executable,
                spec.workingDirectory,
                spec.arguments.toTypedArray(),
                spec.environment,
                spec.transcriptRows,
                sessionClient,
            )
        } catch (error: Throwable) {
            // The class name and stack go to the diagnostics file: for a linkage failure the
            // message is often the missing library rather than the failure, and for an
            // ExceptionInInitializerError it is null.
            TerminalDiagnostics.recordFailure(
                TAG,
                "could not construct a terminal session for ${spec.executable}",
                error,
            )
            DeveloperLogger.error(
                DeveloperLogCategory.ERROR,
                "could not construct a terminal session for ${spec.executable}",
                error,
            )
            // Named so the UI's failure line can say which executable could not be started.
            throw IllegalStateException(
                TermuxShellStartFailure(
                    executable = spec.executable,
                    stderr = error.message.orEmpty(),
                    exitReason = error.javaClass.simpleName,
                ).message,
                error,
            )
        }
        return TerminalSessionAdapter(
            delegate = terminal,
            executable = spec.executable,
            temporarySystemShell = spec.temporarySystemShell,
            columns = defaultColumns,
            rows = defaultRows,
        )
    }

    private fun supportedAbis(): List<String> = Build.SUPPORTED_ABIS?.toList().orEmpty()

    /** Label used for a session that has no workspace, for example a scratch shell. */
    fun scratchLabel(): String = "shell-${clockLabel()}"

    companion object {
        const val TAG: String = "TermuxRuntime"
        const val DEFAULT_COLUMNS: Int = 80
        const val DEFAULT_ROWS: Int = 24
        const val DEFAULT_TRANSCRIPT_ROWS: Int = 2000

        /** App-private file the diagnostic recorder appends to, so a crash does not erase it. */
        const val DIAGNOSTICS_FILE: String = "terminal-diagnostics.log"

        private val LOCK = Any()

        @Volatile
        private var instance: TermuxRuntime? = null

        /**
         * The runtime for this process.
         *
         * A singleton because the sessions are the expensive, stateful part: an Activity
         * recreation must find the same runtime, otherwise it would orphan the old shells and
         * start new ones. The application context is kept, never an Activity.
         */
        fun get(context: Context): TermuxRuntime =
            instance ?: synchronized(LOCK) {
                instance ?: TermuxRuntime(context).also { instance = it }
            }
    }
}
