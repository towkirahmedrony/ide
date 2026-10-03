package com.agentx.app.ubuntu

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private fun ok(text: String) = AgentxCommandResult(exitCode = 0, stdout = text, stderr = "")

private fun failed(exit: Int, text: String) =
    AgentxCommandResult(exitCode = exit, stdout = "", stderr = text)

/**
 * The package-installation lifecycle, with the guest faked.
 *
 * What is under test is the rule that decides whether a runtime may be called READY: a package
 * that unpacked is not a binary that runs, and a failure that happened *during* unpacking means
 * the tree can no longer be trusted — `dpkg` keeps answering `install ok installed` for it.
 */
class UbuntuToolchainTest {

    private val layout = NativeRuntimeLayout(
        nativeLibraryDir = "/data/app/com.agentx.app/lib/arm64",
        runtimeDir = "/data/user/0/com.agentx.app/files/developer-runtime",
    )

    /** A guest that answers by command substring, first match first, and records the questions. */
    private class Guest(
        private val answers: List<Pair<String, AgentxCommandResult>>,
        private val fallback: AgentxCommandResult = ok(""),
    ) {
        val commands = ArrayList<String>()

        fun runner(): UbuntuGuestCommandRunner = UbuntuGuestCommandRunner { command, _ ->
            commands += command
            answers.firstOrNull { command.contains(it.first) }?.second ?: fallback
        }
    }

    /**
     * A guest that answers every check correctly, with [overrides] taking precedence so a single
     * step can be broken without disturbing the rest of the sequence.
     */
    private fun healthyAnswers(
        overrides: List<Pair<String, AgentxCommandResult>> = emptyList(),
    ): List<Pair<String, AgentxCommandResult>> = overrides + listOf(
        "dpkg --audit" to ok(""),
        "apt-get check" to ok("Reading package lists..."),
        "dpkg-query" to ok("install ok installed"),
        "apt-get update" to ok("All packages are up to date."),
        "apt-get install" to ok("Setting up git ..."),
    ) + UbuntuRootfsCatalog.REQUIRED_TOOLCHAIN_COMMANDS.map { check ->
        check.command to ok(check.expectOutput ?: "9.2.0")
    }

    private fun toolchain(guest: Guest) = UbuntuToolchain(layout = layout, runner = guest.runner())

    @Test
    fun `a healthy guest installs and verifies every required executable`() {
        val guest = Guest(healthyAnswers())
        val outcome = toolchain(guest).provision()

        val ready = assertIs<UbuntuToolchainOutcome.Ready>(outcome)
        assertEquals(
            UbuntuRootfsCatalog.REQUIRED_TOOLCHAIN_COMMANDS.map { it.label },
            ready.verified,
        )
        // The order is the brief's, and the sanity check comes first so a broken database is
        // never unpacked on top of.
        assertEquals("dpkg --audit", guest.commands.first())
        assertTrue(guest.commands.contains("apt-get update"))
        assertTrue(guest.commands.any { it.contains("apt-get install -y --no-install-recommends") })
        // Every executable was actually run, not inferred from the install's exit code.
        for (check in UbuntuRootfsCatalog.REQUIRED_TOOLCHAIN_COMMANDS) {
            assertTrue(guest.commands.contains(check.command), "never ran ${check.command}")
        }
    }

    @Test
    fun `a dpkg database in a mess is damaged, and nothing is unpacked into it`() {
        val guest = Guest(
            listOf(
                "dpkg --audit" to ok(
                    "The following packages are only half configured, due to errors " +
                        "while configuring them: perl-base",
                ),
            ),
        )
        val outcome = toolchain(guest).provision()

        val damaged = assertIs<UbuntuToolchainOutcome.Damaged>(outcome)
        assertTrue(damaged.reason.contains("perl-base"), damaged.reason)
        assertEquals(listOf("dpkg --audit"), guest.commands)
    }

    @Test
    fun `an inconsistent package database is damaged`() {
        val guest = Guest(
            listOf(
                "dpkg --audit" to ok(""),
                "apt-get check" to failed(100, "E: You have held broken packages."),
            ),
        )
        assertIs<UbuntuToolchainOutcome.Damaged>(toolchain(guest).provision())
    }

    @Test
    fun `a database that lost a base package is damaged`() {
        val guest = Guest(
            listOf(
                "dpkg --audit" to ok(""),
                "apt-get check" to ok(""),
                "dpkg-query" to ok("deinstall ok config-files"),
            ),
        )
        val damaged = assertIs<UbuntuToolchainOutcome.Damaged>(toolchain(guest).provision())
        assertTrue(damaged.reason.contains("does not report bash as installed"), damaged.reason)
    }

    /**
     * The reported failure, at the level the lifecycle meets it.
     *
     * `apt-get install` is where `dpkg` dies on `perl-base`, and by then it may have unpacked part
     * of a package. `dpkg --audit` stays silent about that and `dpkg-query` keeps answering
     * `install ok installed`, so the tree is not repaired in place — it is reported as damaged and
     * rebuilt from the archive.
     */
    @Test
    fun `a failed apt unpack reports the rootfs as damaged, not as retryable`() {
        val guest = Guest(
            healthyAnswers(
                listOf(
                    "apt-get install" to failed(
                        100,
                        "dpkg: error processing archive " +
                            "/var/cache/apt/archives/perl-base_5.38.2-3.2ubuntu0.6_arm64.deb " +
                            "(--unpack):\n" +
                            " error setting ownership of '/usr/bin/perl5.38.2.dpkg-new': " +
                            "No such file or directory\n" +
                            "E: Sub-process /usr/bin/dpkg returned an error code (1)",
                    ),
                ),
            ),
        )
        val outcome = toolchain(guest).provision()

        val damaged = assertIs<UbuntuToolchainOutcome.Damaged>(outcome)
        assertTrue(damaged.reason.contains("perl5.38.2.dpkg-new"), damaged.reason)
        // Nothing after the failure is attempted.
        assertFalse(guest.commands.contains("git --version"))
    }

    /**
     * A failure *before* anything is unpacked leaves the tree exactly as it was, so it is
     * retryable without throwing away a 110 MB rootfs.
     */
    @Test
    fun `a failed apt-get update is retryable and does not condemn the tree`() {
        val guest = Guest(
            healthyAnswers(
                listOf("apt-get update" to failed(100, "Could not resolve 'ports.ubuntu.com'")),
            ),
        )
        val outcome = toolchain(guest).provision()

        val failure = assertIs<UbuntuToolchainOutcome.Failed>(outcome)
        assertTrue(failure.transient)
        assertEquals(UbuntuInstallStage.CONFIGURATION, failure.stage)
        assertTrue(failure.message.contains("ports.ubuntu.com"), failure.message)
        assertFalse(guest.commands.any { it.contains("apt-get install") })
    }

    /**
     * A package that installs but does not run is not a toolchain.
     *
     * This is the check that could not come from `apt`'s exit code: `perl-base` failing to unpack
     * left the database looking perfect, and only running the tools answers the question.
     */
    @Test
    fun `an executable that does not answer fails verification and condemns the tree`() {
        val guest = Guest(
            healthyAnswers(
                listOf(
                    "node --version" to failed(
                        127,
                        "bash: line 1: /usr/bin/node: No such file or directory",
                    ),
                ),
            ),
        )
        val outcome = toolchain(guest).provision()

        val damaged = assertIs<UbuntuToolchainOutcome.Damaged>(outcome)
        assertTrue(damaged.reason.contains("node"), damaged.reason)
        assertTrue(damaged.reason.contains("No such file or directory"), damaged.reason)
    }

    @Test
    fun `an executable that prints the wrong thing fails verification`() {
        val guest = Guest(healthyAnswers(listOf("gh --version" to ok("gh: command not found"))))
        val outcome = toolchain(guest).provision()

        val damaged = assertIs<UbuntuToolchainOutcome.Damaged>(outcome)
        assertTrue(damaged.reason.contains("gh"), damaged.reason)
        assertTrue(damaged.reason.contains("did not print"), damaged.reason)
    }

    @Test
    fun `an executable that prints nothing fails verification`() {
        val guest = Guest(healthyAnswers(listOf("rg --version" to ok("   "))))
        val outcome = toolchain(guest).provision()

        val damaged = assertIs<UbuntuToolchainOutcome.Damaged>(outcome)
        assertTrue(damaged.reason.contains("printed nothing"), damaged.reason)
    }

    @Test
    fun `a timed out check fails verification`() {
        val guest = Guest(
            healthyAnswers(
                listOf("curl --version" to AgentxCommandResult(-1, "", "", timedOut = true)),
            ),
        )
        val outcome = toolchain(guest).provision()

        val damaged = assertIs<UbuntuToolchainOutcome.Damaged>(outcome)
        assertTrue(damaged.reason.contains("timed out"), damaged.reason)
    }

    @Test
    fun `the install asks for every catalogued package and nothing else`() {
        val guest = Guest(healthyAnswers())
        toolchain(guest).provision()

        val install = guest.commands.single { it.contains("apt-get install") }
        assertTrue(install.contains("DEBIAN_FRONTEND=noninteractive"), install)
        assertTrue(install.contains("--no-install-recommends"), install)
        for (packageName in UbuntuRootfsCatalog.TOOLCHAIN_PACKAGES) {
            assertTrue(install.contains(" $packageName"), "$packageName missing from: $install")
        }
        // `debconf` is what provides /usr/sbin/dpkg-preconfigure, which the base image's apt hook
        // calls and the base image does not ship.
        assertTrue(UbuntuRootfsCatalog.TOOLCHAIN_PACKAGES.contains("debconf"))
    }

    @Test
    fun `streams are read together, because ssh answers on stderr`() {
        val sshAnswers = AgentxCommandResult(
            exitCode = 0,
            stdout = "",
            stderr = "OpenSSH_9.6p1 Ubuntu-3ubuntu13.19, OpenSSL 3.0.13 30 Jan 2024",
        )
        val guest = Guest(healthyAnswers(listOf("ssh -V" to sshAnswers)))
        val outcome = toolchain(guest).provision()

        val ready = assertIs<UbuntuToolchainOutcome.Ready>(outcome)
        assertTrue(ready.verified.contains("ssh"))
    }
}
