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
        addCompleteRootfs(files, layout.rootfs)
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
        addCompleteRootfs(files, layout.rootfs)
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
        addCompleteRootfs(files, layout.rootfs)
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
        addCompleteRootfs(files, layout.rootfs)
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
        addCompleteRootfs(files, layout.rootfs)
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
        addCompleteRootfs(files, layout.rootfs)
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
        // The tree is untouched by the marker going away: a complete tree without a marker is
        // "not verified yet", which is a different answer from "not there".
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
    fun `extraction unpacks into the installing tree and creates the link store inside it`() {
        val files = files()
        NativeRuntimeLayout.REQUIRED_LIBRARIES.forEach { name ->
            files.present += "${layout.nativeLibraryDir}/$name"
        }
        var extractedInto: String? = null
        val installer = installerWithArchive(files) { intoDir ->
            extractedInto = intoDir
            addCompleteRootfs(files, intoDir)
        }

        val result = installer.provision()

        assertIs<UbuntuInstallResult.Prepared>(result)
        assertEquals(layout.installing, extractedInto)
        assertTrue(files.directories.contains(layout.forInstalling().l2s))
        // Nothing is at the live rootfs path yet: the tree is still being built.
        assertFalse(installer.hasExtractedRootfs())
        assertFalse(installer.isInstalled())

        // Promotion rewrites the emulated links to the path the tree is about to have, so it is
        // valid where it ends up rather than where it was unpacked.
        assertTrue(installer.promote())
        assertTrue(installer.isConfiguredRootfs(layout.rootfs))
        assertTrue(files.directories.contains(layout.l2s))
        for (target in files.links.values) {
            assertTrue(target.startsWith("${layout.rootfs}/"), target)
        }
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
            addCompleteRootfs(files, intoDir)
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

    /**
     * The reported failure, at the state level.
     *
     * The logs showed `installMarker=true` while `<rootfs>/bin/bash`, `<rootfs>/usr` and
     * `<rootfs>/var` did not exist. A marker is a claim about the filesystem, never a substitute
     * for it, so it must not be able to make this true.
     */
    @Test
    fun `a stale install marker over an incomplete rootfs is not honoured`() {
        val files = files()
        files.present += layout.marker
        files.present += layout.verificationMarker
        files.present += layout.toolchainMarker
        val installer = UbuntuRootfsInstaller(
            layout = layout,
            supportedAbis = listOf("arm64-v8a"),
            files = files,
        )

        assertFalse(installer.isInstalled(), "a marker must never override the filesystem")
        assertFalse(installer.hasExtractedRootfs())

        // And the stale claim is cleared, so the next attempt cannot be misled by it either.
        assertTrue(installer.repairIncompleteInstallation())
        assertFalse(files.present.contains(layout.marker))
        assertFalse(files.present.contains(layout.verificationMarker))
        assertFalse(files.present.contains(layout.toolchainMarker))
    }

    /**
     * A total tree is reusable without a marker, and a marker is not enough without the tree.
     *
     * This is the other half of the rule: `NOT_INSTALLED` on disk with a complete, valid rootfs
     * must be resolved by looking at the filesystem, not by trusting either the marker or the
     * previous status.
     */
    @Test
    fun `a complete tree is reusable without a marker and a marker is useless without one`() {
        val files = files()
        addCompleteRootfs(files, layout.rootfs)
        NativeRuntimeLayout.REQUIRED_LIBRARIES.forEach { name ->
            files.present += "${layout.nativeLibraryDir}/$name"
        }
        val installer = UbuntuRootfsInstaller(
            layout = layout,
            supportedAbis = listOf("arm64-v8a"),
            files = files,
        )

        assertFalse(installer.isInstalled())
        assertTrue(installer.hasExtractedRootfs())
        assertTrue(installer.isConfiguredRootfs(layout.rootfs))
        // Reused, not re-downloaded: the filesystem decided, not a marker.
        assertIs<UbuntuInstallResult.AlreadyInstalled>(installer.provision())
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

    /** Adds everything isConfiguredRootfs asks for, so a fake tree means "installed". */
    private fun addCompleteRootfs(files: RecordingFiles, tree: String) {
        files.addTree(tree, UbuntuRootfsCatalog.REQUIRED_GUEST_FILES)
        files.addTree(tree, UbuntuRootfsCatalog.REQUIRED_RUNTIME_DIRECTORIES)
        emulatedHardLinks(files, tree)
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

        override fun rename(from: String, to: String): Boolean {
            fun move(map: MutableMap<String, String>) {
                for (key in map.keys.filter { it == from || it.startsWith("$from/") }.toList()) {
                    val value = map.remove(key)!!
                    map[to + key.removePrefix(from)] = value
                }
            }
            for (entry in present.filter { it == from || it.startsWith("$from/") }.toList()) {
                present.remove(entry)
                present += to + entry.removePrefix(from)
            }
            for (entry in directories.filter { it == from || it.startsWith("$from/") }.toList()) {
                directories.remove(entry)
                directories += to + entry.removePrefix(from)
            }
            move(links)
            move(written)
            directories += to
            return true
        }

        override fun rewriteSymlinkTargets(tree: String, fromPrefix: String, toPrefix: String): Int {
            var rewritten = 0
            for ((path, target) in links.toMap()) {
                if (target.startsWith(fromPrefix)) {
                    links[path] = toPrefix + target.removePrefix(fromPrefix)
                    rewritten++
                }
            }
            return rewritten
        }

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
