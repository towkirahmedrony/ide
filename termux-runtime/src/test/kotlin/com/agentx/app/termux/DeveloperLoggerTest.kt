package com.agentx.app.termux

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeveloperLoggerTest {

    private lateinit var directory: File
    private lateinit var logFile: File

    @BeforeTest
    fun setUp() {
        directory = File.createTempFile("developer-logger", "dir").apply {
            delete()
            mkdirs()
        }
        logFile = File(directory, "diagnostics/terminal.log")
        DeveloperLogger.attach(logFile, maxBytes = 2048)
        DeveloperLogger.clear()
    }

    @AfterTest
    fun tearDown() {
        DeveloperLogger.clear()
        DeveloperLogger.attach(null)
    }

    @Test
    fun `log writes timestamp level category and message`() {
        DeveloperLogger.info(DeveloperLogCategory.TERMINAL, "Open requested")
        val contents = DeveloperLogger.readAll()
        assertTrue(contents.contains("INFO [TERMINAL] Open requested"), contents)
    }

    @Test
    fun `error records class message and stack`() {
        DeveloperLogger.error(
            DeveloperLogCategory.ERROR,
            "process start failure",
            UnsatisfiedLinkError("dlopen failed: library \"libproot.so\" not found"),
        )
        val contents = DeveloperLogger.readAll()
        assertTrue(contents.contains("exception class=java.lang.UnsatisfiedLinkError"), contents)
        assertTrue(contents.contains("dlopen failed: library \"libproot.so\" not found"), contents)
        assertTrue(contents.contains("stack:"), contents)
    }

    @Test
    fun `readAll returns persisted file contents`() {
        DeveloperLogger.info(DeveloperLogCategory.PROCESS, "Starting process")
        assertTrue(logFile.isFile)
        assertTrue(logFile.readText().contains("[PROCESS] Starting process"))
        assertTrue(DeveloperLogger.readAll().contains("[PROCESS] Starting process"))
    }

    @Test
    fun `clear empties memory and file`() {
        DeveloperLogger.warn(DeveloperLogCategory.ROOTFS, "missing bash")
        DeveloperLogger.clear()
        assertTrue(DeveloperLogger.readAll().isBlank())
        assertTrue(logFile.readText().isBlank())
    }

    @Test
    fun `environment values are logged and secrets are redacted`() {
        DeveloperLogger.logEnvironment(
            arrayOf(
                "PATH=/usr/bin",
                "HOME=/root",
                "SHELL=/bin/bash",
                "TERM=xterm-256color",
                "TMPDIR=/tmp",
                "PROOT_LOADER=/lib/libproot_loader.so",
                "PROOT_L2S_DIR=/l2s",
                "API_KEY=super-secret",
                "PASSWORD=hidden",
            ),
        )
        val contents = DeveloperLogger.readAll()
        assertTrue(contents.contains("PATH=/usr/bin"), contents)
        assertTrue(contents.contains("HOME=/root"), contents)
        assertTrue(contents.contains("PROOT_LOADER=/lib/libproot_loader.so"), contents)
        assertTrue(contents.contains("PROOT_LOADER32=(unset)"), contents)
        assertFalse(contents.contains("super-secret"), contents)
        assertFalse(contents.contains("PASSWORD=hidden"), contents)
    }

    @Test
    fun `file rotates when it exceeds the bound`() {
        val payload = "x".repeat(200)
        repeat(30) { index ->
            DeveloperLogger.info(DeveloperLogCategory.OUTPUT, "line-$index $payload")
        }
        assertTrue(logFile.length() <= 2048L, "log size ${logFile.length()}")
        val contents = DeveloperLogger.readAll()
        assertTrue(contents.contains("[OUTPUT]"), contents)
    }

    @Test
    fun `lines flow updates when a log is written and when cleared`() {
        DeveloperLogger.info(DeveloperLogCategory.PROCESS, "Starting process")
        val afterWrite = DeveloperLogger.lines.value
        kotlin.test.assertTrue(afterWrite.any { it.contains("[PROCESS] Starting process") }, afterWrite.toString())
        DeveloperLogger.clear()
        kotlin.test.assertTrue(DeveloperLogger.lines.value.isEmpty())
    }

    @Test
    fun `captureSnapshot appends structured body through the logger`() {
        DeveloperLogger.captureSnapshot("=== AgentX Runtime Snapshot ===\nApp version: 0.1.0")
        val contents = DeveloperLogger.readAll()
        kotlin.test.assertTrue(contents.contains("Runtime snapshot captured"), contents)
        kotlin.test.assertTrue(contents.contains("=== AgentX Runtime Snapshot ==="), contents)
        kotlin.test.assertTrue(contents.contains("App version: 0.1.0"), contents)
    }

    @Test
    fun `flagValues extracts bind mounts without changing the command`() {
        val arguments = listOf(
            "proot",
            "-r",
            "/data/rootfs",
            "-b",
            "/dev",
            "-b",
            "/tmp:/tmp",
            "/bin/bash",
            "--login",
        )
        kotlin.test.assertEquals(listOf("/dev", "/tmp:/tmp"), DeveloperLogger.flagValues(arguments, "-b"))
        kotlin.test.assertEquals(listOf("/data/rootfs"), DeveloperLogger.flagValues(arguments, "-r"))
    }
}
