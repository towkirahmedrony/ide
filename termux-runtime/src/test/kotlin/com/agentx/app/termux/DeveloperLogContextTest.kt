package com.agentx.app.termux

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Context-aware visibility and per-entry copying of the Developer Log, plus the
 * single-entry exception format that makes copying an error complete.
 */
class DeveloperLogContextTest {

    @BeforeTest
    fun setUp() {
        DeveloperLogger.attach(null)
        DeveloperLogger.clear()
        DeveloperLogVisibility.reset()
    }

    @AfterTest
    fun tearDown() {
        DeveloperLogger.clear()
        DeveloperLogger.attach(null)
        DeveloperLogVisibility.reset()
    }

    private val lines = listOf(
        "2026-10-01 21:42:01.000 INFO [TERMINAL] Open requested",
        "2026-10-01 21:42:02.000 INFO [PROCESS] executable = /lib/libproot.so",
        "2026-10-01 21:42:03.000 ERROR [ERROR] process start failure",
        "2026-10-01 21:42:04.000 INFO [AGENT] Agent turn started",
        "2026-10-01 21:42:05.000 INFO [MODEL] completion finished",
        "2026-10-01 21:42:06.000 INFO [APP] workspace opened",
    )

    @Test
    fun `terminal logs are hidden while the terminal page is closed`() {
        val visible = visibleDeveloperLogs(lines, DeveloperLogFilter.ALL, "", showTerminalLogs = false)
        assertEquals(
            listOf("ERROR", "AGENT", "MODEL", "APP"),
            visible.map { it.category?.name },
            visible.toString(),
        )
    }

    @Test
    fun `terminal logs become visible when the terminal page is open`() {
        val visible = visibleDeveloperLogs(lines, DeveloperLogFilter.ALL, "", showTerminalLogs = true)
        assertEquals(lines.size, visible.size)
        assertTrue(visible.any { it.category == DeveloperLogCategory.TERMINAL })
        assertTrue(visible.any { it.category == DeveloperLogCategory.PROCESS })
    }

    @Test
    fun `agent model and application logs are unaffected by the terminal page`() {
        val hidden = visibleDeveloperLogs(lines, DeveloperLogFilter.ALL, "", showTerminalLogs = false)
        assertTrue(hidden.any { it.category == DeveloperLogCategory.AGENT })
        assertTrue(hidden.any { it.category == DeveloperLogCategory.MODEL })
        assertTrue(hidden.any { it.category == DeveloperLogCategory.APP })
        // A genuine error is never hidden just because it came from the runtime.
        assertTrue(hidden.any { it.level == DeveloperLogLevel.ERROR })
    }

    @Test
    fun `hiding terminal logs never changes the stored lines`() {
        val snapshot = lines.toList()
        visibleDeveloperLogs(lines, DeveloperLogFilter.ALL, "", showTerminalLogs = false)
        visibleDeveloperLogs(lines, DeveloperLogFilter.ALL, "", showTerminalLogs = true)
        assertEquals(snapshot, lines, "filtering must not mutate or drop history")
    }

    @Test
    fun `terminal source categories are classified explicitly`() {
        assertTrue(DeveloperLogCategory.TERMINAL.isTerminalSource)
        assertTrue(DeveloperLogCategory.PROOT.isTerminalSource)
        assertTrue(DeveloperLogCategory.ROOTFS.isTerminalSource)
        assertTrue(DeveloperLogCategory.PROCESS.isTerminalSource)
        assertTrue(DeveloperLogCategory.SESSION.isTerminalSource)
        assertFalse(DeveloperLogCategory.AGENT.isTerminalSource)
        assertFalse(DeveloperLogCategory.MODEL.isTerminalSource)
        assertFalse(DeveloperLogCategory.APP.isTerminalSource)
        assertFalse(DeveloperLogCategory.STORAGE.isTerminalSource)
        assertFalse(DeveloperLogCategory.ERROR.isTerminalSource)
    }

    @Test
    fun `visibility follows the terminal page lifecycle`() {
        assertFalse(DeveloperLogVisibility.terminalPageActive.value)
        DeveloperLogVisibility.enterTerminalPage()
        assertTrue(DeveloperLogVisibility.terminalPageActive.value)
        DeveloperLogVisibility.exitTerminalPage()
        assertFalse(DeveloperLogVisibility.terminalPageActive.value)
    }

    @Test
    fun `copying one entry yields only that entry complete line`() {
        val line = DeveloperLogLine.parse(
            "2026-10-01 21:42:03.123 ERROR [AGENT] Agent turn failed correlationId=abc123 errorCode=MODEL_FAILURE",
        )
        val copy = line.copyText
        assertTrue(copy.contains("21:42:03.123"), copy)
        assertTrue(copy.contains("ERROR"), copy)
        assertTrue(copy.contains("[AGENT]"), copy)
        assertTrue(copy.contains("correlationId=abc123"), copy)
        assertTrue(copy.contains("errorCode=MODEL_FAILURE"), copy)
        assertFalse(copy.contains("Open requested"), "copy must not include other entries")
    }

    @Test
    fun `an exception is stored as one entry carrying its message and stack`() {
        DeveloperLogger.error(
            DeveloperLogCategory.ERROR,
            "process start failure",
            IllegalStateException("boom"),
        )
        val entries = DeveloperLogger.readAll().lines().filter { it.isNotBlank() }
        assertEquals(1, entries.size, entries.toString())
        val entry = entries.single()
        assertTrue(entry.contains("exception class=java.lang.IllegalStateException"), entry)
        assertTrue(entry.contains("message=boom"), entry)
        assertTrue(entry.contains("stack:"), entry)
        assertTrue(entry.contains("DeveloperLogContextTest"), entry)
        // The whole entry is parseable as one line, so a single tap copies all of it.
        val parsed = DeveloperLogLine.parse(entry)
        assertEquals(DeveloperLogCategory.ERROR, parsed.category)
        assertTrue(parsed.copyText.contains("stack:"))
    }
}
