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
}

/** Archive handling. The Ubuntu tar preserves symlinks, hard links and modes; a hand-rolled
 *  reader in Kotlin cannot be trusted to, so extraction is delegated to the system tar. */
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
    private val tar: UbuntuTar = AndroidUbuntuTar,
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

    fun provision(onStatus: (RuntimeStatus) -> Unit = {}): UbuntuInstallResult {
        ensureRuntimeDirectories()

        if (isInstalled()) {
            onStatus(RuntimeStatus.Ready)
            return UbuntuInstallResult.AlreadyInstalled
        }

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
            onStatus(RuntimeStatus.Ready)
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

        val entries = tar.countEntries(archive.absolutePath)
        onStatus(RuntimeStatus(AgentxRuntimeState.EXTRACTING, progressPercent = 0, totalBytes = entries.toLong()))
        try {
            tar.extract(archive.absolutePath, layout.staging)
        } catch (error: Exception) {
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

    private fun isSymlink(file: File): Boolean = try {
        Os.lstat(file.absolutePath).st_mode and 0xF000 == 0xA000
    } catch (missing: Exception) {
        false
    }
}

/**
 * Extraction through the platform tar.
 *
 * `tar` is the only tool that preserves Ubuntu's hard links, symlinks and file modes reliably;
 * a Kotlin `ZipInputStream`-style reader cannot, which is why none is used for the rootfs.
 * toybox tar (the Android implementation) supports `-x -z -f -C` and drops ownership, which is
 * correct here because an app cannot chown to root and PRoot presents the guest as root anyway.
 */
object AndroidUbuntuTar : UbuntuTar {

    private const val TAR = "/system/bin/tar"

    override fun extract(archivePath: String, intoDir: String) {
        run(listOf(TAR, "-xzf", archivePath, "-C", intoDir))
    }

    override fun countEntries(archivePath: String): Int = try {
        val process = ProcessBuilder(TAR, "-tzf", archivePath)
            .redirectErrorStream(false)
            .start()
        val count = process.inputStream.bufferedReader().useLines { lines -> lines.count() }
        process.waitFor()
        count
    } catch (error: Exception) {
        0
    }

    private fun run(command: List<String>) {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        if (exit != 0) {
            throw IOException("${command.first()} exited $exit: ${output.take(500)}")
        }
    }
}
