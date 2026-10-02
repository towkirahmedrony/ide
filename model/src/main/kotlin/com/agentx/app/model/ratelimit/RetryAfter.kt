package com.agentx.app.model.ratelimit

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Parses an HTTP `Retry-After` value into a delay in milliseconds. */
object RetryAfter {

    fun parseMillis(headers: Map<String, List<String>>, nowMillis: Long = System.currentTimeMillis()): Long? {
        val raw = headers.entries
            .firstOrNull { (name, _) -> name.equals("Retry-After", ignoreCase = true) }
            ?.value
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return parseMillis(raw, nowMillis)
    }

    fun parseMillis(raw: String, nowMillis: Long = System.currentTimeMillis()): Long? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        trimmed.toDoubleOrNull()?.let { seconds ->
            if (seconds < 0.0) return null
            return (seconds * 1_000.0).toLong()
        }
        val at = parseHttpDate(trimmed) ?: return null
        return (at - nowMillis).coerceAtLeast(0L)
    }

    private fun parseHttpDate(raw: String): Long? {
        for (pattern in HTTP_DATE_PATTERNS) {
            val parsed = runCatching {
                val format = SimpleDateFormat(pattern, Locale.US)
                format.timeZone = TimeZone.getTimeZone("GMT")
                format.isLenient = false
                format.parse(raw)?.time
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    private val HTTP_DATE_PATTERNS = listOf(
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "EEEE, dd-MMM-yy HH:mm:ss zzz",
        "EEE MMM d HH:mm:ss yyyy",
    )
}
