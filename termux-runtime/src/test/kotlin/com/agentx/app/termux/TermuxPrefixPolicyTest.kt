package com.agentx.app.termux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class TermuxPrefixPolicyTest {

    @Test
    fun `agentx prefix is supported as a custom bootstrap target`() {
        val support = TermuxPrefixPolicy.evaluate(
            TermuxPaths.forAppDataDir(TermuxPaths.AGENTX_APP_DATA_DIR),
        )
        val supported = assertIs<TermuxPrefixSupport.Supported>(support)
        assertEquals(TermuxPaths.AGENTX_PREFIX, supported.prefix)
        assertFalse(supported.official)
    }

    @Test
    fun `official termux prefix is never allowed inside agentx`() {
        val support = TermuxPrefixPolicy.evaluate(
            TermuxPaths.forAppDataDir(TermuxPaths.OFFICIAL_APP_DATA_DIR),
        )
        val unsupported = assertIs<TermuxPrefixSupport.Unsupported>(support)
        assertEquals(TermuxPaths.OFFICIAL_PREFIX, unsupported.prefix)
        assertTrue(unsupported.reason.contains(TermuxPaths.OFFICIAL_PREFIX))
        assertFalse(TermuxPrefixPolicy.canLaunch(
            TermuxPaths.forAppDataDir(TermuxPaths.OFFICIAL_APP_DATA_DIR),
            "${TermuxPaths.OFFICIAL_PREFIX}/bin/bash",
        ))
    }

    @Test
    fun `another app data directory is refused`() {
        val support = TermuxPrefixPolicy.evaluate(TermuxPaths.forAppDataDir("/data/data/com.other.app"))
        val unsupported = assertIs<TermuxPrefixSupport.Unsupported>(support)
        assertTrue(unsupported.reason.contains(TermuxPaths.AGENTX_PREFIX))
    }

    @Test
    fun `artifact prefix must match the runtime prefix`() {
        val mismatch = TermuxPrefixPolicy.requireMatchingPrefix(
            runtimePrefix = TermuxPaths.AGENTX_PREFIX,
            artifactPrefix = TermuxPaths.OFFICIAL_PREFIX,
        )
        val unsupported = assertIs<TermuxPrefixSupport.Unsupported>(mismatch)
        assertTrue(unsupported.reason.contains(TermuxPaths.OFFICIAL_PREFIX))

        val match = TermuxPrefixPolicy.requireMatchingPrefix(
            runtimePrefix = TermuxPaths.AGENTX_PREFIX,
            artifactPrefix = TermuxPaths.AGENTX_PREFIX,
        )
        assertIs<TermuxPrefixSupport.Supported>(match)
    }

    @Test
    fun `an over long prefix is refused`() {
        val deep = "/data/data/" + "a".repeat(TermuxPaths.MAX_PREFIX_LENGTH)
        val support = TermuxPrefixPolicy.evaluate(TermuxPaths.forAppDataDir(deep))
        val unsupported = assertIs<TermuxPrefixSupport.Unsupported>(support)
        assertTrue(unsupported.reason.contains("over the ${TermuxPaths.MAX_PREFIX_LENGTH}"))
    }
}
