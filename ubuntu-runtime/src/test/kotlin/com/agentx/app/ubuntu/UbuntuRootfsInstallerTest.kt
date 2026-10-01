package com.agentx.app.ubuntu

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

    private class RecordingFiles(
        val present: MutableSet<String>,
        val directories: MutableSet<String>,
    ) : UbuntuFiles {
        override fun entryExists(path: String): Boolean = path in present || path in directories
        override fun deleteRecursively(path: String): Boolean {
            val removedEntries = present.removeAll { it == path || it.startsWith("$path/") }
            val removedDirs = directories.removeAll { it == path || it.startsWith("$path/") }
            return removedEntries || removedDirs || true
        }
        override fun mkdirs(path: String): Boolean {
            directories += path
            return true
        }
        override fun rename(from: String, to: String): Boolean = false
        override fun writeText(path: String, text: String) {
            present += path
        }
        override fun isDirectory(path: String): Boolean = path in directories
        override fun resolvesToSameFile(first: String, second: String): Boolean = false
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
