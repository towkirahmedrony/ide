package com.agentx.app.ui.ide.state

import com.agentx.app.termux.DeveloperLogCategory
import com.agentx.app.termux.DeveloperLogFilter
import com.agentx.app.termux.DeveloperLogger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeveloperLogsViewModelTest {

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
    fun `filter and search do not change stored logs`() {
        DeveloperLogger.info(DeveloperLogCategory.ROOTFS, "RootFS path = /data/rootfs")
        DeveloperLogger.error(DeveloperLogCategory.PROCESS, "Process exited with code 127")
        val viewModel = DeveloperLogsViewModel(
            appVersion = "0.1.0",
            androidVersion = { "14" },
            deviceAbi = { "arm64-v8a" },
        )
        val stored = DeveloperLogger.readAll()
        viewModel.updateFilter(DeveloperLogFilter.ERROR)
        viewModel.updateQuery("127")
        val visible = viewModel.displayed(viewModel.lines.value)
        assertEquals(1, visible.size)
        assertEquals(stored, DeveloperLogger.readAll())
    }

    @Test
    fun `snapshot uses available values and N-A for the rest`() {
        DeveloperLogger.info(DeveloperLogCategory.PROCESS, "executable = /lib/libproot.so")
        DeveloperLogger.info(DeveloperLogCategory.PROCESS, "PID=99")
        DeveloperLogger.info(DeveloperLogCategory.ENV, "HOME=/root")
        val viewModel = DeveloperLogsViewModel(
            appVersion = "0.1.0",
            androidVersion = { "14" },
            deviceAbi = { "arm64-v8a" },
        )
        val text = viewModel.buildSnapshot().render()
        assertTrue(text.contains("App version: 0.1.0"), text)
        assertTrue(text.contains("Android version: 14"), text)
        assertTrue(text.contains("Device ABI: arm64-v8a"), text)
        assertTrue(text.contains("executable: /lib/libproot.so"), text)
        assertTrue(text.contains("PID: 99"), text)
        assertTrue(text.contains("HOME: /root"), text)
        assertTrue(text.contains("nativeLibraryDir: N/A"), text)
        assertTrue(text.contains("libproot.so: N/A"), text)
        assertTrue(text.contains("state: N/A"), text)
    }

    @Test
    fun `clear empties the logger used by the screen`() {
        DeveloperLogger.warn(DeveloperLogCategory.PROOT, "missing loader")
        val viewModel = DeveloperLogsViewModel(appVersion = "0.1.0")
        viewModel.clear()
        assertTrue(DeveloperLogger.readAll().isBlank())
        assertTrue(viewModel.lines.value.isEmpty())
    }
}
