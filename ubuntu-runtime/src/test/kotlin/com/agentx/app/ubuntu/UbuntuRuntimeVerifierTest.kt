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
        assertEquals(6, result.results.size)
        assertTrue(result.summary.contains("Guest verified through PRoot"))
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
