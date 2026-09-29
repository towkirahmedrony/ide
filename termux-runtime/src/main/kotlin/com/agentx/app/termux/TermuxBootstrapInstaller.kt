package com.agentx.app.termux

import android.system.Os
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Result of making sure a Termux userland exists. */
sealed interface TermuxProvisioning {

    /** Nothing to do: `$PREFIX/bin` already holds a shell. */
    data object AlreadyInstalled : TermuxProvisioning

    /** The archive was downloaded, verified and unpacked. */
    data class Installed(val bytes: Long, val files: Int, val symlinks: Int) : TermuxProvisioning

    /** The app's prefix cannot host official Termux artifacts. See `TermuxPrefixPolicy`. */
    data class Unsupported(val reason: String, val remedy: String) : TermuxProvisioning

    /** No bootstrap archive is published for this device's ABI. */
    data class NoArchive(val supportedAbis: List<String>) : TermuxProvisioning

    /** Download, verification or extraction failed; [failed] says where. */
    data class Failed(val failed: String, val message: String) : TermuxProvisioning
}

/** Coarse progress for the provisioning UI. */
sealed interface TermuxProvisioningState {
    data object Idle : TermuxProvisioningState
    data class Downloading(val percent: Int, val receivedBytes: Long, val totalBytes: Long) : TermuxProvisioningState
    data class Verifying(val percent: Int) : TermuxProvisioningState
    data class Extracting(val files: Int) : TermuxProvisioningState
    data class Ready(val installed: TermuxProvisioning) : TermuxProvisioningState
    data class Failed(val message: String) : TermuxProvisioningState
}

/**
 * Installs the official Termux bootstrap into the app's prefix.
 *
 * The sequence follows `TermuxInstaller.setupBootstrapIfNeeded`:
 * clear the staging prefix, extract there, apply `SYMLINKS.txt`, mark the executables, then move
 * the staging directory into place as `$PREFIX` in one rename. Doing the move last means an
 * interrupted install leaves no half-populated prefix that later code would mistake for a
 * working one.
 *
 * Differences from upstream are intentional and documented:
 * - the archive is downloaded instead of embedded, because embedding four ~100 MB archives plus
 *   the NDK obfuscation step is not worth it for an IDE that already needs the network;
 * - the SHA-256 pins come from `TermuxBootstrapCatalog`, so the check still happens;
 * - nothing is extracted unless [TermuxPrefixPolicy] confirms the prefix is one the official
 *   artifacts were built for.
 *
 * Blocking I/O on purpose: callers run this on a background dispatcher.
 */
class TermuxBootstrapInstaller(
    private val paths: TermuxPaths,
    /** Accepted ABIs in preference order, normally `Build.SUPPORTED_ABIS`. */
    private val supportedAbis: List<String>,
    /** Downloads `url` to `target`, reporting `(received, total)`; [defaultDownload] by default. */
    private val download: (String, File, (Long, Long) -> Unit) -> File = TermuxBootstrapInstaller::defaultDownload,
    /** Injects the layout decision so tests do not need a real `/data/data/com.termux`. */
    private val prefixSupport: (TermuxPaths) -> TermuxPrefixSupport = TermuxPrefixPolicy::evaluate,
) {

    /**
     * True once `$PREFIX/bin` holds a login shell.
     *
     * `File.isFile()` follows symlinks, which matters because the bootstrap installs `sh`, `bash`
     * and friends as links; the check is therefore a real one, not a name match.
     */
    fun isInstalled(): Boolean = TermuxShellResolver.LOGIN_SHELL_BINARIES.any { name ->
        File("${paths.bin}/$name").isFile
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

        val entry = TermuxBootstrapCatalog.forAbis(supportedAbis)
            ?: return fail(TermuxProvisioning.NoArchive(supportedAbis), onState)

        return try {
            val archive = fetch(entry, onState)
            val result = extract(entry, archive, onState)
            onState(TermuxProvisioningState.Ready(result))
            result
        } catch (io: IOException) {
            fail(TermuxProvisioning.Failed("network", io.message ?: "download failed"), onState)
        } catch (security: SecurityException) {
            fail(TermuxProvisioning.Failed("verification", security.message ?: "checksum mismatch"), onState)
        } catch (corrupt: TermuxBootstrapException) {
            fail(TermuxProvisioning.Failed(corrupt.stage, corrupt.message ?: "bootstrap archive invalid"), onState)
        }
    }

    private fun fetch(
        entry: TermuxBootstrapCatalog.Entry,
        onState: (TermuxProvisioningState) -> Unit,
    ): File {
        val target = File(paths.downloadDir, entry.fileName)
        if (target.isFile && sha256(target) == entry.sha256) return target

        target.parentFile?.mkdirs()
        onState(TermuxProvisioningState.Downloading(percent = 0, receivedBytes = 0, totalBytes = 0))
        val downloaded = try {
            download(entry.url, target) { received, total ->
                val percent = if (total > 0) ((received * 100) / total).toInt().coerceIn(0, 99) else 0
                onState(TermuxProvisioningState.Downloading(percent, received, total))
            }
        } catch (io: IOException) {
            throw IOException("Could not download ${entry.url}: ${io.message}", io)
        }

        onState(TermuxProvisioningState.Verifying(percent = 100))
        val digest = sha256(downloaded)
        if (digest != entry.sha256) {
            // Never keep an archive that failed verification: the next run must re-download.
            downloaded.delete()
            throw SecurityException(
                "Bootstrap ${entry.fileName} failed SHA-256 verification " +
                    "(expected ${entry.sha256}, got $digest). Nothing was installed.",
            )
        }
        return downloaded
    }

    private fun extract(
        entry: TermuxBootstrapCatalog.Entry,
        archive: File,
        onState: (TermuxProvisioningState) -> Unit,
    ): TermuxProvisioning {
        val staging = File(paths.stagingPrefix)
        deleteRecursively(staging)
        if (!staging.mkdirs() && !staging.isDirectory) {
            throw TermuxBootstrapException("staging", "Could not create ${staging.absolutePath}")
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
                        throw TermuxBootstrapException("extract", "Unsafe archive entry: $name")
                    }
                    if (TermuxBootstrapArchive.isManifestEntry(name)) {
                        val manifest = zip.readBytes().toString(Charsets.UTF_8)
                        val parsed = TermuxBootstrapArchive.parseSymlinks(manifest)
                        if (parsed.invalid.isNotEmpty()) {
                            throw TermuxBootstrapException(
                                "extract",
                                "Malformed ${TermuxBootstrapArchive.SYMLINK_MANIFEST}: ${parsed.invalid.first()}",
                            )
                        }
                        symlinks += parsed.links
                        zip.closeEntry()
                        continue
                    }

                    val target = File(staging, name.removePrefix("./"))
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
                            Os.chmod(target.absolutePath, 0b111_000_000)
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
                "extract",
                "No ${TermuxBootstrapArchive.SYMLINK_MANIFEST} in ${entry.fileName}; refusing to install a prefix " +
                    "where the shell and package manager would not resolve.",
            )
        }
        for (link in symlinks) {
            val linkPath = TermuxBootstrapArchive.resolveSymlink(paths.stagingPrefix, link.linkPath)
            File(linkPath).parentFile?.mkdirs()
            try {
                Os.symlink(link.target, linkPath)
            } catch (exists: Exception) {
                // Re-installing over a leftover link is fine; a genuinely broken link is not.
                if (!File(linkPath).exists()) {
                    throw TermuxBootstrapException("symlink", "Could not create $linkPath -> ${link.target}")
                }
            }
        }

        val prefix = File(paths.prefix)
        if (prefix.exists() && !deleteRecursively(prefix)) {
            throw TermuxBootstrapException("prefix", "Could not clear ${prefix.absolutePath}")
        }
        if (!staging.renameTo(prefix)) {
            throw TermuxBootstrapException(
                "prefix",
                "Could not move ${staging.absolutePath} to ${prefix.absolutePath}",
            )
        }

        createRuntimeDirectories()
        return TermuxProvisioning.Installed(bytes = archive.length(), files = files, symlinks = symlinks.size)
    }

    /**
     * Directories Termux expects to exist. `$PREFIX/tmp` is writable scratch, `$HOME` is where a
     * session starts, and `$ROOTFS/workspaces` is where SAF projects are mirrored.
     */
    fun createRuntimeDirectories() {
        for (directory in listOf(paths.tmp, paths.home, paths.workspaces, paths.homeStorage)) {
            File(directory).mkdirs()
        }
    }

    /** Writes `$PREFIX/etc/termux/termux.env`, which `$PREFIX/etc/profile` sources. */
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

    private fun fail(
        result: TermuxProvisioning,
        onState: (TermuxProvisioningState) -> Unit,
    ): TermuxProvisioning {
        val message = when (result) {
            is TermuxProvisioning.Unsupported -> result.reason
            is TermuxProvisioning.NoArchive -> "No Termux bootstrap is published for ${result.supportedAbis.joinToString()}."
            is TermuxProvisioning.Failed -> result.message
            else -> "Provisioning did not complete."
        }
        onState(TermuxProvisioningState.Failed(message))
        return result
    }

    class TermuxBootstrapException(val stage: String, message: String) : Exception(message)

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_EVERY = 200

        /** Downloads `url` to `target`, reporting `(received, total)` for the progress bar. */
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

/** Buffered stdin wrapper so callers cannot forget to encode a keystroke. */
fun TermuxSession.writeUtf8(text: String) {
    val bytes = text.toByteArray(Charsets.UTF_8)
    write(bytes, 0, bytes.size)
}
