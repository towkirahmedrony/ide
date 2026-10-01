package com.agentx.app.termux

import kotlin.test.Test
import kotlin.test.assertTrue

class DeveloperRuntimeSnapshotTest {

    @Test
    fun `missing fields render as N-A`() {
        val text = DeveloperRuntimeSnapshot().render()
        assertTrue(text.contains("=== AgentX Runtime Snapshot ==="), text)
        assertTrue(text.contains("App version: N/A"), text)
        assertTrue(text.contains("nativeLibraryDir: N/A"), text)
        assertTrue(text.contains("path: N/A"), text)
        assertTrue(text.contains("bin/bash: N/A"), text)
        assertTrue(text.contains("libproot.so: N/A"), text)
        assertTrue(text.contains("PID: N/A"), text)
        assertTrue(text.contains("HOME: N/A"), text)
        assertTrue(text.contains("=== End Snapshot ==="), text)
        assertTrue(!text.contains("null"), text)
    }

    @Test
    fun `known fields are written without invention`() {
        val text = DeveloperRuntimeSnapshot(
            appVersion = "0.1.0",
            androidVersion = "14",
            deviceAbi = "arm64-v8a",
            nativeLibraryDir = "/data/app/lib",
            rootfsPath = "/data/rootfs",
            rootfsExists = true,
            rootfsBash = false,
            libproot = true,
            sessionState = "FAILED",
            sessionPid = "4321",
            envHome = "/root",
        ).render()
        assertTrue(text.contains("App version: 0.1.0"), text)
        assertTrue(text.contains("Device ABI: arm64-v8a"), text)
        assertTrue(text.contains("exists: true"), text)
        assertTrue(text.contains("bin/bash: false"), text)
        assertTrue(text.contains("libproot.so: true"), text)
        assertTrue(text.contains("libtalloc.so: N/A"), text)
        assertTrue(text.contains("state: FAILED"), text)
        assertTrue(text.contains("PID: 4321"), text)
        assertTrue(text.contains("HOME: /root"), text)
        assertTrue(text.contains("PATH: N/A"), text)
    }
}
