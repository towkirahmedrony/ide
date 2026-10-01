package com.agentx.app.ubuntu

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The verifier never runs a real PRoot here: the runner is injected, so what is under test is the
 * rule that matters — the runtime is only accepted when a guest actually answered, and the first
 * thing that did not answer is named.
 */
class UbuntuRuntimeVerifierTest {

    private val layout = NativeRuntimeLayout(
        nativeLibraryDir = "/data/app/com.agentx.app/lib/arm64",
        runtimeDir = "/data/user/0/com.agentx.app/files/developer-runtime",
    )

    private fun verifier(answers: (UbuntuGuestProbe) -> UbuntuProbeResult) =
        UbuntuRuntimeVerifier(layout = layout, resolvConf = { null }, runner = answers)

    @Test
    fun `a guest that answers every probe is accepted`() {
        val result = verifier { probe ->
            UbuntuProbeResult(probe.label, probe.command, 0, probe.expectOutput ?: "")
        }.verify()

        assertTrue(result.ok, result.failure)
        assertEquals(8, result.results.size)
        assertTrue(result.summary.contains("Guest verified through PRoot"))
    }

    @Test
    fun `the guest root is proven, not just the device architecture`() {
        // `uname -m` answers aarch64 from an Android shell too, and the host `id` answers u0_a1005,
        // so neither can tell the guest from the host — which is how an Android shell passed for a
        // working terminal. These two probes can: the guest's own os-release and its own root.
        val probes = verifier { probe ->
            UbuntuProbeResult(probe.label, probe.command, 0, probe.expectOutput ?: "")
        }.probes

        assertTrue(
            probes.any { it.command.contains("/etc/os-release") && it.expectOutput == "Ubuntu" },
            "the verifier must identify the guest root by /etc/os-release",
        )
        assertTrue(
            probes.any { it.command == listOf("/usr/bin/ls", "/") },
            "the verifier must list the guest root",
        )
    }

    @Test
    fun `an android root is refused even when every command exits zero`() {
        val result = verifier { probe ->
            val output = when (probe.label) {
                "sh" -> "AgentX Ubuntu OK"
                "bash" -> "GNU bash, version 5.2.21(1)-release"
                // What an Android host root would answer: the commands run, the root is wrong.
                "os-release" -> "ID=android\nPRETTY_NAME=\"Android\""
                else -> probe.expectOutput ?: ""
            }
            UbuntuProbeResult(probe.label, probe.command, 0, output)
        }.verify()

        assertFalse(result.ok)
        // sh, bash and os-release ran; the sequence stops at the probe that named the wrong root.
        assertEquals(3, result.results.size)
        assertTrue(result.failure!!.contains("Ubuntu"), result.failure!!)
    }

    @Test
    fun `the first probe that fails stops the sequence and is named`() {
        val result = verifier { probe ->
            if (probe.label == "bash") {
                UbuntuProbeResult(probe.label, probe.command, 127, "bash: not found")
            } else {
                UbuntuProbeResult(probe.label, probe.command, 0, probe.expectOutput ?: "")
            }
        }.verify()

        assertFalse(result.ok)
        // sh answered, bash did not: nothing after it is run.
        assertEquals(2, result.results.size)
        assertTrue(result.failure!!.contains("/bin/bash --version"))
    }

    @Test
    fun `a zero exit that is not a Ubuntu shell is refused`() {
        // A legacy Termux shell exits 0 too; the output is what proves which userland answered.
        val result = verifier { probe ->
            UbuntuProbeResult(probe.label, probe.command, 0, "PREFIX=/data/data/com.agentx.app/files/usr")
        }.verify()

        assertFalse(result.ok)
        assertTrue(result.failure!!.contains("AgentX Ubuntu OK"))
    }

    @Test
    fun `a runner that throws is reported, not propagated`() {
        val result = verifier { probe ->
            throw IllegalStateException("could not start ${probe.label}")
        }.verify()

        assertFalse(result.ok)
        assertEquals(1, result.results.size)
        assertTrue(result.failure!!.contains("/bin/sh"))
    }
}
