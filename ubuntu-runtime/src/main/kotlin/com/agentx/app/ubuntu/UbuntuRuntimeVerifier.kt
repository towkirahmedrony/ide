package com.agentx.app.ubuntu

import java.io.File
import java.util.concurrent.TimeUnit

/** One guest command that was run, and what it answered. */
data class UbuntuProbeResult(
    val label: String,
    val command: List<String>,
    val exitCode: Int,
    val output: String,
) {
    val commandLine: String get() = command.joinToString(" ")
}

/** The outcome of running the whole probe sequence. */
data class UbuntuRootfsVerification(
    val results: List<UbuntuProbeResult>,
    /** The first probe that failed, described for the user, or null when all of them passed. */
    val failure: String? = null,
) {
    val ok: Boolean get() = failure == null

    val summary: String
        get() = if (ok) {
            "Guest verified through PRoot: ${results.joinToString { it.label }}"
        } else {
            failure.orEmpty()
        }
}

/**
 * Runs the installed rootfs through PRoot before it is accepted as READY.
 *
 * A rootfs that extracts is not automatically a rootfs that *runs*. This is the step that turns
 * "the files are there" into "a real Ubuntu shell answered": the guest is started exactly the
 * way the terminal starts it — `libproot.so` with `PROOT_LOADER`, the rootfs mounted, the guest
 * binaries reached through the loader — and asked for its shell, its identity, its working
 * directory and its package manager.
 *
 * Nothing here executes a guest binary through Android's `execve` directly, and nothing here
 * touches the legacy Termux prefix: the only executable Android starts is
 * [NativeRuntimeLayout.proot].
 */
class UbuntuRuntimeVerifier(
    private val layout: NativeRuntimeLayout,
    private val resolvConf: () -> String? = {
        layout.resolvConf.takeIf { File(it).isFile }
    },
    private val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
    /** Injectable so the rules are unit testable without an ARM64 device. */
    private val runner: (UbuntuGuestProbe) -> UbuntuProbeResult = { probe ->
        runUbuntuGuestProbe(layout, probe, resolvConf(), timeoutSeconds)
    },
) {

    /**
     * The probes, in the order the runtime promises them.
     *
     * The shell probes come first: if `/bin/bash --login` cannot run, nothing after it matters.
     */
    val probes: List<UbuntuGuestProbe> = listOf(
        UbuntuGuestProbe(
            label = "sh",
            command = listOf("/bin/sh", "-c", "echo AgentX Ubuntu OK"),
            expectOutput = "AgentX Ubuntu OK",
        ),
        UbuntuGuestProbe(
            label = "bash",
            command = listOf("/bin/bash", "--version"),
            expectOutput = "GNU bash",
        ),
        UbuntuGuestProbe(
            label = "os-release",
            // The check that identifies the *root*, not the architecture. `uname -m` answers
            // aarch64 from an Android shell too, and the host `id` answers u0_a1005, so neither
            // distinguishes the guest from the host. This does.
            command = listOf("/usr/bin/cat", "/etc/os-release"),
            expectOutput = "Ubuntu",
        ),
        UbuntuGuestProbe(
            label = "ls-root",
            // The guest `/` must be the rootfs. If PRoot had not switched roots this would list
            // Android's root or fail outright.
            command = listOf("/usr/bin/ls", "/"),
            expectOutput = "usr",
        ),
        UbuntuGuestProbe(
            label = "id",
            // PRoot's -0 fake-root: uid 0 without Android root, Magisk or a system change.
            command = listOf("/usr/bin/id"),
            expectOutput = "uid=0",
        ),
        UbuntuGuestProbe(
            label = "pwd",
            command = listOf("/usr/bin/pwd"),
            expectOutput = ProotCommand.GUEST_HOME,
        ),
        UbuntuGuestProbe(
            label = "apt-get",
            command = listOf("/usr/bin/apt-get", "--version"),
            expectOutput = "apt",
        ),
        UbuntuGuestProbe(
            label = "dpkg",
            command = listOf("/usr/bin/dpkg", "--version"),
            expectOutput = "dpkg",
        ),
    )

    fun verify(): UbuntuRootfsVerification {
        val results = ArrayList<UbuntuProbeResult>(probes.size)
        for (probe in probes) {
            val result = try {
                runner(probe)
            } catch (error: Throwable) {
                // Throwable, and the class name is kept. Starting a process here can fail as an
                // Error rather than an Exception — an unloadable native library reaches this code
                // as UnsatisfiedLinkError — and an empty message would make that indistinguishable
                // from a probe that merely printed nothing.
                UbuntuProbeResult(
                    label = probe.label,
                    command = probe.command,
                    exitCode = -1,
                    output = "${error.javaClass.name}: ${error.message.orEmpty()}",
                )
            }
            results += result
            if (result.exitCode != 0) {
                return UbuntuRootfsVerification(
                    results = results,
                    failure = "The Ubuntu guest could not run `${result.commandLine}` " +
                        "(exit ${result.exitCode}). ${excerpt(result.output)} " +
                        "The rootfs is installed but not usable, so the terminal is not started.",
                )
            }
            val expected = probe.expectOutput
            if (expected != null && !result.output.contains(expected)) {
                return UbuntuRootfsVerification(
                    results = results,
                    failure = "`${result.commandLine}` did not answer as a Ubuntu guest: expected " +
                        "\"$expected\". ${excerpt(result.output)} " +
                        "The runtime needs its PRoot loader and a rootfs extracted through it.",
                )
            }
        }
        return UbuntuRootfsVerification(results = results)
    }

    private fun excerpt(output: String): String {
        val trimmed = output.trim()
        if (trimmed.isEmpty()) return "It printed nothing."
        val capped = if (trimmed.length > MAX_EXCERPT) trimmed.take(MAX_EXCERPT) + "…" else trimmed
        return "Output: $capped"
    }

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS: Long = 60L
        private const val MAX_EXCERPT = 400
    }
}

/** A guest command plus the output that proves it ran. */
data class UbuntuGuestProbe(
    val label: String,
    val command: List<String>,
    /** A substring the output must contain, or null when the exit status is the whole test. */
    val expectOutput: String? = null,
)

/**
 * Starts one guest command through PRoot and collects its output.
 *
 * This is the same invocation shape the terminal uses, minus the pty, so a probe that passes here
 * means the interactive path can start too. Only [NativeRuntimeLayout.proot] is executed by
 * Android; the guest binaries are reached through `PROOT_LOADER`.
 */
private fun runUbuntuGuestProbe(
    layout: NativeRuntimeLayout,
    probe: UbuntuGuestProbe,
    resolvConf: String?,
    timeoutSeconds: Long,
): UbuntuProbeResult {
    val invocation = ProotCommand.build(
        layout = layout,
        guestWorkingDirectory = ProotCommand.GUEST_HOME,
        binds = ProotCommand.infrastructureBinds(layout, resolvConf),
        guestCommand = probe.command,
    )
    val builder = ProcessBuilder(invocation.processCommand).redirectErrorStream(true)
    for ((name, value) in invocation.environment) {
        builder.environment()[name] = value
    }
    val process = builder.start()

    // Drain while waiting: a guest that writes more than a pipe buffer must not stall against
    // its own output.
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

    return UbuntuProbeResult(
        label = probe.label,
        command = probe.command,
        exitCode = if (exited) process.exitValue() else -1,
        output = output.toString(),
    )
}
