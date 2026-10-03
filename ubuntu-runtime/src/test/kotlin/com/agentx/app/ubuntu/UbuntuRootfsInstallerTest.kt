package com.agentx.app.ubuntu

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UbuntuRootfsInstallerTest {

    private val layout = NativeRuntimeLayout(
        nativeLibraryDir = "/data/app/com.agentx.app/lib/arm64",
        runtimeDir = "/data/user/0/com.agentx.app/files/developer-runtime",
    )

    private fun files(
        present: MutableSet<String> = mutableSetOf(),
        directories: MutableSet<String> = mutableSetOf(layout.rootfs, layout.runtimeDir),
    ): RecordingFiles = RecordingFiles(present, directories)

    @Test
    fun `a complete tree without a marker is extracted, not installed`() {
        val files = files()
        files.addTree(layout.rootfs, UbuntuRootfsCatalog.REQUIRED_GUEST_FILES)
        val installer = UbuntuRootfsInstaller(
            layout = layout,
            supportedAbis = listOf("arm64-v8a"),
            files = files,
        )
        assertFalse(installer.isInstalled())
        assertTrue(installer.hasExtractedRootfs())
    }

    @Test
    fun `repair keeps a complete unmarked rootfs`() {
        val files = files()
        files.addTree(layout.rootfs, UbuntuRootfsCatalog.REQUIRED_GUEST_FILES)
        val installer = UbuntuRootfsInstaller(
            layout = layout,
            supportedAbis = listOf("arm64-v8a"),
            files = files,
        )
        assertFalse(installer.repairIncompleteInstallation())
        assertTrue(installer.hasExtractedRootfs())
        assertTrue(files.present.any { it.startsWith(layout.rootfs) })
    }

    @Test
    fun `repair removes an unusable rootfs`() {
        val files = files(present = mutableSetOf("${layout.rootfs}/usr/bin/bash"))
        val installer = UbuntuRootfsInstaller(
            layout = layout,
            supportedAbis = listOf("arm64-v8a"),
            files = files,
        )
        assertTrue(installer.repairIncompleteInstallation())
        assertFalse(installer.hasExtractedRootfs())
        assertFalse(files.present.any { it.startsWith("${layout.rootfs}/") || it == layout.rootfs })
    }

    /**
     * The recovery path for a tree `dpkg` left half-modified.
     *
     * The decision cannot be re-derived: such a tree passes every file check this installer can
     * make from the host — measured against a tree whose `perl-base` unpack had just failed and
     * which still answered `install ok installed` for every package with an empty `dpkg --audit`.
     * So it is recorded, and nothing that described the tree as usable survives it.
     */
    @Test
    fun `discarding a rootfs removes the tree and every marker that described it`() {
        val files = files()
        files.addTree(layout.rootfs, UbuntuRootfsCatalog.REQUIRED_GUEST_FILES)
        files.present += layout.marker
        files.present += layout.verificationMarker
        files.present += layout.toolchainMarker
        val installer = UbuntuRootfsInstaller(
            layout = layout,
            supportedAbis = listOf("arm64-v8a"),
            files = files,
        )
        assertTrue(installer.isInstalled())

        installer.discardRootfs("apt-get install exited with 1: perl-base unpack failed")

        assertFalse(installer.hasExtractedRootfs())
        assertFalse(installer.isInstalled())
        assertFalse(files.present.contains(layout.marker))
        assertFalse(files.present.contains(layout.verificationMarker))
        assertFalse(files.present.contains(layout.toolchainMarker))
        // The refusal is recorded as well, so a tree that could not be removed immediately is
        // still refused on the next attempt rather than reused.
        assertTrue(files.present.contains(layout.recreateMarker))
    }

    /**
     * A tree marked for recreation is removed even though its files look complete, and the
     * refusal is cleared once it has been acted on.
     */
    @Test
    fun `repair removes a complete tree that was marked for recreation`() {
        val files = files()
        files.addTree(layout.rootfs, UbuntuRootfsCatalog.REQUIRED_GUEST_FILES)
        files.present += layout.recreateMarker
        val installer = UbuntuRootfsInstaller(
            layout = layout,
            supportedAbis = listOf("arm64-v8a"),
            files = files,
        )
        assertTrue(installer.hasExtractedRootfs())

        assertTrue(installer.repairIncompleteInstallation())

        assertFalse(installer.hasExtractedRootfs())
        assertFalse(files.present.contains(layout.recreateMarker))
    }

    @Test
    fun `missing PRoot is a runtime failure, not a download`() {
        var downloaded = false
        val installer = UbuntuRootfsInstaller(
            layout = layout,
            supportedAbis = listOf("arm64-v8a"),
            download = { _, target, _ ->
                downloaded = true
                target
            },
            files = files(),
        )
        val result = installer.provision()
        val failed = assertIs<UbuntuInstallResult.Failed>(result)
        assertEquals(UbuntuInstallStage.RUNTIME, failed.stage)
        assertTrue(failed.message.contains("missing from this APK"), failed.message)
        assertFalse(downloaded)
    }

    @Test
    fun `a complete extracted tree is already installed and is not re-downloaded`() {
        val files = files()
        files.addTree(layout.rootfs, UbuntuRootfsCatalog.REQUIRED_GUEST_FILES)
        NativeRuntimeLayout.REQUIRED_LIBRARIES.forEach { name ->
            files.present += "${layout.nativeLibraryDir}/$name"
        }
        var downloaded = false
        val installer = UbuntuRootfsInstaller(
            layout = layout,
            supportedAbis = listOf("arm64-v8a"),
            download = { _, target, _ ->
                downloaded = true
                target
            },
            files = files,
        )
        assertIs<UbuntuInstallResult.AlreadyInstalled>(installer.provision())
        assertFalse(downloaded)
        assertFalse(installer.isInstalled())
        assertTrue(installer.hasExtractedRootfs())
    }

    @Test
    fun `the install marker is written only on request after guest probes`() {
        val files = files()
        files.addTree(layout.rootfs, UbuntuRootfsCatalog.REQUIRED_GUEST_FILES)
        val installer = UbuntuRootfsInstaller(
            layout = layout,
            supportedAbis = listOf("arm64-v8a"),
            files = files,
        )
        assertFalse(installer.isInstalled())
        installer.writeInstallMarker()
        assertTrue(installer.isInstalled())
        installer.clearInstallMarker()
        assertFalse(installer.isInstalled())
        assertTrue(installer.hasExtractedRootfs())
    }

    @Test
    fun `the toolchain marker records what was verified`() {
        val files = files()
        val installer = UbuntuRootfsInstaller(
            layout = layout,
            supportedAbis = listOf("arm64-v8a"),
            files = files,
        )
        assertFalse(files.present.contains(layout.toolchainMarker))
        installer.writeToolchainMarker(listOf("git", "python3"))
        assertTrue(files.present.contains(layout.toolchainMarker))
        assertTrue(files.textAt(layout.toolchainMarker)!!.contains("verified=git"))
        installer.clearInstallMarkers()
        assertFalse(files.present.contains(layout.toolchainMarker))
    }

    /**
     * The tree is unpacked straight into the rootfs and the store is created inside it.
     *
     * This is the shape the whole fix rests on: `-l` records the store's absolute path in every
     * symlink it writes, so both the store's location and the fact that the tree is not moved
     * afterwards are load-bearing.
     */
    @Test
    fun `extraction unpacks into the rootfs and creates the link store inside it`() {
        val files = files()
        NativeRuntimeLayout.REQUIRED_LIBRARIES.forEach { name ->
            files.present += "${layout.nativeLibraryDir}/$name"
        }
        var extractedInto: String? = null
        val installer = installerWithArchive(files) { intoDir ->
            extractedInto = intoDir
            files.addTree(intoDir, UbuntuRootfsCatalog.REQUIRED_GUEST_FILES)
            emulatedHardLinks(files, intoDir)
        }

        val result = installer.provision()

        assertIs<UbuntuInstallResult.Installed>(result)
        assertEquals(layout.rootfs, extractedInto)
        assertTrue(files.directories.contains(layout.l2s))
        assertTrue(installer.hasExtractedRootfs())
    }

    /**
     * A store kept beside the rootfs is refused at extraction time.
     *
     * This is the regression guard for the reported failure: the emulated links still resolve for
     * the *host* (`resolvesToSameFile` is true either way), so the check that catches it is the
     * prefix of the target. Left uncaught it reached the user as
     * `dpkg: error setting ownership of '/usr/bin/perl5.38.2.dpkg-new': No such file or directory`.
     */
    @Test
    fun `a link store outside the rootfs fails validation instead of being installed`() {
        val files = files()
        NativeRuntimeLayout.REQUIRED_LIBRARIES.forEach { name ->
            files.present += "${layout.nativeLibraryDir}/$name"
        }
        val installer = installerWithArchive(files) { intoDir ->
            files.addTree(intoDir, UbuntuRootfsCatalog.REQUIRED_GUEST_FILES)
            // Exactly what the old layout produced: the target is a real host path, but not one
            // that lies under the guest root.
            val store = "${layout.runtimeDir}/l2s"
            files.links["$intoDir/usr/bin/perl"] = "$store/.l2s.perl0001"
            files.links["$intoDir/usr/bin/perl5.38.2"] = "$store/.l2s.perl0001"
            files.links["$intoDir/usr/bin/gunzip"] = "$store/.l2s.gunzip0001"
            files.links["$intoDir/usr/bin/uncompress"] = "$store/.l2s.gunzip0001"
        }

        val result = installer.provision()

        val failed = assertIs<UbuntuInstallResult.Failed>(result)
        assertEquals(UbuntuInstallStage.VALIDATION, failed.stage)
        assertTrue(failed.message.contains("outside the guest rootfs"), failed.message)
        // Nothing half-populated is left behind for a later attempt to reuse.
        assertFalse(installer.hasExtractedRootfs())
    }

    /** Builds an installer whose download and tar are faked, but whose digest check is real. */
    private fun installerWithArchive(
        files: RecordingFiles,
        populate: (String) -> Unit,
    ): UbuntuRootfsInstaller {
        val archive = File.createTempFile("agentx-rootfs", ".tar.gz").apply {
            writeText("not a real archive; the digest is what is under test")
            deleteOnExit()
        }
        val digest = UbuntuRootfsInstaller.sha256(archive)
        val catalogued = UbuntuRootfsCatalog.forAbi("arm64-v8a")!!
        val entry = catalogued.copy(sha256 = digest, archiveSizeBytes = archive.length())
        val tar = object : UbuntuTar {
            override fun extract(archivePath: String, intoDir: String) = populate(intoDir)
            override fun countEntries(archivePath: String): Int = 7
        }
        return UbuntuRootfsInstaller(
            layout = layout,
            supportedAbis = listOf("arm64-v8a"),
            download = { _, _, _ -> archive },
            resolveEntry = { entry },
            tar = tar,
            files = files,
            dnsServers = { listOf("8.8.8.8") },
        )
    }

    private fun emulatedHardLinks(files: RecordingFiles, intoDir: String) {
        for (hardLink in UbuntuRootfsCatalog.REQUIRED_HARD_LINKS) {
            val name = hardLink.file.substringAfterLast('/')
            files.links["$intoDir/${hardLink.file}"] = "$intoDir/.l2s/.l2s.${name}0001"
            files.links["$intoDir/${hardLink.link}"] = "$intoDir/.l2s/.l2s.${name}0001"
        }
    }

    private class RecordingFiles(
        val present: MutableSet<String>,
        val directories: MutableSet<String>,
    ) : UbuntuFiles {
        /** Symlink path -> target, for the paths the test wants to behave as links. */
        val links: MutableMap<String, String> = mutableMapOf()

        /** Written text, so a marker's contents can be asserted. */
        val written: MutableMap<String, String> = mutableMapOf()

        fun textAt(path: String): String? = written[path]

        override fun entryExists(path: String): Boolean =
            path in present || path in directories || path in links

        /**
         * True once the path is gone, including when it was never there — the same contract as
         * [AndroidUbuntuFiles.deleteRecursively], which returns true for an absent path. A strict
         * "did anything exist" result here would refuse to clear a rootfs the repair step has just
         * deleted, which is not what the platform does.
         */
        override fun deleteRecursively(path: String): Boolean {
            present.removeAll { it == path || it.startsWith("$path/") }
            directories.removeAll { it == path || it.startsWith("$path/") }
            links.keys.removeAll { it == path || it.startsWith("$path/") }
            written.keys.removeAll { it == path || it.startsWith("$path/") }
            return true
        }

        override fun mkdirs(path: String): Boolean {
            directories += path
            return true
        }

        override fun writeText(path: String, text: String) {
            present += path
            written[path] = text
        }

        override fun isDirectory(path: String): Boolean = path in directories

        override fun readLink(path: String): String? = links[path]

        // A real hard link in the fake: the two names always resolve to one file, which is what
        // the emulated pair does too.
        override fun resolvesToSameFile(first: String, second: String): Boolean =
            (links[first] ?: first) == (links[second] ?: second)

        fun addTree(root: String, relatives: List<String>) {
            directories += root
            for (relative in relatives) {
                present += "$root/$relative"
                var parent = "$root/$relative"
                while (true) {
                    val slash = parent.lastIndexOf('/')
                    if (slash <= 0) break
                    parent = parent.substring(0, slash)
                    directories += parent
                }
            }
        }
    }
}
