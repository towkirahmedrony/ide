package com.agentx.app.ubuntu

import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Outcome of an install attempt. */
sealed interface UbuntuInstallResult {

    data object AlreadyInstalled : UbuntuInstallResult

    data class Installed(val archiveBytes: Long, val files: Int) : UbuntuInstallResult

    data class Unavailable(val abi: String, val reason: String) : UbuntuInstallResult

    data class Failed(val stage: UbuntuInstallStage, val message: String) : UbuntuInstallResult
}

/** Filesystem operations the installer needs, injectable so the rules are unit testable. */
interface UbuntuFiles {
    /**
     * Whether a path exists *without following the final symlink*.
     *
     * Required because an Ubuntu rootfs contains absolute and chained symlinks that only
     * resolve inside the guest; probing them with a host `exists()` would wrongly reject a
     * correct tree.
     */
    fun entryExists(path: String): Boolean
    fun deleteRecursively(path: String): Boolean
    fun mkdirs(path: String): Boolean
    fun rename(from: String, to: String): Boolean
    fun writeText(path: String, text: String)
    fun isDirectory(path: String): Boolean

    /**
     * Whether [first] and [second] name the same file once symlinks are followed.
     *
     * This is the hard-link relationship. It is true both for a real hard link (one inode, two
     * names) and for PRoot's emulation on Android (two symlinks onto one file in the
     * link-to-symlink store). It is deliberately *not* true for two independent copies, which is
     * what the validation has to catch. Returns false when either path is missing or dangling.
     */
    fun resolvesToSameFile(first: String, second: String): Boolean
}

/**
 * Archive handling.
 *
 * A Kotlin reader is not used: GNU/BSD `tar` is the only thing that reproduces Ubuntu Base's
 * symlinks, modes and hard links faithfully. On Android, however, `tar` cannot be allowed to
 * create hard links itself, so it is started *through* the runtime's PRoot — see
 * [ProotUbuntuTar].
 */
interface UbuntuTar {
    /** Extracts [archivePath] into [intoDir]. Throws on a non-zero exit or a spawn failure. */
    fun extract(archivePath: String, intoDir: String)
    /** Number of archive entries, for the progress line. Returns 0 when it cannot be counted. */
    fun countEntries(archivePath: String): Int
}

/**
 * Downloads, verifies, extracts and validates the Ubuntu ARM64 rootfs.
 *
 * The order is fixed and there is no way to skip a step:
 * download → SHA-256 → extract → validate required files → configure → activate → marker.
 * A checksum mismatch deletes the archive and aborts; the marker is only written after the
 * extracted tree has been proven to contain a shell, `apt` and `dpkg`.
 */
class UbuntuRootfsInstaller(
    private val layout: NativeRuntimeLayout,
    private val supportedAbis: List<String>,
    private val download: (String, File, (Long, Long) -> Unit) -> File =
        UbuntuRootfsInstaller::defaultDownload,
    private val resolveEntry: (List<String>) -> UbuntuRootfsCatalog.Entry? = UbuntuRootfsCatalog::forAbis,
    private val tar: UbuntuTar = ProotUbuntuTar(layout),
    private val files: UbuntuFiles = AndroidUbuntuFiles,
    private val dnsServers: () -> List<String> = { emptyList() },
) {

    fun isInstalled(): Boolean {
        if (!files.entryExists(layout.marker)) return false
        val rootfsOk = files.isDirectory(layout.rootfs)
        if (!rootfsOk) return false
        return UbuntuRootfsCatalog.REQUIRED_GUEST_FILES.all { relative ->
            files.entryExists("${layout.rootfs}/$relative")
        }
    }

    fun ensureRuntimeDirectories() {
        for (directory in layout.requiredDirectories) {
            files.mkdirs(directory)
        }
    }

    /**
     * Removes whatever an interrupted or failed install left behind so a retry starts from
     * verified bytes.
     *
     * A `rootfs` without the install marker is a partial tree that must never be treated as an
     * installation; `staging` is scratch. The downloaded archive is deliberately **kept**:
     * [fetch] re-checks it against the pinned SHA-256 and reuses it when it still matches, so a
     * retry after a transient extraction failure costs no download. The link-to-symlink store is
     * never cleared here — an installed rootfs refers into it.
     */
    fun repairIncompleteInstallation(): Boolean {
        var removed = false
        if (files.entryExists(layout.staging)) {
            removed = files.deleteRecursively(layout.staging) || removed
        }
        if (!isInstalled() && files.entryExists(layout.rootfs)) {
            val cleared = files.deleteRecursively(layout.rootfs)
            removed = cleared || removed
            if (cleared) {
                // Both markers describe a rootfs that no longer exists, so a retry must run the
                // guest probes and the apt toolchain step again rather than trust them.
                files.deleteRecursively(layout.verificationMarker)
                files.deleteRecursively(layout.toolchainMarker)
            }
        }
        return removed
    }

    fun provision(onStatus: (RuntimeStatus) -> Unit = {}): UbuntuInstallResult {
        ensureRuntimeDirectories()

        // No terminal `Ready` is published here: READY belongs to the runtime, and it is only
        // reached after the installed tree has been run through PRoot. See
        // `LocalUbuntuRuntime.verifyRootfs`.
        if (isInstalled()) return UbuntuInstallResult.AlreadyInstalled

        // A rootfs or staging tree from a previous attempt is not evidence of anything.
        repairIncompleteInstallation()

        val entry = resolveEntry(supportedAbis)
            ?: return fail(
                UbuntuInstallResult.Unavailable(
                    abi = supportedAbis.joinToString(),
                    reason = "No Ubuntu rootfs is catalogued for ${supportedAbis.joinToString()}.",
                ),
                UbuntuInstallStage.DOWNLOAD,
                onStatus,
            )

        if (!entry.available) {
            return fail(
                UbuntuInstallResult.Unavailable(entry.androidAbi, entry.unavailableReason()),
                UbuntuInstallStage.DOWNLOAD,
                onStatus,
            )
        }

        return try {
            val archive = fetch(entry, onStatus)
            val files = extract(entry, archive, onStatus)
            UbuntuInstallResult.Installed(archiveBytes = archive.length(), files = files)
        } catch (io: IOException) {
            fail(UbuntuInstallResult.Failed(UbuntuInstallStage.DOWNLOAD, io.message ?: "download failed"), UbuntuInstallStage.DOWNLOAD, onStatus)
        } catch (failure: UbuntuRootfsException) {
            fail(UbuntuInstallResult.Failed(failure.stage, failure.message ?: "rootfs install failed"), failure.stage, onStatus)
        }
    }

    private fun fetch(
        entry: UbuntuRootfsCatalog.Entry,
        onStatus: (RuntimeStatus) -> Unit,
    ): File {
        val sha256 = entry.sha256 ?: throw UbuntuRootfsException(
            UbuntuInstallStage.DOWNLOAD,
            entry.unavailableReason(),
        )
        val url = entry.url ?: throw UbuntuRootfsException(
            UbuntuInstallStage.DOWNLOAD,
            entry.unavailableReason(),
        )

        val target = File(layout.downloads, entry.assetName)
        if (target.isFile) {
            onStatus(RuntimeStatus(AgentxRuntimeState.VERIFYING, progressPercent = 100))
            if (sha256(target) == sha256) return target
            // A cached archive that no longer matches the pinned digest is not trusted.
            target.delete()
        }

        target.parentFile?.mkdirs()
        onStatus(RuntimeStatus(AgentxRuntimeState.DOWNLOADING, progressPercent = 0))
        val downloaded = try {
            download(url, target) { received, total ->
                val percent = if (total > 0) ((received * 100) / total).toInt().coerceIn(0, 99) else 0
                onStatus(
                    RuntimeStatus(
                        AgentxRuntimeState.DOWNLOADING,
                        progressPercent = percent,
                        installedBytes = received,
                        totalBytes = total,
                    ),
                )
            }
        } catch (io: IOException) {
            throw IOException("Could not download $url: ${io.message}", io)
        }

        onStatus(RuntimeStatus(AgentxRuntimeState.VERIFYING, progressPercent = 100))
        val digest = sha256(downloaded)
        if (digest != sha256) {
            downloaded.delete()
            throw UbuntuRootfsException(
                UbuntuInstallStage.CHECKSUM,
                "Rootfs ${entry.assetName} failed SHA-256 verification " +
                    "(expected $sha256, got $digest). Nothing was installed.",
            )
        }
        return downloaded
    }

    private fun extract(
        entry: UbuntuRootfsCatalog.Entry,
        archive: File,
        onStatus: (RuntimeStatus) -> Unit,
    ): Int {
        // Re-verify immediately before extraction: the archive on disk is the one being unpacked.
        val expected = entry.sha256 ?: throw UbuntuRootfsException(
            UbuntuInstallStage.CHECKSUM,
            "Refusing to extract ${entry.assetName}: no SHA-256 is recorded.",
        )
        val digest = sha256(archive)
        if (digest != expected) {
            archive.delete()
            throw UbuntuRootfsException(
                UbuntuInstallStage.CHECKSUM,
                "Rootfs ${entry.assetName} failed SHA-256 verification at extraction " +
                    "(expected $expected, got $digest). Nothing was installed.",
            )
        }

        val staging = File(layout.staging)
        if (!files.deleteRecursively(layout.staging)) {
            throw UbuntuRootfsException(UbuntuInstallStage.EXTRACTION, "Could not clear ${layout.staging}")
        }
        if (!files.mkdirs(layout.staging)) {
            throw UbuntuRootfsException(UbuntuInstallStage.EXTRACTION, "Could not create ${layout.staging}")
        }

        // The extraction is the one step that has to run *through* the native PRoot.
        requireProotTooling()

        val entries = tar.countEntries(archive.absolutePath)
        onStatus(RuntimeStatus(AgentxRuntimeState.EXTRACTING, progressPercent = 0, totalBytes = entries.toLong()))
        try {
            tar.extract(archive.absolutePath, layout.staging)
        } catch (error: Exception) {
            // Leave nothing half-unpacked behind: the next attempt starts from the archive.
            files.deleteRecursively(layout.staging)
            throw UbuntuRootfsException(
                UbuntuInstallStage.EXTRACTION,
                "Could not extract ${entry.assetName}: ${error.message}",
            )
        }

        onStatus(RuntimeStatus(AgentxRuntimeState.INSTALLING))
        validate(layout.staging)

        configure(layout.staging)

        // Activation is last: an interrupted install never leaves a half-populated tree that
        // later code would mistake for a working rootfs.
        if (files.entryExists(layout.rootfs) && !files.deleteRecursively(layout.rootfs)) {
            throw UbuntuRootfsException(UbuntuInstallStage.ACTIVATION, "Could not clear ${layout.rootfs}")
        }
        if (!files.rename(layout.staging, layout.rootfs)) {
            throw UbuntuRootfsException(
                UbuntuInstallStage.ACTIVATION,
                "Could not move ${layout.staging} to ${layout.rootfs}",
            )
        }

        writeMarker()

        if (!files.entryExists(layout.marker) || !isInstalled()) {
            throw UbuntuRootfsException(
                UbuntuInstallStage.RUNTIME,
                "Rootfs was installed but ${layout.marker} or the required guest files are missing.",
            )
        }
        return entries
    }

    private fun validate(tree: String) {
        val missing = UbuntuRootfsCatalog.REQUIRED_GUEST_FILES.filter { relative ->
            !files.entryExists("$tree/$relative")
        }
        if (missing.isNotEmpty()) {
            throw UbuntuRootfsException(
                UbuntuInstallStage.VALIDATION,
                "The extracted rootfs is not a usable Ubuntu userland: missing ${missing.joinToString()}. " +
                    "Symlinks or permissions did not survive extraction.",
            )
        }
        validateHardLinks(tree)
    }

    /**
     * Proves the archive's hard links survived *as links*.
     *
     * Checking that both paths exist is not enough: a naive extractor that writes each entry as
     * its own file would satisfy that while silently doubling a binary and breaking dpkg. The
     * test is therefore that the two names resolve to the same file — a real hard link, or, on
     * Android where the kernel forbids them, PRoot's link-to-symlink emulation, which is still
     * one file under two names.
     */
    private fun validateHardLinks(tree: String) {
        for (hardLink in UbuntuRootfsCatalog.REQUIRED_HARD_LINKS) {
            val file = "$tree/${hardLink.file}"
            val link = "$tree/${hardLink.link}"
            if (!files.entryExists(file) || !files.entryExists(link)) {
                throw UbuntuRootfsException(
                    UbuntuInstallStage.VALIDATION,
                    "The extracted rootfs is missing ${hardLink.file} or ${hardLink.link}; the " +
                        "archive stores them as a hard-link pair and both must survive extraction.",
                )
            }
            if (files.resolvesToSameFile(file, link)) continue
            throw UbuntuRootfsException(
                UbuntuInstallStage.VALIDATION,
                "${hardLink.link} and ${hardLink.file} no longer name the same file after " +
                    "extraction. Android forbids a hard link, so PRoot's link-to-symlink " +
                    "emulation must have been used; a plain extraction or an independent copy " +
                    "cannot be accepted.",
            )
        }
    }

    /**
     * Fails with the real reason when the native PRoot/loader pair is not in `nativeLibraryDir`.
     *
     * Without it the archive cannot be unpacked correctly at all, and a guest could not be run
     * afterwards either, so the honest outcome is a runtime-stage error rather than a rootfs
     * extracted in a way that cannot work.
     */
    private fun requireProotTooling() {
        val missing = listOf(NativeRuntimeLayout.PROOT_LIBRARY, NativeRuntimeLayout.LOADER_LIBRARY)
            .filter { name -> !files.entryExists("${layout.nativeLibraryDir}/$name") }
        if (missing.isEmpty()) return
        throw UbuntuRootfsException(
            UbuntuInstallStage.RUNTIME,
            "PRoot is not installed in ${layout.nativeLibraryDir} (missing ${missing.joinToString()}). " +
                "The Ubuntu rootfs is unpacked through PRoot so its hard links are preserved; " +
                "reinstall the APK with its native libraries and retry.",
        )
    }

    private fun configure(tree: String) {
        // apt under PRoot: the sandbox drops privileges with unavailable helpers, and a phone
        // should not pull recommended packages. `noble` on arm64 is served from ubuntu-ports.
        files.writeText(
            "$tree/etc/apt/apt.conf.d/99agentx.conf",
            buildString {
                append("APT::Sandbox::User \"root\";\n")
                append("APT::Install-Recommends \"false\";\n")
                append("APT::Install-Suggests \"false\";\n")
                append("Acquire::Retries \"5\";\n")
                append("Acquire::http::No-Cache \"true\";\n")
            },
        )
        files.writeText(
            "$tree/etc/apt/sources.list",
            buildString {
                append("# AgentX developer runtime: Ubuntu ARM64 (ports) repositories.\n")
                for (component in listOf("main", "restricted", "universe", "multiverse")) {
                    append("deb http://ports.ubuntu.com/ubuntu-ports ${UbuntuRootfsCatalog.UBUNTU_CODENAME} $component\n")
                }
                append("deb http://ports.ubuntu.com/ubuntu-ports ${UbuntuRootfsCatalog.UBUNTU_CODENAME}-updates main restricted universe multiverse\n")
                append("deb http://ports.ubuntu.com/ubuntu-ports ${UbuntuRootfsCatalog.UBUNTU_CODENAME}-security main restricted universe multiverse\n")
            },
        )

        // DNS: Android has no /etc/resolv.conf, so one is generated and bound into the guest.
        val servers = dnsServers().filter { it.isNotBlank() }.distinct()
        val effective = servers.ifEmpty { listOf("8.8.8.8", "1.1.1.1") }
        files.writeText(
            layout.resolvConf,
            buildString {
                if (servers.isEmpty()) {
                    append("# No Android DNS servers were discovered; using public resolvers.\n")
                }
                for (server in effective) append("nameserver $server\n")
            },
        )
    }

    private fun writeMarker() {
        files.writeText(
            layout.marker,
            buildString {
                append("ok\n")
                append("abi=arm64-v8a\n")
                append("ubuntu=${UbuntuRootfsCatalog.UBUNTU_RELEASE}\n")
                append("runtime=${UbuntuEnvironment.RUNTIME_MARKER}\n")
            },
        )
    }

    private fun fail(
        result: UbuntuInstallResult,
        stage: UbuntuInstallStage,
        onStatus: (RuntimeStatus) -> Unit,
    ): UbuntuInstallResult {
        val message = when (result) {
            is UbuntuInstallResult.Unavailable -> result.reason
            is UbuntuInstallResult.Failed -> result.message
            else -> "Provisioning did not complete."
        }
        onStatus(RuntimeStatus(AgentxRuntimeState.ERROR, stage = stage, message = message))
        return result
    }

    class UbuntuRootfsException(val stage: UbuntuInstallStage, message: String) : Exception(message)

    companion object {
        private const val BUFFER_SIZE = 64 * 1024

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }

        fun defaultDownload(url: String, target: File, onProgress: (Long, Long) -> Unit): File {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 30_000
                    readTimeout = 60_000
                    requestMethod = "GET"
                }
                val status = connection.responseCode
                if (status !in 200..299) throw IOException("HTTP $status from $url")
                val total = connection.contentLengthLong.takeIf { it > 0 } ?: 0L
                target.parentFile?.mkdirs()
                var received = 0L
                connection.inputStream.use { input ->
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            received += read
                            onProgress(received, total)
                        }
                    }
                }
                return target
            } finally {
                connection?.disconnect()
            }
        }
    }
}

/** Android implementation of [UbuntuFiles]. */
object AndroidUbuntuFiles : UbuntuFiles {

    override fun entryExists(path: String): Boolean = try {
        Os.lstat(path)
        true
    } catch (missing: Exception) {
        false
    }

    override fun deleteRecursively(path: String): Boolean {
        val file = File(path)
        if (!file.exists() && !entryExists(path)) return true
        if (file.isDirectory && !isSymlink(file)) {
            file.listFiles()?.forEach { deleteRecursively(it.absolutePath) }
        }
        return file.delete() || (!file.exists() && !entryExists(path))
    }

    override fun mkdirs(path: String): Boolean {
        val file = File(path)
        return file.isDirectory || file.mkdirs()
    }

    override fun rename(from: String, to: String): Boolean = File(from).renameTo(File(to))

    override fun writeText(path: String, text: String) {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    override fun isDirectory(path: String): Boolean = File(path).isDirectory

    /**
     * Compares the *resolved* files. `Os.stat` follows symlinks, so this is true for a real hard
     * link and for PRoot's link-to-symlink emulation (both names lead to one file) and false for
     * two independent copies. A dangling symlink or a missing path throws and reports false.
     */
    override fun resolvesToSameFile(first: String, second: String): Boolean = try {
        val left = Os.stat(first)
        val right = Os.stat(second)
        left.st_dev == right.st_dev && left.st_ino == right.st_ino
    } catch (missing: Exception) {
        false
    }

    private fun isSymlink(file: File): Boolean = try {
        Os.lstat(file.absolutePath).st_mode and 0xF000 == 0xA000
    } catch (missing: Exception) {
        false
    }
}

/**
 * Extraction through the platform `tar`, started by the runtime's own PRoot.
 *
 * Why PRoot is in the middle: Ubuntu Base stores two entries as hard links
 * ([UbuntuRootfsCatalog.REQUIRED_HARD_LINKS]). Android's SELinux policy forbids `untrusted_app`
 * from creating a hard link at all — the platform's `neverallow` rule — so running
 * `/system/bin/tar -xzf` directly stops at
 *
 * ```text
 * tar: can't link 'usr/bin/perl5.38.2' -> 'usr/bin/perl': Permission denied
 * tar: had errors
 * ```
 *
 * and leaves an unusable tree. PRoot's `-l` extension performs that `link()`/`linkat()` as a
 * symlink to the same file (`PROOT_L2S_DIR` is where it keeps the contents), so the archive
 * extracts, the two names still lead to one file, and nothing is copied or dropped. The
 * archive's SHA-256 is verified before this runs and is not weakened by it.
 *
 * `countEntries` is a read-only `-t` listing and is safe to run directly: it creates nothing.
 */
class ProotUbuntuTar(
    private val layout: NativeRuntimeLayout,
    private val hostTar: String = UbuntuRootfsCatalog.HOST_TAR,
) : UbuntuTar {

    override fun extract(archivePath: String, intoDir: String) {
        val invocation = ProotCommand.extraction(
            layout = layout,
            hostTar = hostTar,
            archivePath = archivePath,
            intoDir = intoDir,
        )
        val builder = ProcessBuilder(invocation.processCommand).redirectErrorStream(true)
        for ((name, value) in invocation.environment) {
            builder.environment()[name] = value
        }
        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        if (exit != 0) {
            throw IOException(
                "tar under PRoot exited $exit: ${output.take(500)}",
            )
        }
        // toybox tar reports a link it could not create on stderr and still exits non-zero; a
        // zero exit with 'can't link' in the output would mean the emulation did not run.
        if (output.contains("can't link") || output.contains("Cannot hard link")) {
            throw IOException("tar could not preserve the archive's hard links: ${output.take(500)}")
        }
    }

    override fun countEntries(archivePath: String): Int = try {
        val process = ProcessBuilder(hostTar, "-tzf", archivePath)
            .redirectErrorStream(false)
            .start()
        val count = process.inputStream.bufferedReader().useLines { lines -> lines.count() }
        process.waitFor()
        count
    } catch (error: Exception) {
        0
    }
}
