package com.agentx.app.ui.ide.state

import com.agentx.app.termux.DeveloperLogCategory
import com.agentx.app.termux.DeveloperLogger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Developer Log view follows the navigation context: terminal diagnostics only
 * show when the destination was opened from the Terminal page, and hiding them never
 * deletes the stored history.
 */
class DeveloperLogsVisibilityTest {

    @BeforeTest
    fun setUp() {
        DeveloperLogger.attach(null)
        DeveloperLogger.clear()
    }

    @AfterTest
    fun tearDown() {
        DeveloperLogger.clear()
        DeveloperLogger.attach(null)
    }

    @Test
    fun `terminal logs are hidden when the log was not opened from the terminal page`() {
        DeveloperLogger.info(DeveloperLogCategory.TERMINAL, "Open requested")
        DeveloperLogger.info(DeveloperLogCategory.PROCESS, "executable = /lib/libproot.so")
        DeveloperLogger.info(DeveloperLogCategory.ROOTFS, "RootFS path = /data/rootfs")
        DeveloperLogger.info(DeveloperLogCategory.AGENT, "Agent turn started")
        DeveloperLogger.error(DeveloperLogCategory.ERROR, "process start failure")

        val viewModel = DeveloperLogsViewModel(appVersion = "0.1.0", showTerminalLogs = false)
        val visible = viewModel.displayed(viewModel.lines.value)

        assertEquals(
            listOf(DeveloperLogCategory.AGENT, DeveloperLogCategory.ERROR),
            visible.map { it.category },
            visible.toString(),
        )

        // Nothing was lost: the persistent log and "Copy/Share" still carry every line.
        val full = viewModel.fullLog()
        assertTrue(full.contains("[TERMINAL] Open requested"), full)
        assertTrue(full.contains("[PROCESS] executable"), full)
        assertTrue(full.contains("[ROOTFS] RootFS path"), full)
    }

    @Test
    fun `terminal logs are visible when the log was opened from the terminal page`() {
        DeveloperLogger.info(DeveloperLogCategory.TERMINAL, "Open requested")
        DeveloperLogger.info(DeveloperLogCategory.PTY, "PTY open handle=abc")

        val viewModel = DeveloperLogsViewModel(appVersion = "0.1.0", showTerminalLogs = true)
        val visible = viewModel.displayed(viewModel.lines.value)

        assertEquals(2, visible.size)
        assertTrue(visible.any { it.category == DeveloperLogCategory.TERMINAL })
        assertTrue(visible.any { it.category == DeveloperLogCategory.PTY })
    }
}
