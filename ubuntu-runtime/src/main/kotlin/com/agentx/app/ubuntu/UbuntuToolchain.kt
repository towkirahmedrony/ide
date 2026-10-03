package com.agentx.app.ubuntu

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs one shell command inside the guest.
 *
 * Injected so the whole package-installation lifecycle is unit testable without a device, and
 * so a caller can supply a different backend later. An implementation never throws: a guest
 * command that cannot be started at all is reported as a non-zero [AgentxCommandResult], because
 * the lifecycle has to be able to *report* that rather than unwind through it.
 */
fun interface UbuntuGuestCommandRunner {
    fun run(command: String, timeoutSeconds: Long): AgentxCommandResult
}

/**
 * What the guest's package database says about itself.
 *
 * This is deliberately only *one* of the gates. A tree that `dpkg` has left half-unpacked can
 * still report `install ok installed` for every package and an empty `dpkg --audit` — that was
 * measured against a tree that had just failed to unpack `perl-base`, and the only thing that
 * revealed it was trying to run the binaries. [UbuntuPackageState] therefore catches a database
 * that is honest about being broken; [UbuntuToolchain.verify] is what catches everything else.
 */
sealed interface UbuntuPackageState {

    data object Usable : UbuntuPackageState

    /** The tree cannot be repaired in place; it has to be re-extracted. */
    data class Damaged(val reason: String) : UbuntuPackageState
}

/** What one attempt at the developer toolchain settled on. */
sealed interface UbuntuToolchainOutcome {

    /** Every package is installed and every required executable answered inside the guest. */
    data class Ready(val verified: List<String>) : UbuntuToolchainOutcome

    /**
     * The rootfs itself must be recreated before anything else is attempted. Everything that
     * produced this outcome has already stopped: no package state is trusted after it.
     */
    data class Damaged(val reason: String) : UbuntuToolchainOutcome

    /**
     * The tree is still intact — the failure happened before anything was unpacked, or was a
     * timeout — so the same tree can be retried without re-extracting it.
     */
    data class Failed(
        val stage: UbuntuInstallStage,
        val message: String,
        val transient: Boolean = true,
    ) : UbuntuToolchainOutcome
}

/**
 * Installs and then *proves* the Ubuntu developer toolchain inside an installed rootfs.
 *
 * The order is the brief's, and each step is a gate rather than a log line:
 *
 * ```
 * apt/dpkg sanity check  →  apt-get update  →  apt-get install  →  verify every executable
 * ```
 *
 * Only the last step can promote a runtime to READY, and it does so by running each binary.
 * That is not belt-and-braces: the failure this whole area was fixed for — `-l` emulated hard
 * links pointing outside the guest root — produced a tree whose `dpkg` database was pristine
 * (`install ok installed`, `dpkg --audit` empty, `apt-get check` clean) while `/usr/bin/perl`
 * could not be executed at all. Nothing short of running the tools detects that.
 */
class UbuntuToolchain(
    private val layout: NativeRuntimeLayout,
    private val runner: UbuntuGuestCommandRunner,
    private val packages: List<String> = UbuntuRootfsCatalog.TOOLCHAIN_PACKAGES,
    private val commands: List<UbuntuRootfsCatalog.ToolchainCommand> =
        UbuntuRootfsCatalog.REQUIRED_TOOLCHAIN_COMMANDS,
    private val sanityTimeoutSeconds: Long = SANITY_TIMEOUT_SECONDS,
    private val updateTimeoutSeconds: Long = UPDATE_TIMEOUT_SECONDS,
    private val installTimeoutSeconds: Long = INSTALL_TIMEOUT_SECONDS,
    private val probeTimeoutSeconds: Long = PROBE_TIMEOUT_SECONDS,
) {

    /**
     * The cheap preflight: `dpkg`'s database answers, is internally consistent, and still knows
     * about the base packages the runtime was built on.
     *
     * A non-empty `dpkg --audit` is dpkg's own "the following packages are in a mess" report, and
     * a non-zero `apt-get check` is apt's "you have held broken packages". Both mean the tree has
     * to be re-extracted, because unpacking on top of them compounds the damage.
     */
    fun packageState(): UbuntuPackageState {
        val audit = runner.run("dpkg --audit", sanityTimeoutSeconds)
        if (!audit.success) {
            return UbuntuPackageState.Damaged(
                "`dpkg --audit` exited ${exitLabel(audit)}: ${excerpt(audit)}",
            )
        }
        val auditOutput = audit.output.trim()
        if (auditOutput.isNotEmpty()) {
            return UbuntuPackageState.Damaged(
                "the dpkg database reports packages in a mess: ${auditOutput.take(400)}",
            )
        }

        val check = runner.run("apt-get check", sanityTimeoutSeconds)
        if (!check.success) {
            return UbuntuPackageState.Damaged(
                "`apt-get check` exited ${exitLabel(check)}: ${excerpt(check)}",
            )
        }

        // The base image's own shell must still be registered; a database that lost it is not a
        // database this runtime can install into. `dash` and `bash` are the two the archive
        // guarantees, and both are packages dpkg tracks.
        for (base in listOf("bash", "dpkg", "apt")) {
            val query = runner.run(
                "dpkg-query -W -f='\${Status}' $base",
                sanityTimeoutSeconds,
            )
            val status = query.output.trim()
            if (!query.success || status != INSTALLED_STATUS) {
                return UbuntuPackageState.Damaged(
                    "dpkg does not report $base as installed (status=\"$status\", " +
                        "exit ${exitLabel(query)}).",
                )
            }
        }
        return UbuntuPackageState.Usable
    }

    /** `apt-get update`, with the guest's retry policy already in `99agentx.conf`. */
    fun update(): AgentxCommandResult =
        runner.run("apt-get update", updateTimeoutSeconds)

    /** The install itself. `--no-install-recommends` keeps a phone-sized rootfs phone-sized. */
    fun install(): AgentxCommandResult = runner.run(
        "DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends " +
            packages.joinToString(" "),
        installTimeoutSeconds,
    )

    /**
     * Runs every required executable and returns the ones that did not answer.
     *
     * A check fails when the command exits non-zero, prints nothing, or does not print the
     * expected signature. The empty-output rule is what catches a binary that exists but cannot
     * be started through a broken link, which is the failure mode this replaced.
     */
    fun failures(): List<String> {
        val failures = ArrayList<String>()
        for (check in commands) {
            val result = runner.run(check.command, probeTimeoutSeconds)
            val output = result.output.trim()
            val problem = when {
                result.timedOut -> "timed out"
                !result.success -> "exited ${exitLabel(result)}"
                output.isEmpty() -> "printed nothing"
                check.expectOutput != null && !output.contains(check.expectOutput) ->
                    "did not print \"${check.expectOutput}\""
                else -> null
            }
            if (problem != null) {
                failures += "${check.label} (`${check.command}`) $problem: ${excerpt(result)}"
            }
        }
        return failures
    }

    /**
     * The whole sequence, stopping at the first gate that closes.
     *
     * Nothing here writes a marker; the caller does that, and only for [UbuntuToolchainOutcome.Ready].
     */
    fun provision(): UbuntuToolchainOutcome {
        when (val state = packageState()) {
            is UbuntuPackageState.Damaged -> return UbuntuToolchainOutcome.Damaged(state.reason)
            UbuntuPackageState.Usable -> Unit
        }

        val update = update()
        if (!update.success) {
            // Nothing has been unpacked yet, so the tree is exactly as it was: this is retryable
            // without throwing the rootfs away.
            return UbuntuToolchainOutcome.Failed(
                stage = UbuntuInstallStage.CONFIGURATION,
                message = "`apt-get update` exited ${exitLabel(update)}: ${excerpt(update)}",
                transient = true,
            )
        }

        val install = install()
        if (!install.success) {
            // Unpacking may have started and stopped halfway, and dpkg cannot be trusted to say
            // so (see the class documentation). The only safe answer is a fresh tree.
            return UbuntuToolchainOutcome.Damaged(
                "`apt-get install` exited ${exitLabel(install)}: ${excerpt(install)}",
            )
        }

        val failures = failures()
        if (failures.isNotEmpty()) {
            return UbuntuToolchainOutcome.Damaged(
                "the toolchain did not verify: " + failures.joinToString(FAILURE_SEPARATOR),
            )
        }
        return UbuntuToolchainOutcome.Ready(commands.map { it.label })
    }

    /** The guest paths the marker records, for diagnostics. */
    fun verifiedLabels(): List<String> = commands.map { it.label }

    companion object {
        const val SANITY_TIMEOUT_SECONDS: Long = 120L
        const val UPDATE_TIMEOUT_SECONDS: Long = 600L
        /** `apt-get install` of the toolchain is slower than a single command. */
        const val INSTALL_TIMEOUT_SECONDS: Long = 1800L
        const val PROBE_TIMEOUT_SECONDS: Long = 60L

        /** What `dpkg-query -W -f='${Status}' <pkg>` prints for a fully installed package. */
        const val INSTALLED_STATUS: String = "install ok installed"

        private const val MAX_EXCERPT = 300
        private const val FAILURE_SEPARATOR = "; "

        fun excerpt(result: AgentxCommandResult): String {
            val text = result.output.trim().ifEmpty { return "it printed nothing" }
            val tail = text.lines().takeLast(4).joinToString(" | ")
            return if (tail.length > MAX_EXCERPT) tail.take(MAX_EXCERPT) + "…" else tail
        }

        fun exitLabel(result: AgentxCommandResult): String =
            if (result.timedOut) "on timeout" else "with ${result.exitCode}"
    }
}

/**
 * The runner that actually reaches the guest.
 *
 * The command is run by `/bin/bash` with the guest `PATH`, so `git` resolves the way it does in
 * the interactive shell the user gets, and with `HOME` set because `gh`, `pip` and `npm` write
 * into it. `stdout` and `stderr` are merged: `ssh -V` answers on `stderr`, and a verification
 * that silently ignored that would fail on a working guest.
 */
internal fun ubuntuGuestCommandRunner(
    layout: NativeRuntimeLayout,
    hostWorkingDirectory: String,
    resolvConf: () -> String?,
): UbuntuGuestCommandRunner = UbuntuGuestCommandRunner { command, timeoutSeconds ->
    runUbuntuGuestCommand(layout, resolvConf(), hostWorkingDirectory, command, timeoutSeconds)
}

private fun runUbuntuGuestCommand(
    layout: NativeRuntimeLayout,
    resolvConf: String?,
    hostWorkingDirectory: String,
    command: String,
    timeoutSeconds: Long,
): AgentxCommandResult {
    val invocation = ProotCommand.build(
        layout = layout,
        guestWorkingDirectory = ProotCommand.GUEST_HOME,
        binds = ProotCommand.infrastructureBinds(layout, resolvConf),
        guestCommand = listOf("/bin/bash", "-c", command),
    )
    return try {
        val builder = ProcessBuilder(invocation.processCommand)
            .redirectErrorStream(true)
            .directory(File(hostWorkingDirectory))
        builder.environment().putAll(invocation.environment)
        for (entry in UbuntuEnvironment.build(androidEnv = emptyMap())) {
            val separator = entry.indexOf('=')
            if (separator > 0) {
                builder.environment()[entry.substring(0, separator)] = entry.substring(separator + 1)
            }
        }
        val process = builder.start()
        val output = StringBuilder()
        val reader = Thread {
            process.inputStream.bufferedReader().forEachLine { line -> output.appendLine(line) }
        }.apply { isDaemon = true; start() }

        val exited = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!exited) {
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
        }
        reader.join(2_000)

        AgentxCommandResult(
            exitCode = if (exited) process.exitValue() else -1,
            stdout = output.toString(),
            stderr = "",
            timedOut = !exited,
        )
    } catch (error: Throwable) {
        // Throwable, not Exception: starting a process can fail as an Error (an unloadable
        // native library reaches here as UnsatisfiedLinkError), and the lifecycle has to report
        // that as a failed command rather than unwind out of provisioning.
        AgentxCommandResult(
            exitCode = -1,
            stdout = "",
            stderr = "${error.javaClass.name}: ${error.message.orEmpty()}",
        )
    }
}
