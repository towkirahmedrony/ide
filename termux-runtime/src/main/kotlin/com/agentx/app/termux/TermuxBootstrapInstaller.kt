package com.agentx.app.termux

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

enum class TermuxInstallStage(val wireName: String) {
    DOWNLOAD("download"),
    CHECKSUM("checksum"),
    EXTRACTION("extraction"),
    SYMLINK("symlink"),
    PERMISSIONS("permissions"),
    PREFIX("prefix"),
    RUNTIME("runtime"),
}

sealed interface TermuxProvisioning {

    data object AlreadyInstalled : TermuxProvisioning

    data class Installed(val bytes: Long, val files: Int, val symlinks: Int) : TermuxProvisioning

    data class Unsupported(val reason: String, val remedy: String) : TermuxProvisioning

    data class NoArchive(val supportedAbis: List<String>) : TermuxProvisioning

    data class ArtifactUnavailable(val abi: String, val reason: String) : TermuxProvisioning

    data class Failed(val stage: TermuxInstallStage, val message: String) : TermuxProvisioning {
        val failed: String get() = stage.wireName
    }
}

sealed interface TermuxProvisioningState {
    data object Idle : TermuxProvisioningState
    data class Downloading(val percent: Int, val receivedBytes: Long, val totalBytes: Long) : TermuxProvisioningState
    data class Verifying(val percent: Int) : TermuxProvisioningState
    data class Extracting(val files: Int) : TermuxProvisioningState
    data class Ready(val installed: TermuxProvisioning) : TermuxProvisioningState
    /**
     * [stage] is the step that actually failed, when it is known, so the UI can say which of
     * download / checksum / extraction / symlink / permissions / prefix / runtime broke instead
     * of showing one undifferentiated "install failed".
     */
    data class Failed(
        val message: String,
        val stage: TermuxInstallStage? = null,
    ) : TermuxProvisioningState
}

class TermuxBootstrapInstaller(
    private val paths: TermuxPaths,
    private val supportedAbis: List<String>,
    private val download: (String, File, (Long, Long) -> Unit) -> File = TermuxBootstrapInstaller::defaultDownload,
    private val prefixSupport: (TermuxPaths) -> TermuxPrefixSupport = TermuxPrefixPolicy::evaluate,
    private val matchPrefixes: (String, String) -> TermuxPrefixSupport = TermuxPrefixPolicy::requireMatchingPrefix,
    private val resolveEntry: (List<String>) -> TermuxBootstrapCatalog.Entry? = TermuxBootstrapCatalog::forAbis,
    private val posix: TermuxPosix = AndroidTermuxPosix,
    private val isExecutable: (File) -> Boolean = { file -> posix.canExecute(file) },
) {

    fun isInstalled(): Boolean {
        if (prefixSupport(paths) !is TermuxPrefixSupport.Supported) return false
        if (TermuxPrefixPolicy.isOfficialPath(paths.prefix)) return false
        val marker = File(paths.prefix, TermuxBootstrapArchive.INSTALL_MARKER)
        if (!marker.isFile) return false
        val hasShell = TermuxShellResolver.LOGIN_SHELL_BINARIES.any { name ->
            isExecutable(File("${paths.bin}/$name"))
        }
        if (!hasShell) return false
        return File(paths.lib).isDirectory
    }

    fun provision(onState: (TermuxProvisioningState) -> Unit = {}): TermuxProvisioning {
        if (isInstalled()) {
            onState(TermuxProvisioningState.Ready(TermuxProvisioning.AlreadyInstalled))
            return TermuxProvisioning.AlreadyInstalled
        }

        when (val support = prefixSupport(paths)) {
            is TermuxPrefixSupport.Unsupported ->
                return fail(TermuxProvisioning.Unsupported(support.reason, support.remedy), onState)
            is TermuxPrefixSupport.Supported -> Unit
        }

        val entry = resolveEntry(supportedAbis)
            ?: return fail(TermuxProvisioning.NoArchive(supportedAbis), onState)

        when (val match = matchPrefixes(paths.prefix, entry.prefix)) {
            is TermuxPrefixSupport.Unsupported ->
                return fail(TermuxProvisioning.Unsupported(match.reason, match.remedy), onState)
            is TermuxPrefixSupport.Supported -> Unit
        }

        if (!entry.available) {
            return fail(TermuxProvisioning.ArtifactUnavailable(entry.androidAbi, entry.unavailableReason()), onState)
        }

        return try {
            val archive = fetch(entry, onState)
            val result = extract(entry, archive, onState)
            onState(TermuxProvisioningState.Ready(result))
            result
        } catch (io: IOException) {
            fail(TermuxProvisioning.Failed(TermuxInstallStage.DOWNLOAD, io.message ?: "download failed"), onState)
        } catch (corrupt: TermuxBootstrapException) {
            fail(TermuxProvisioning.Failed(corrupt.stage, corrupt.message ?: "bootstrap archive invalid"), onState)
        }
    }

    private fun fetch(
        entry: TermuxBootstrapCatalog.Entry,
        onState: (TermuxProvisioningState) -> Unit,
    ): File {
        val sha256 = entry.sha256 ?: throw TermuxBootstrapException(
            TermuxInstallStage.DOWNLOAD,
            entry.unavailableReason(),
        )
        val url = entry.url ?: throw TermuxBootstrapException(
            TermuxInstallStage.DOWNLOAD,
            entry.unavailableReason(),
        )
        val target = File(paths.downloadDir, entry.assetName)
        if (target.isFile) {
            onState(TermuxProvisioningState.Verifying(percent = 100))
            val existing = sha256(target)
            if (existing == sha256) return target
            target.delete()
        }

        target.parentFile?.mkdirs()
        onState(TermuxProvisioningState.Downloading(percent = 0, receivedBytes = 0, totalBytes = 0))
        val downloaded = try {
            download(url, target) { received, total ->
                val percent = if (total > 0) ((received * 100) / total).toInt().coerceIn(0, 99) else 0
                onState(TermuxProvisioningState.Downloading(percent, received, total))
            }
        } catch (io: IOException) {
            throw IOException("Could not download $url: ${io.message}", io)
        }

        onState(TermuxProvisioningState.Verifying(percent = 100))
        val digest = sha256(downloaded)
        if (digest != sha256) {
            downloaded.delete()
            throw TermuxBootstrapException(
                TermuxInstallStage.CHECKSUM,
                "Bootstrap ${entry.assetName} failed SHA-256 verification " +
                    "(expected $sha256, got $digest). Nothing was installed.",
            )
        }
        return downloaded
    }

    private fun extract(
        entry: TermuxBootstrapCatalog.Entry,
        archive: File,
        onState: (TermuxProvisioningState) -> Unit,
    ): TermuxProvisioning {
        val expected = entry.sha256 ?: throw TermuxBootstrapException(
            TermuxInstallStage.CHECKSUM,
            "Refusing to extract ${entry.assetName}: no SHA-256 is recorded.",
        )
        val digest = sha256(archive)
        if (digest != expected) {
            archive.delete()
            throw TermuxBootstrapException(
                TermuxInstallStage.CHECKSUM,
                "Bootstrap ${entry.assetName} failed SHA-256 verification " +
                    "(expected $expected, got $digest). Nothing was installed.",
            )
        }

        val staging = File(paths.stagingPrefix)
        deleteRecursively(staging)
        if (!staging.mkdirs() && !staging.isDirectory) {
            throw TermuxBootstrapException(TermuxInstallStage.EXTRACTION, "Could not create ${staging.absolutePath}")
        }

        var files = 0
        val symlinks = ArrayList<TermuxBootstrapArchive.Symlink>()
        val buffer = ByteArray(BUFFER_SIZE)

        archive.inputStream().buffered(BUFFER_SIZE).use { raw ->
            ZipInputStream(raw).use { zip ->
                while (true) {
                    val zipEntry = zip.nextEntry ?: break
                    val name = zipEntry.name
                    if (!TermuxBootstrapArchive.isSafeEntry(name)) {
                        throw TermuxBootstrapException(TermuxInstallStage.EXTRACTION, "Unsafe archive entry: $name")
                    }
                    if (TermuxBootstrapArchive.isManifestEntry(name)) {
                        val manifest = zip.readBytes().toString(Charsets.UTF_8)
                        val parsed = TermuxBootstrapArchive.parseSymlinks(
                            content = manifest,
                            stagingPrefix = paths.stagingPrefix,
                            finalPrefix = paths.prefix,
                        )
                        if (parsed.invalid.isNotEmpty()) {
                            throw TermuxBootstrapException(
                                TermuxInstallStage.SYMLINK,
                                "Malformed ${TermuxBootstrapArchive.SYMLINK_MANIFEST}: ${parsed.invalid.first()}",
                            )
                        }
                        symlinks += parsed.links
                        zip.closeEntry()
                        continue
                    }

                    val relative = name.removePrefix("./")
                    val resolved = TermuxBootstrapArchive.resolvedInside(staging.absolutePath, relative)
                        ?: throw TermuxBootstrapException(TermuxInstallStage.EXTRACTION, "Unsafe archive entry: $name")
                    val target = File(resolved)
                    if (zipEntry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { out ->
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                out.write(buffer, 0, read)
                            }
                        }
                        if (TermuxBootstrapArchive.isExecutableEntry(name)) {
                            try {
                                posix.chmodOwnerExecute(target.absolutePath)
                            } catch (error: Exception) {
                                throw TermuxBootstrapException(
                                    TermuxInstallStage.PERMISSIONS,
                                    "Could not mark ${target.absolutePath} executable: ${error.message}",
                                )
                            }
                        }
                        files += 1
                        if (files % PROGRESS_EVERY == 0) onState(TermuxProvisioningState.Extracting(files))
                    }
                    zip.closeEntry()
                }
            }
        }

        if (symlinks.isEmpty()) {
            throw TermuxBootstrapException(
                TermuxInstallStage.SYMLINK,
                "No ${TermuxBootstrapArchive.SYMLINK_MANIFEST} in ${entry.assetName}; refusing to install a prefix " +
                    "where the shell and package manager would not resolve.",
            )
        }
        for (link in symlinks) {
            val linkPath = TermuxBootstrapArchive.resolveSymlink(paths.stagingPrefix, link.linkPath)
                ?: throw TermuxBootstrapException(
                    TermuxInstallStage.SYMLINK,
                    "Symlink path escapes staging: ${link.linkPath}",
                )
            // Absolute targets that point into the final prefix are expected here: they are
            // dangling while the tree sits in usr-staging and resolve once it is renamed.
            if (!TermuxBootstrapArchive.isSafeSymlinkTarget(
                    stagingPrefix = paths.stagingPrefix,
                    finalPrefix = paths.prefix,
                    linkPath = link.linkPath,
                    target = link.target,
                )
            ) {
                throw TermuxBootstrapException(
                    TermuxInstallStage.SYMLINK,
                    "Symlink target ${link.target} of ${link.linkPath} escapes both " +
                        "${paths.stagingPrefix} and ${paths.prefix}",
                )
            }
            File(linkPath).parentFile?.mkdirs()
            try {
                posix.symlink(link.target, linkPath)
            } catch (exists: Exception) {
                if (!File(linkPath).exists()) {
                    throw TermuxBootstrapException(
                        TermuxInstallStage.SYMLINK,
                        "Could not create $linkPath -> ${link.target}: ${exists.message}",
                    )
                }
            }
        }

        val prefix = File(paths.prefix)
        if (prefix.exists() && !deleteRecursively(prefix)) {
            throw TermuxBootstrapException(TermuxInstallStage.PREFIX, "Could not clear ${prefix.absolutePath}")
        }
        if (!staging.renameTo(prefix)) {
            throw TermuxBootstrapException(
                TermuxInstallStage.PREFIX,
                "Could not move ${staging.absolutePath} to ${prefix.absolutePath}",
            )
        }

        createRuntimeDirectories()
        if (!File(paths.tmp).isDirectory || !File(paths.home).isDirectory || !File(paths.homeStorage).isDirectory) {
            throw TermuxBootstrapException(
                TermuxInstallStage.RUNTIME,
                "Could not create ${paths.tmp}, ${paths.home} or ${paths.homeStorage}",
            )
        }

        writeInstallMarker()
        return TermuxProvisioning.Installed(bytes = archive.length(), files = files, symlinks = symlinks.size)
    }

    fun createRuntimeDirectories() {
        for (directory in listOf(paths.tmp, paths.home, paths.workspaces, paths.homeStorage)) {
            File(directory).mkdirs()
        }
    }

    fun writeEnvironmentFile(environment: Array<String>) {
        val file = File(paths.envFile)
        file.parentFile?.mkdirs()
        val body = TermuxEnvironment.toEnvFile(environment)
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(body)
        if (!temporary.renameTo(file)) {
            file.writeText(body)
            temporary.delete()
        }
    }

    private fun writeInstallMarker() {
        val marker = File(paths.prefix, TermuxBootstrapArchive.INSTALL_MARKER)
        marker.parentFile?.mkdirs()
        marker.writeText("ok\n")
    }

    private fun fail(
        result: TermuxProvisioning,
        onState: (TermuxProvisioningState) -> Unit,
    ): TermuxProvisioning {
        val message = when (result) {
            is TermuxProvisioning.Unsupported -> result.reason
            is TermuxProvisioning.NoArchive ->
                "No AgentX bootstrap is catalogued for ${result.supportedAbis.joinToString()}."
            is TermuxProvisioning.ArtifactUnavailable -> result.reason
            is TermuxProvisioning.Failed -> "[${result.stage.wireName}] ${result.message}"
            else -> "Provisioning did not complete."
        }
        onState(TermuxProvisioningState.Failed(message = message, stage = stageOf(result)))
        return result
    }

    /**
     * Which install step a failure belongs to. An artifact that could not be resolved never
     * reached the download, and a missing/unusable entry is reported against the step that
     * would have used it.
     */
    private fun stageOf(result: TermuxProvisioning): TermuxInstallStage? = when (result) {
        is TermuxProvisioning.Failed -> result.stage
        is TermuxProvisioning.ArtifactUnavailable -> TermuxInstallStage.DOWNLOAD
        is TermuxProvisioning.NoArchive -> TermuxInstallStage.DOWNLOAD
        is TermuxProvisioning.Unsupported -> TermuxInstallStage.PREFIX
        else -> null
    }

    class TermuxBootstrapException(val stage: TermuxInstallStage, message: String) : Exception(message)

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_EVERY = 200

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
                if (status !in 200..299) {
                    throw IOException("HTTP $status from $url")
                }
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

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input: InputStream ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }

        fun deleteRecursively(file: File): Boolean {
            if (!file.exists()) return true
            if (file.isDirectory) {
                file.listFiles()?.forEach { deleteRecursively(it) }
            }
            return file.delete() || !file.exists()
        }
    }
}

fun TermuxSession.writeUtf8(text: String) {
    val bytes = text.toByteArray(Charsets.UTF_8)
    write(bytes, 0, bytes.size)
}
