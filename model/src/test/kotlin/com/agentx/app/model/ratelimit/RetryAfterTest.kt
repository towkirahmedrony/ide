package com.agentx.app.model.ratelimit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RetryAfterTest {

    @Test
    fun `numeric seconds are converted to milliseconds`() {
        assertEquals(2_000L, RetryAfter.parseMillis("2"))
        assertEquals(1_500L, RetryAfter.parseMillis("1.5"))
        assertEquals(0L, RetryAfter.parseMillis("0"))
    }

    @Test
    fun `header lookup is case insensitive`() {
        val headers = mapOf("retry-after" to listOf("3"))
        assertEquals(3_000L, RetryAfter.parseMillis(headers))
    }

    @Test
    fun `missing retry-after is null`() {
        assertNull(RetryAfter.parseMillis(emptyMap()))
        assertNull(RetryAfter.parseMillis(" "))
        assertNull(RetryAfter.parseMillis("-1"))
    }

    @Test
    fun `http-date retry-after is converted relative to now`() {
        val format = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
        format.timeZone = java.util.TimeZone.getTimeZone("GMT")
        val now = 1_700_000_000_000L
        val header = format.format(java.util.Date(now + 5_000L))
        assertEquals(5_000L, RetryAfter.parseMillis(header, nowMillis = now))
    }
}
