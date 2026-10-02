package com.agentx.app.model.ratelimit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuotaTest {

    @Test
    fun `unknown never blocks and is not a guessed number`() {
        assertFalse(Quota.Unknown.wouldExceed(used = 1_000_000, additional = 1_000_000))
        assertTrue(Quota.Unknown.isUnknown)
        assertEquals(null, Quota.of(null as Long?).knownValue)
    }

    @Test
    fun `known quota blocks only when exceeded`() {
        val quota = Quota.Known(10L)
        assertFalse(quota.wouldExceed(used = 9, additional = 1))
        assertTrue(quota.wouldExceed(used = 9, additional = 2))
        assertTrue(Quota.Known(0).wouldExceed(used = 0, additional = 1))
    }
}
