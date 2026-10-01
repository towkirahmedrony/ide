package com.agentx.app.termux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeveloperLogLineTest {

    @Test
    fun `parse extracts time level category and message`() {
        val line = DeveloperLogLine.parse(
            "2026-10-01 21:42:01.123 INFO [ROOTFS] RootFS path = /data/rootfs",
        )
        assertEquals("21:42:01", line.time)
        assertEquals(DeveloperLogLevel.INFO, line.level)
        assertEquals(DeveloperLogCategory.ROOTFS, line.category)
        assertEquals("RootFS path = /data/rootfs", line.message)
        assertEquals("21:42:01 INFO  [ROOTFS] RootFS path = /data/rootfs", line.display)
    }

    @Test
    fun `error display keeps ERROR aligned with INFO`() {
        val line = DeveloperLogLine.parse(
            "2026-10-01 21:42:02.000 ERROR [PROCESS] Process exited with code 127",
        )
        assertEquals("21:42:02 ERROR [PROCESS] Process exited with code 127", line.display)
    }

    @Test
    fun `filters match level or category without changing storage`() {
        val lines = listOf(
            "2026-10-01 21:42:01.123 INFO [ROOTFS] RootFS path = /data/rootfs",
            "2026-10-01 21:42:02.000 ERROR [PROCESS] Process exited with code 127",
            "2026-10-01 21:42:03.000 WARN [PROOT] libproot.so exists=false",
            "2026-10-01 21:42:04.000 INFO [SESSION] IDLE -> STARTING",
            "2026-10-01 21:42:05.000 INFO [TERMINAL] Open requested",
            "2026-10-01 21:42:06.000 INFO [PTY] PTY close handle=abc pid=1",
        )
        assertEquals(1, visibleDeveloperLogs(lines, DeveloperLogFilter.ERROR, "").size)
        assertEquals(1, visibleDeveloperLogs(lines, DeveloperLogFilter.WARNING, "").size)
        assertEquals(1, visibleDeveloperLogs(lines, DeveloperLogFilter.ROOTFS, "").size)
        assertEquals(1, visibleDeveloperLogs(lines, DeveloperLogFilter.PROOT, "").size)
        assertEquals(1, visibleDeveloperLogs(lines, DeveloperLogFilter.PROCESS, "").size)
        assertEquals(1, visibleDeveloperLogs(lines, DeveloperLogFilter.PTY, "").size)
        assertEquals(1, visibleDeveloperLogs(lines, DeveloperLogFilter.SESSION, "").size)
        assertEquals(1, visibleDeveloperLogs(lines, DeveloperLogFilter.TERMINAL, "").size)
        assertEquals(6, visibleDeveloperLogs(lines, DeveloperLogFilter.ALL, "").size)
        assertEquals(1, visibleDeveloperLogs(lines, DeveloperLogFilter.ALL, "code 127").size)
        assertEquals(lines.size, lines.size)
    }

    @Test
    fun `lastLogValue reads the most recent matching prefix`() {
        val lines = listOf(
            "2026-10-01 21:42:01.000 INFO [PROCESS] executable = /old",
            "2026-10-01 21:42:02.000 INFO [ROOTFS] RootFS path = /data/rootfs",
            "2026-10-01 21:42:03.000 INFO [PROCESS] executable = /lib/libproot.so",
            "2026-10-01 21:42:04.000 INFO [PROCESS] PID=99 handle=abc",
        )
        assertEquals("/lib/libproot.so", lastLogValue(lines, DeveloperLogCategory.PROCESS, "executable = "))
        assertEquals("99 handle=abc", lastLogValue(lines, DeveloperLogCategory.PROCESS, "PID="))
        assertEquals("/data/rootfs", lastLogValue(lines, DeveloperLogCategory.ROOTFS, "RootFS path = "))
        assertEquals(null, lastLogValue(lines, DeveloperLogCategory.PTY, "PTY close"))
    }

    @Test
    fun `visible list is bounded`() {
        val lines = (0 until 800).map { index ->
            "2026-10-01 21:42:01.000 INFO [TERMINAL] line-$index"
        }
        val visible = visibleDeveloperLogs(lines, DeveloperLogFilter.ALL, "", limit = 500)
        assertEquals(500, visible.size)
        assertTrue(visible.last().message.contains("line-799"))
    }
}
