package com.agentx.app.ubuntu

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The storage audit's whole job is to answer "what is using the space" honestly, so these assert
 * that a reading is complete, classified, and reconcilable — the three ways a storage report can
 * mislead.
 */
class AgentxStorageAuditTest {

    private lateinit var filesDir: File
    private lateinit var cacheDir: File
    private lateinit var nativeLibraryDir: File

    @BeforeTest
    fun setUp() {
        filesDir = Files.createTempDirectory("agentx-audit-files").toFile()
        cacheDir = Files.createTempDirectory("agentx-audit-cache").toFile()
        nativeLibraryDir = Files.createTempDirectory("agentx-audit-native").toFile()

        // The persistent runtime, as an installed device looks: a rootfs with a toolchain, plus the
        // cached archive, the install scratch dirs and the legacy staging dir beside it.
        write("developer-runtime/rootfs/etc/os-release", 2_000)
        write("developer-runtime/rootfs/usr/bin/git", 40_000)
        write("developer-runtime/rootfs/usr/lib/python3.12/os.py", 30_000)
        write("developer-runtime/rootfs.installing/etc/os-release", 500)
        write("developer-runtime/rootfs-staging/etc/os-release", 400)
        write("developer-runtime/downloads/ubuntu-base.tar.gz", 12_000)
        write("developer-runtime/tmp/proot-1234", 300)
        write("developer-runtime/rootfs-verified.ok", 10)

        // Two project copies AgentX made, of two different projects.
        write("developer-runtime/workspaces/myproject-1a2b3c4d/src/Main.kt", 1_500)
        write("developer-runtime/workspaces/myproject-1a2b3c4d/README.md", 500)
        write("developer-runtime/workspaces/otherproject-9999/lib/util.kt", 700)

        // The app's own data and its diagnostics.
        write("skills/imported/SKILL.md", 800)
        write("agent-sessions/abc.json", 1_200)
        write("diagnostics/terminal.log", 900)
        write("terminal-diagnostics.log", 2_400)
        write("something-else/notes.txt", 100)

        write("cache/blobs/thumb.png", 640)

        // The APK's executable side, which Android reports as app size but which is not user data.
        writeNative("libproot.so", 250_000)
        writeNative("libproot_loader.so", 90_000)
    }

    @AfterTest
    fun tearDown() {
        filesDir.deleteRecursively()
        cacheDir.deleteRecursively()
        nativeLibraryDir.deleteRecursively()
    }

    private fun write(relative: String, bytes: Int) {
        val target = File(filesDir, relative)
        target.parentFile?.mkdirs()
        target.writeBytes(ByteArray(bytes) { 'x'.code.toByte() })
    }

    private fun writeNative(name: String, bytes: Int) {
        File(nativeLibraryDir, name).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(bytes) { 'x'.code.toByte() })
        }
    }

    private fun cache(relative: String, bytes: Int) {
        val target = File(cacheDir, relative)
        target.parentFile?.mkdirs()
        target.writeBytes(ByteArray(bytes) { 'x'.code.toByte() })
    }

    private fun measure(): StorageBreakdown = AgentxStorageAudit.measure(
        filesDir = filesDir.path,
        cacheDir = cacheDir.path,
        nativeLibraryDir = nativeLibraryDir.path,
    )

    private fun entry(breakdown: StorageBreakdown): (String) -> StorageEntry =
        { label -> breakdown.entries.first { it.label == label } }

    /** Every byte under a directory, counted the way the audit counts it. */
    private fun bytesUnder(root: File): Long = root.walkTopDown()
        .filter { it.isFile }
        .sumOf { it.length() }

    // --- classification ----------------------------------------------------

    @Test
    fun `the runtime is expanded rather than reported as one blob`() {
        val at = entry(measure())

        assertEquals(72_000L, at("Ubuntu rootfs + installed toolchain").usage.bytes)
        assertEquals(12_000L, at("Cached rootfs archive(s)").usage.bytes)
        assertEquals(300L, at("PRoot scratch (PROOT_TMP_DIR)").usage.bytes)
        assertEquals(500L, at("Rootfs under construction").usage.bytes)
        assertEquals(400L, at("Legacy staging directory").usage.bytes)
        assertEquals(10L, at("Developer runtime file: rootfs-verified.ok").usage.bytes)
    }

    @Test
    fun `each location is classified by what it actually is`() {
        val at = entry(measure())

        assertEquals(StorageClass.PERSISTENT_RUNTIME, at("Ubuntu rootfs + installed toolchain").storageClass)
        assertEquals(StorageClass.RUNTIME_CACHE, at("Cached rootfs archive(s)").storageClass)
        assertEquals(StorageClass.TEMPORARY, at("PRoot scratch (PROOT_TMP_DIR)").storageClass)
        assertEquals(StorageClass.TEMPORARY, at("Rootfs under construction").storageClass)
        assertEquals(StorageClass.PROJECT_COPY, at("Project copies (materialiser)").storageClass)
        assertEquals(StorageClass.PROJECT_COPY, at("Project mirrors (app-owned copies)").storageClass)
        assertEquals(StorageClass.APP_DATA, at("Installed skills").storageClass)
        assertEquals(StorageClass.APP_DATA, at("Agent sessions").storageClass)
        assertEquals(StorageClass.DIAGNOSTICS, at("Developer log").storageClass)
        assertEquals(StorageClass.DIAGNOSTICS, at("Terminal diagnostics").storageClass)
        assertEquals(StorageClass.TEMPORARY, at("Android cache (cacheDir)").storageClass)
        assertEquals(StorageClass.APK, at("APK native libraries (nativeLibraryDir)").storageClass)
    }

    @Test
    fun `every location under the app's storage is accounted for`() {
        val breakdown = measure()

        // Nothing is bucketed away: the entries' paths cover the tree, so the reading can be
        // compared with what Android reports instead of being a partial answer.
        val root = filesDir.path.trimEnd('/')
        val covered = breakdown.entries
            .filter { it.storageClass != StorageClass.APK }
            .map { it.path.removePrefix("$root/").substringBefore('/') }
            .toSet()
        val topLevel = filesDir.listFiles().orEmpty().map { it.name }.toSet()

        assertEquals(topLevel, covered)
    }

    // --- totals ------------------------------------------------------------

    @Test
    fun `the totals reconcile with the bytes on disk`() {
        val breakdown = measure()

        val expectedAppData = bytesUnder(filesDir) + bytesUnder(cacheDir)
        assertEquals(expectedAppData, breakdown.appPrivateBytes, "app-private bytes must not double-count")

        val expectedFiles = (filesDir.walkTopDown().count { it.isFile } +
            cacheDir.walkTopDown().count { it.isFile })
        assertEquals(expectedFiles, breakdown.appPrivateFiles)

        // Entries are disjoint: their sum is the total, and the APK is reported separately.
        val entryTotal = breakdown.entries
            .filter { it.storageClass != StorageClass.APK }
            .sumOf { it.usage.bytes }
        assertEquals(breakdown.appPrivateBytes, entryTotal)
        assertFalse(breakdown.truncated)
    }

    @Test
    fun `the breakdown groups the totals by class`() {
        val byClass = measure().byClass

        assertEquals(72_000L, byClass[StorageClass.PERSISTENT_RUNTIME])
        assertEquals(12_000L, byClass[StorageClass.RUNTIME_CACHE])
        assertEquals(1_840L, byClass[StorageClass.TEMPORARY])
        assertEquals(2_700L, byClass[StorageClass.PROJECT_COPY])
        assertEquals(2_000L, byClass[StorageClass.APP_DATA])
        assertEquals(3_300L, byClass[StorageClass.DIAGNOSTICS])
        assertEquals(340_000L, byClass[StorageClass.APK])
    }

    // --- per-project detail ------------------------------------------------

    @Test
    fun `each project copy is visible as itself`() {
        val copies = measure().projectCopies.associateBy { it.label }

        assertEquals(2_000L, copies.getValue("myproject-1a2b3c4d").usage.bytes)
        assertEquals(2, copies.getValue("myproject-1a2b3c4d").usage.files)
        assertEquals(700L, copies.getValue("otherproject-9999").usage.bytes)
        assertEquals(StorageClass.PROJECT_COPY, copies.getValue("otherproject-9999").storageClass)
    }

    @Test
    fun `a project copy total matches the per-project entries`() {
        val breakdown = measure()
        val copies = breakdown.projectCopies.sumOf { it.usage.bytes }
        val root = entry(breakdown)("Project copies (materialiser)").usage.bytes

        assertEquals(root, copies, "the per-project view must explain the directory it breaks down")
    }

    @Test
    fun `an app with no runtime measures cleanly`() {
        val empty = Files.createTempDirectory("agentx-audit-empty").toFile()
        try {
            val breakdown = AgentxStorageAudit.measure(filesDir = empty.path)

            assertTrue(breakdown.entries.isEmpty())
            assertTrue(breakdown.projectCopies.isEmpty())
            assertEquals(0L, breakdown.appPrivateBytes)
            assertFalse(breakdown.truncated)
        } finally {
            empty.deleteRecursively()
        }
    }

    @Test
    fun `a missing cache or native directory is simply not measured`() {
        val breakdown = AgentxStorageAudit.measure(filesDir = filesDir.path)

        assertTrue(breakdown.entries.none { it.label == "Android cache (cacheDir)" })
        assertEquals(0L, breakdown.byClass[StorageClass.APK] ?: 0L)
    }

    // --- rendering ---------------------------------------------------------

    @Test
    fun `the rendered report labels the classes`() {
        val text = measure().render()

        assertTrue(text.contains("app-private total:"), text)
        assertTrue(text.contains("PERSISTENT_RUNTIME"), text)
        assertTrue(text.contains("PROJECT_COPY"), text)
        assertTrue(text.contains("duplicated project data"), text)
        assertTrue(text.contains("project copies AgentX owns"), text)
        assertTrue(text.contains("myproject-1a2b3c4d"), text)
        assertTrue(text.contains("APK on disk:"), text)
        assertFalse(text.contains("lower bounds"), "an untruncated reading must not claim a floor")
    }

    @Test
    fun `byte counts render in units that fit the reading`() {
        assertEquals("512 B", AgentxStorageAudit.format(512))
        assertEquals("1.0 kB", AgentxStorageAudit.format(1024))
        assertEquals("1.5 kB", AgentxStorageAudit.format(1536))
        assertEquals("183.4 MB", AgentxStorageAudit.format(192_300_000))
        assertEquals("1.00 GB", AgentxStorageAudit.format(1_073_741_824))
    }
}
