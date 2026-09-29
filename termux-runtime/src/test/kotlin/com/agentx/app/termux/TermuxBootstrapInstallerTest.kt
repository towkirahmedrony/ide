package com.agentx.app.termux

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TermuxBootstrapInstallerTest {

    private open class RecordingPosix : TermuxPosix {
        val chmodded = mutableListOf<String>()
        val links = mutableListOf<Pair<String, String>>()
        val executable = mutableSetOf<String>()

        override fun chmodOwnerExecute(path: String) {
            chmodded += path
            executable += path
            File(path).setExecutable(true, true)
        }

        override fun symlink(target: String, linkPath: String) {
            links += target to linkPath
            File(linkPath).writeText("link:$target")
        }

        override fun canExecute(file: File): Boolean =
            file.absolutePath in executable || (file.isFile && file.canExecute())
    }

    private fun tempPaths(): TermuxPaths {
        val root = File.createTempFile("agentx-termux", ".dir")
        root.delete()
        root.mkdirs()
        return TermuxPaths(appDataDir = root.absolutePath)
    }

    private fun installer(
        paths: TermuxPaths,
        posix: RecordingPosix,
        entry: TermuxBootstrapCatalog.Entry,
        archive: File? = null,
        downloadThrows: Exception? = null,
    ): TermuxBootstrapInstaller {
        val supported = TermuxPrefixSupport.Supported(prefix = paths.prefix, official = false)
        return TermuxBootstrapInstaller(
            paths = paths,
            supportedAbis = listOf(entry.androidAbi),
            download = { _, target, _ ->
                if (downloadThrows != null) throw downloadThrows
                val source = archive ?: error("no archive")
                source.copyTo(target, overwrite = true)
                target
            },
            prefixSupport = { supported },
            matchPrefixes = { runtime, artifact ->
                if (runtime == artifact) supported
                else TermuxPrefixSupport.Unsupported(runtime, "mismatch", "match prefixes")
            },
            resolveEntry = { entry },
            posix = posix,
            isExecutable = { file -> posix.canExecute(file) },
        )
    }

    private fun availableEntry(
        sha256: String,
        prefix: String = "/tmp/unused",
    ): TermuxBootstrapCatalog.Entry = TermuxBootstrapCatalog.Entry(
        androidAbi = "arm64-v8a",
        termuxArch = "aarch64",
        prefix = prefix,
        sourceRevision = "test",
        assetName = "bootstrap-aarch64.zip",
        url = "https://example.invalid/bootstrap-aarch64.zip",
        sha256 = sha256,
        archiveSizeBytes = 1L,
        fileCount = 1,
    )

    private fun writeZip(file: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private fun goodArchive(dir: File): File {
        val file = File(dir, "good.zip")
        val manifest = "bin/bash\u2190bin/sh\n".toByteArray()
        writeZip(
            file,
            mapOf(
                "bin/bash" to "#!/bin/sh\n".toByteArray(),
                "lib/.keep" to ByteArray(0),
                "SYMLINKS.txt" to manifest,
            ),
        )
        return file
    }

    @Test
    fun `pending catalog entries fail before any download`() {
        val paths = tempPaths()
        val posix = RecordingPosix()
        val pending = TermuxBootstrapCatalog.forAbi("arm64-v8a")!!
        val installer = TermuxBootstrapInstaller(
            paths = paths,
            supportedAbis = listOf("arm64-v8a"),
            download = { _, _, _ -> error("must not download") },
            prefixSupport = { TermuxPrefixSupport.Supported(it.prefix, official = false) },
            matchPrefixes = { runtime, artifact ->
                TermuxPrefixSupport.Supported(runtime, official = false).also { assertEquals(artifact, pending.prefix) }
            },
            resolveEntry = { pending },
            posix = posix,
        )
        val result = installer.provision()
        val unavailable = assertIs<TermuxProvisioning.ArtifactUnavailable>(result)
        assertEquals("arm64-v8a", unavailable.abi)
        assertTrue(unavailable.reason.contains("No custom AgentX bootstrap is available yet"))
        assertFalse(File(paths.prefix).exists())
    }

    @Test
    fun `official termux prefix is refused before extraction`() {
        val paths = TermuxPaths.forAppDataDir(TermuxPaths.OFFICIAL_APP_DATA_DIR)
        val installer = TermuxBootstrapInstaller(
            paths = paths,
            supportedAbis = listOf("arm64-v8a"),
            download = { _, _, _ -> error("must not download") },
            posix = RecordingPosix(),
        )
        val result = installer.provision()
        val unsupported = assertIs<TermuxProvisioning.Unsupported>(result)
        assertTrue(unsupported.reason.contains(TermuxPaths.OFFICIAL_PREFIX))
        assertFalse(installer.isInstalled())
    }

    @Test
    fun `checksum mismatch deletes the archive and extracts nothing`() {
        val paths = tempPaths()
        val posix = RecordingPosix()
        val cache = File(paths.downloadDir).also { it.mkdirs() }
        val archive = goodArchive(cache)
        val digest = TermuxBootstrapInstaller.sha256(archive)
        val wrong = "0".repeat(64)
        assertTrue(digest != wrong)
        val entry = availableEntry(sha256 = wrong, prefix = paths.prefix)
        val installer = installer(paths, posix, entry, archive)
        val result = installer.provision()
        val failed = assertIs<TermuxProvisioning.Failed>(result)
        assertEquals(TermuxInstallStage.CHECKSUM, failed.stage)
        assertEquals("checksum", failed.failed)
        assertFalse(File(paths.downloadDir, entry.assetName).exists())
        assertFalse(File(paths.prefix).exists())
        assertFalse(installer.isInstalled())
    }

    @Test
    fun `unsafe zip paths abort extraction`() {
        val paths = tempPaths()
        val posix = RecordingPosix()
        val archive = File(File(paths.downloadDir).also { it.mkdirs() }, "unsafe.zip")
        writeZip(
            archive,
            mapOf(
                "../../etc/passwd" to "root\n".toByteArray(),
                "SYMLINKS.txt" to "bin/bash\u2190bin/sh\n".toByteArray(),
            ),
        )
        val entry = availableEntry(sha256 = TermuxBootstrapInstaller.sha256(archive), prefix = paths.prefix)
        val result = installer(paths, posix, entry, archive).provision()
        val failed = assertIs<TermuxProvisioning.Failed>(result)
        assertEquals(TermuxInstallStage.EXTRACTION, failed.stage)
        assertTrue(failed.message.contains("Unsafe archive entry"))
        assertFalse(File(paths.prefix).exists())
    }

    @Test
    fun `symlink escape is rejected`() {
        val paths = tempPaths()
        val posix = RecordingPosix()
        val archive = File(File(paths.downloadDir).also { it.mkdirs() }, "links.zip")
        writeZip(
            archive,
            mapOf(
                "bin/bash" to "ok".toByteArray(),
                // From bin/sh, one level up is the prefix itself; two levels up leaves it.
                "SYMLINKS.txt" to "../../escape\u2190bin/sh\n".toByteArray(),
            ),
        )
        val entry = availableEntry(sha256 = TermuxBootstrapInstaller.sha256(archive), prefix = paths.prefix)
        val result = installer(paths, posix, entry, archive).provision()
        val failed = assertIs<TermuxProvisioning.Failed>(result)
        assertEquals(TermuxInstallStage.SYMLINK, failed.stage)
        assertTrue(posix.links.isEmpty())
    }

    @Test
    fun `a real bootstrap manifest installs every link`() {
        // The shapes bootstrap-aarch64.zip actually ships: a bare-name multicall target, a
        // relative target with `..`, and an absolute target into the final prefix. All three
        // must be created; refusing any of them would abort a real install.
        val paths = tempPaths()
        val posix = RecordingPosix()
        val archive = File(File(paths.downloadDir).also { it.mkdirs() }, "real.zip")
        val manifest = listOf(
            "coreutils\u2190./bin/ls",
            "../term.h\u2190./include/ncursesw/term.h",
            "${paths.prefix}/share/termux-keyring/mradityaalok.gpg\u2190./share/pacman/keyrings/mradityaalok.gpg",
        ).joinToString("\n") + "\n"
        writeZip(
            archive,
            mapOf(
                "bin/coreutils" to "ok".toByteArray(),
                "include/term.h" to "ok".toByteArray(),
                "SYMLINKS.txt" to manifest.toByteArray(),
            ),
        )
        val entry = availableEntry(sha256 = TermuxBootstrapInstaller.sha256(archive), prefix = paths.prefix)
        val result = installer(paths, posix, entry, archive).provision()
        val installed = assertIs<TermuxProvisioning.Installed>(result)
        assertEquals(3, installed.symlinks)

        // The links are created inside the staging prefix, because the whole tree is
        // unpacked there and then renamed to `usr` in one move. A relative target such as
        // `../term.h` therefore still resolves after the rename, and the absolute target
        // below -- written against the final prefix, as upstream writes it -- is dangling
        // while staged and resolves once the rename has happened. resolveSymlink
        // canonicalises, so the expected base is the canonical staging path.
        val staging = File(paths.stagingPrefix).canonicalPath
        assertEquals(
            listOf(
                "coreutils" to "$staging/bin/ls",
                "../term.h" to "$staging/include/ncursesw/term.h",
                "${paths.prefix}/share/termux-keyring/mradityaalok.gpg" to
                    "$staging/share/pacman/keyrings/mradityaalok.gpg",
            ),
            posix.links,
        )
    }

    @Test
    fun `executables receive owner execute and install is atomic`() {
        val paths = tempPaths()
        val posix = RecordingPosix()
        val archive = goodArchive(File(paths.downloadDir).also { it.mkdirs() })
        val entry = availableEntry(sha256 = TermuxBootstrapInstaller.sha256(archive), prefix = paths.prefix)
        val result = installer(paths, posix, entry, archive).provision()
        val installed = assertIs<TermuxProvisioning.Installed>(result)
        assertEquals(1, installed.symlinks)
        assertTrue(posix.chmodded.any { it.endsWith("/bin/bash") })
        assertTrue(File(paths.prefix).isDirectory)
        assertFalse(File(paths.stagingPrefix).exists())
        assertTrue(File(paths.tmp).isDirectory)
        assertTrue(File(paths.home).isDirectory)
        assertTrue(File(paths.homeStorage).isDirectory)
        assertTrue(File(paths.workspaces).isDirectory)
        assertTrue(File(paths.prefix, TermuxBootstrapArchive.INSTALL_MARKER).isFile)
        val verifier = installer(paths, posix, entry, archive)
        assertTrue(verifier.isInstalled())
    }

    @Test
    fun `a prefix without an executable shell is not installed`() {
        val paths = tempPaths()
        File(paths.bin).mkdirs()
        File(paths.lib).mkdirs()
        File(paths.bin, "bash").writeText("not executable")
        File(paths.prefix, TermuxBootstrapArchive.INSTALL_MARKER).apply {
            parentFile?.mkdirs()
            writeText("ok\n")
        }
        val posix = RecordingPosix()
        val installer = TermuxBootstrapInstaller(
            paths = paths,
            supportedAbis = listOf("arm64-v8a"),
            prefixSupport = { TermuxPrefixSupport.Supported(it.prefix, official = false) },
            posix = posix,
            isExecutable = { false },
        )
        assertFalse(installer.isInstalled())
    }

    @Test
    fun `download failures are labelled download`() {
        val paths = tempPaths()
        val posix = RecordingPosix()
        val entry = availableEntry(sha256 = "a".repeat(64), prefix = paths.prefix)
        val installer = installer(
            paths = paths,
            posix = posix,
            entry = entry,
            downloadThrows = java.io.IOException("offline"),
        )
        val result = installer.provision()
        val failed = assertIs<TermuxProvisioning.Failed>(result)
        assertEquals(TermuxInstallStage.DOWNLOAD, failed.stage)
        assertTrue(failed.message.contains("offline"))
    }

    @Test
    fun `chmod failure is labelled permissions`() {
        val paths = tempPaths()
        val posix = object : RecordingPosix() {
            override fun chmodOwnerExecute(path: String) {
                throw SecurityException("no chmod")
            }
        }
        val archive = goodArchive(File(paths.downloadDir).also { it.mkdirs() })
        val entry = availableEntry(sha256 = TermuxBootstrapInstaller.sha256(archive), prefix = paths.prefix)
        val result = installer(paths, posix, entry, archive).provision()
        val failed = assertIs<TermuxProvisioning.Failed>(result)
        assertEquals(TermuxInstallStage.PERMISSIONS, failed.stage)
        assertFalse(File(paths.prefix).exists())
    }

    @Test
    fun `interrupted staging is never treated as a valid prefix`() {
        val paths = tempPaths()
        File(paths.stagingPrefix, "bin").mkdirs()
        File(paths.stagingPrefix, "bin/bash").writeText("partial")
        File(paths.stagingPrefix, "bin/bash").setExecutable(true)
        val posix = RecordingPosix()
        posix.executable += File(paths.stagingPrefix, "bin/bash").absolutePath
        val installer = TermuxBootstrapInstaller(
            paths = paths,
            supportedAbis = listOf("arm64-v8a"),
            prefixSupport = { TermuxPrefixSupport.Supported(it.prefix, official = false) },
            posix = posix,
            isExecutable = { file -> posix.canExecute(file) },
        )
        assertFalse(installer.isInstalled())
        assertFalse(File(paths.prefix, TermuxBootstrapArchive.INSTALL_MARKER).exists())
    }
}
