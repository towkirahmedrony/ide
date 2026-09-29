package com.agentx.app.termux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

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
    fun `another app data directory is refused with an actionable reason`() {
        // This is the default shape of the build: the packages are compiled for
        // /data/data/com.termux, so installing them under our own directory has to be refused
        // rather than half-done.
        val support = TermuxPrefixPolicy.evaluate(TermuxPaths.forAppDataDir("/data/data/com.agentx.app"))
        val unsupported = assertIs<TermuxPrefixSupport.Unsupported>(support)

        assertTrue(unsupported.reason.contains(TermuxPaths.OFFICIAL_PREFIX))
        assertTrue(unsupported.reason.contains("/data/data/com.agentx.app/files/usr"))
        assertTrue(unsupported.remedy.contains("agentx.termux.officialPrefix"))
    }

    @Test
    fun `an over long prefix is refused`() {
        val deep = "/data/data/" + "a".repeat(TermuxPaths.MAX_PREFIX_LENGTH)
        val support = TermuxPrefixPolicy.evaluate(TermuxPaths.forAppDataDir(deep))
        val unsupported = assertIs<TermuxPrefixSupport.Unsupported>(support)
        assertTrue(unsupported.reason.contains("over the ${TermuxPaths.MAX_PREFIX_LENGTH}"))
    }
}
