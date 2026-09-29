package com.agentx.app.termux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class TermuxPrefixPolicyTest {

    @Test
    fun `termux own data directory is supported`() {
        val support = TermuxPrefixPolicy.evaluate(
            TermuxPaths.forAppDataDir(TermuxPaths.OFFICIAL_APP_DATA_DIR),
        )
        val supported = assertIs<TermuxPrefixSupport.Supported>(support)
        assertEquals(TermuxPaths.OFFICIAL_PREFIX, supported.prefix)
        assertTrue(supported.official)
    }

    @Test
    fun `custom app data directory is refused because official binaries are hard coded`() {
        val support = TermuxPrefixPolicy.evaluate(TermuxPaths.forAppDataDir("/data/data/com.agentx.app"))
        val unsupported = assertIs<TermuxPrefixSupport.Unsupported>(support)
        assertTrue(unsupported.reason.contains(TermuxPaths.OFFICIAL_PREFIX))
    }

    @Test
    fun `another app data directory is refused with an actionable reason`() {
        val support = TermuxPrefixPolicy.evaluate(TermuxPaths.forAppDataDir("/data/data/com.other.app"))
        val unsupported = assertIs<TermuxPrefixSupport.Unsupported>(support)

        assertTrue(unsupported.reason.contains(TermuxPaths.OFFICIAL_PREFIX))
    }

    @Test
    fun `an over long prefix is refused`() {
        val deep = "/data/data/" + "a".repeat(TermuxPaths.MAX_PREFIX_LENGTH)
        val support = TermuxPrefixPolicy.evaluate(TermuxPaths.forAppDataDir(deep))
        val unsupported = assertIs<TermuxPrefixSupport.Unsupported>(support)
        assertTrue(unsupported.reason.contains("over the ${TermuxPaths.MAX_PREFIX_LENGTH}"))
    }
}
