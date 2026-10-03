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

    /** The live [NativeRuntimeLayout.rootfs] is already a complete, configured Ubuntu tree. */
    data object AlreadyInstalled : UbuntuInstallResult

    /**
     * A new tree has been unpacked, validated and configured at
     * [NativeRuntimeLayout.installing]. It is **not** installed yet — it has not been verified
     * through PRoot, it has no packages, and it is not in the runtime's rootfs path. The caller
     * promotes it once it passes, with [UbuntuRootfsInstaller.promote].
     */
    data class Prepared(val archiveBytes: Long, val files: Int) : UbuntuInstallResult

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
     * Rewrites the target of every symlink under [tree] that starts with [fromPrefix] so it starts
     * with [toPrefix]; returns how many were rewritten.
     *
     * This is what makes a promoted installation possible at all. `-l` records the link-to-symlink
     * store's absolute host path inside every emulated hard link, so a tree built at
     * `<runtimeDir>/rootfs.installing` refers to that path and would be broken by the move to
     * `<runtimeDir>/rootfs`. Upstream `proot-distro` solves the same problem the same way
     * (`proot_distro/l2s.py`, `rewrite_l2s_targets`, called after every move of a rootfs).
     */
    fun rewriteSymlinkTargets(tree: String, fromPrefix: String, toPrefix: String): Int

    /**
     * The *contents* of the symbolic link at [path], or null when [path] is not a link.
     *
     * The final component is not followed. This is what makes the link-to-symlink store
     * checkable from the host: a hard-link entry that `-l` emulated is a symlink naming an
     * absolute path, and the one property that matters is whether that path is inside the guest
     * root (see [NativeRuntimeLayout.l2s]).
     */
    fun readLink(path: String): String?

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
 * download → SHA-256 → extract → validate required files, hard links and the link store →
 * configure.
 * Native PRoot in `nativeLibraryDir` is required before any download. A checksum mismatch
 * deletes the archive and aborts. The install marker is written by [LocalUbuntuRuntime]
 * only after PRoot has started `/bin/sh` and `/bin/bash` in the guest.
 *
 * The tree is unpacked directly into its final path, and the link-to-symlink store is created
 * inside it before `tar` runs. Both are requirements rather than conveniences; see
 * [NativeRuntimeLayout.l2s].
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

    /**
     * True only when a marker says installed *and* the filesystem agrees.
     *
     * The marker never decides on its own. If `/bin/bash`, `/etc/os-release`, `/usr` or `/var`
     * are missing — a tree left behind by an older build, a wiped data directory, a partial
     * unpack — this is false no matter what the marker says, and [repairIncompleteInstallation]
     * will have already deleted the tree by the time anything asks. And it never decides the other
     * way either: a *complete* tree with no marker is a tree that has not been verified yet, which
     * is a different answer from "not installed" and is reported as such.
     */
    fun isInstalled(): Boolean =
        files.entryExists(layout.marker) && isConfiguredRootfs(layout.rootfs)

    /**
     * Deletes the tree under construction.
     *
     * Called on every failure path of the installation, so a failed attempt leaves nothing that
     * could later be mistaken for a runtime. The live [NativeRuntimeLayout.rootfs] is not touched.
     */
    fun deleteInstallingTree(): Boolean = files.deleteRecursively(layout.installing)

    /** Writes the marker that says the live rootfs answered the PRoot guest probes. */
    fun writeVerificationMarker() {
        files.writeText(layout.verificationMarker, "ok\n")
    }

    /** True when a complete Ubuntu userland is present, marker or not. */
    fun hasExtractedRootfs(): Boolean = isCompleteRootfs(layout.rootfs)

    /**
     * The one authoritative answer to "is this tree a Ubuntu rootfs?", used by every caller.
     *
     * Three things have to hold, and the marker is not one of them:
     *
     * 1. every entry of [UbuntuRootfsCatalog.REQUIRED_GUEST_FILES] exists — the shell, the guest's
     *    identity, the userland directories, the package manager;
     * 2. the archive's hard-link pairs still resolve to one file each, so the extraction preserved
     *    the relationships `dpkg` and coreutils depend on;
     * 3. the link-to-symlink store the emulated links point into is **inside this tree**, because a
     *    store anywhere else resolves on the host and dangles in the guest.
     *
     * Checks 2 and 3 used to live only in the extraction path. That is what let a stale tree be
     * reused: it had every file `isUsableRootfs` asked about, so it was called installed,
     * `provision` returned early without ever re-validating, and the guest then failed on
     * `/usr/bin/perl` — which is exactly the reported symptom.
     */
    fun isCompleteRootfs(tree: String): Boolean {
        if (!files.isDirectory(tree)) return false
        if (!UbuntuRootfsCatalog.REQUIRED_GUEST_FILES.all { files.entryExists("$tree/$it") }) {
            return false
        }
        if (!hardLinksResolve(tree)) return false
        if (!linkStoreIsInside(tree)) return false
        return true
    }

    /** True when the tree is not only complete but carries the directories a runtime needs. */
    fun isConfiguredRootfs(tree: String): Boolean =
        isCompleteRootfs(tree) && UbuntuRootfsCatalog.REQUIRED_RUNTIME_DIRECTORIES.all {
            files.entryExists("$tree/$it")
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
     * `staging` is scratch left by a build that staged before activating; it is always cleared.
     *
     * A `rootfs` is removed when it is unusable, and also when [layout.recreateMarker] says the
     * tree must not be trusted — that is what a `dpkg` database damaged by a failed unpack
     * leaves behind, and it is the only honest response: the tree still passes the file checks
     * that [isUsableRootfs] can make from the host, so without the marker it would be reused and
     * every later `apt-get` would fail the same way.
     *
     * A complete extracted tree without the install marker is waiting for PRoot guest probes,
     * not a re-download. The downloaded archive is deliberately **kept**: [fetch] re-checks it
     * against the pinned SHA-256 and reuses it when it still matches, so a retry after a
     * transient extraction failure costs no download. The link-to-symlink store is inside the
     * rootfs and goes with it.
     */
    fun repairIncompleteInstallation(): Boolean {
        var removed = false
        // The tree under construction is scratch by definition: whatever is in it belongs to an
        // attempt that did not finish, and the next attempt starts from the archive. The legacy
        // staging name is cleared too so an app upgraded from an older build cannot leave a tree
        // sitting in the runtime directory.
        for (scratch in listOf(layout.installing, legacyStaging, "$rootfsOld")) {
            if (files.entryExists(scratch)) {
                removed = files.deleteRecursively(scratch) || removed
            }
        }

        val recreate = files.entryExists(layout.recreateMarker)
        val unusable = files.entryExists(layout.rootfs) && !isConfiguredRootfs(layout.rootfs)
        if (recreate || unusable) {
            if (files.entryExists(layout.rootfs)) {
                removed = files.deleteRecursively(layout.rootfs) || removed
            }
            // Whatever the markers said is void: the tree they described is gone or was never
            // valid, and a marker must never outlive the filesystem state it claims.
            clearInstallMarkers()
        }
        files.deleteRecursively(layout.recreateMarker)
        return removed
    }

    /** Scratch names that are not the tree under construction but are cleared with it. */
    private val legacyStaging: String get() = "${layout.runtimeDir}/${NativeRuntimeLayout.LEGACY_STAGING_DIR}"
    private val rootfsOld: String get() = "${layout.rootfs}.old"

    /**
     * Throws the extracted tree away and says why, so the next attempt re-extracts it.
     *
     * Used when the guest reports a state this code cannot repair *in place* — a `dpkg` database
     * with a package that never finished unpacking, most of all. Files on disk still look
     * complete from the host at that point, so the decision has to be recorded explicitly
     * rather than re-derived from the tree.
     */
    fun discardRootfs(reason: String) {
        files.writeText(layout.recreateMarker, reason.trim().ifEmpty { "unspecified" } + "\n")
        if (files.entryExists(layout.rootfs)) files.deleteRecursively(layout.rootfs)
        clearInstallMarkers()
    }

    /** Removes every marker that says part of the runtime is usable. */
    fun clearInstallMarkers() {
        files.deleteRecursively(layout.marker)
        files.deleteRecursively(layout.verificationMarker)
        files.deleteRecursively(layout.toolchainMarker)
    }

    fun provision(onStatus: (RuntimeStatus) -> Unit = {}): UbuntuInstallResult {
        ensureRuntimeDirectories()

        // Native PRoot must exist in nativeLibraryDir before any Ubuntu bytes are fetched.
        // A missing APK library is not a missing rootfs.
        try {
            requireProotTooling()
        } catch (failure: UbuntuRootfsException) {
            return fail(
                UbuntuInstallResult.Failed(failure.stage, failure.message ?: "native runtime missing"),
                failure.stage,
                onStatus,
            )
        }

        // A tree from a previous attempt is not evidence of anything, and a tree this runtime has
        // been told not to trust (`discardRootfs`) must be gone before anything is decided.
        repairIncompleteInstallation()

        // No terminal `Ready` is published here: READY belongs to the runtime, and it is only
        // reached after the installed tree has been run through PRoot. See
        // `LocalUbuntuRuntime.verifyRootfs`.
        // A complete, configured tree is reusable whether or not a marker survived: the
        // filesystem is the authority, and a marker is only ever a hint that lets a finished
        // runtime skip work. A tree that fails this is already gone — repair ran above.
        if (isConfiguredRootfs(layout.rootfs)) {
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
            UbuntuInstallResult.Prepared(archiveBytes = archive.length(), files = files)
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

        // The tree is unpacked into [NativeRuntimeLayout.installing], never into the live rootfs,
        // and it moves with a rewrite of the emulated links at the end (see [promote]).
        //
        // The rewrite is not optional. `-l` records the link-to-symlink store's *absolute host
        // path* inside every symlink it leaves behind, and the store lives inside this tree
        // (`<installing>/.l2s`); moving the tree without rewriting those targets would leave every
        // emulated hard link pointing at the path the tree used to have. See
        // [NativeRuntimeLayout.l2s].
        val tree = layout.installing
        if (!files.deleteRecursively(tree)) {
            throw UbuntuRootfsException(UbuntuInstallStage.EXTRACTION, "Could not clear $tree")
        }
        if (!files.mkdirs(tree)) {
            throw UbuntuRootfsException(UbuntuInstallStage.EXTRACTION, "Could not create $tree")
        }
        // PRoot opens PROOT_L2S_DIR at the first link(2) the archive contains and answers ENOENT
        // for that link when it cannot, so the store has to exist before tar runs. The archive has
        // no `.l2s` entry of its own, so nothing here is overwritten.
        val store = "${layout.forInstalling().l2s}"
        if (!files.mkdirs(store)) {
            throw UbuntuRootfsException(
                UbuntuInstallStage.EXTRACTION,
                "Could not create the link-to-symlink store $store",
            )
        }

        // The extraction is the one step that has to run *through* the native PRoot.
        requireProotTooling()

        val entries = tar.countEntries(archive.absolutePath)
        onStatus(RuntimeStatus(AgentxRuntimeState.EXTRACTING, progressPercent = 0, totalBytes = entries.toLong()))
        try {
            tar.extract(archive.absolutePath, tree)
        } catch (error: Exception) {
            // Leave nothing half-unpacked behind: the next attempt starts from the archive.
            files.deleteRecursively(tree)
            throw UbuntuRootfsException(
                UbuntuInstallStage.EXTRACTION,
                "Could not extract ${entry.assetName}: ${error.message}",
            )
        }

        onStatus(RuntimeStatus(AgentxRuntimeState.INSTALLING))
        try {
            validate(tree)
            configure(tree)
            validateConfigured(tree)
        } catch (invalid: UbuntuRootfsException) {
            // Nothing half-populated is left where a later attempt could mistake it for a
            // working rootfs; a retry starts from the verified archive.
            files.deleteRecursively(tree)
            clearInstallMarkers()
            throw invalid
        }

        // The install marker is written only after the guest has been run through PRoot
        // (`LocalUbuntuRuntime.verifyRootfs`). An extracted tree that cannot start /bin/bash
        // must never look installed.
        if (!isConfiguredRootfs(tree)) {
            files.deleteRecursively(tree)
            throw UbuntuRootfsException(
                UbuntuInstallStage.RUNTIME,
                "Rootfs was extracted but the required guest files are missing under $tree.",
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
        validateLinkStore(tree)
    }

    /**
     * The second half of validation: the directories a runtime needs that the archive need not
     * contain, which [configure] has just created. Checked separately so a failure names the
     * right cause — a missing `/var/cache/apt` after configure is a configuration failure, not a
     * bad download.
     */
    private fun validateConfigured(tree: String) {
        val missing = UbuntuRootfsCatalog.REQUIRED_RUNTIME_DIRECTORIES.filter { relative ->
            !files.entryExists("$tree/$relative")
        }
        if (missing.isNotEmpty()) {
            throw UbuntuRootfsException(
                UbuntuInstallStage.CONFIGURATION,
                "The extracted rootfs is missing the directories apt/dpkg need after " +
                    "configuration: ${missing.joinToString()}.",
            )
        }
    }

    /**
     * Moves the finished tree from [NativeRuntimeLayout.installing] into place.
     *
     * Called only after the tree has been extracted, validated, configured, installed into and
     * verified through PRoot. Nothing about the *contents* changes here; this is the step that
     * makes the tree the one the runtime will use, and it is deliberately the last thing that
     * happens before the markers are written.
     *
     * The emulated hard links are rewritten first, because `-l` wrote the tree's *current* path
     * into them (see [UbuntuFiles.rewriteSymlinkTargets]). The old rootfs is then moved aside
     * rather than deleted, so a failure to move the new tree in can be undone instead of leaving
     * the device with no runtime at all. Only after the new tree is confirmed in place is the old
     * one deleted.
     */
    fun promote(): Boolean {
        val finished = layout.installing
        val target = layout.rootfs
        if (!isConfiguredRootfs(finished)) return false

        val fromPrefix = finished.trimEnd('/') + "/"
        val toPrefix = target.trimEnd('/') + "/"
        files.rewriteSymlinkTargets(finished, fromPrefix, toPrefix)

        val previous = "$target.old"
        files.deleteRecursively(previous)
        val hadPrevious = files.entryExists(target)
        if (hadPrevious && !files.rename(target, previous)) return false

        if (!files.rename(finished, target)) {
            // Put the tree that was working back where it was.
            if (hadPrevious) files.rename(previous, target)
            return false
        }
        if (!isConfiguredRootfs(target)) {
            // The move produced something that is not a rootfs: undo rather than promote it.
            files.rename(target, finished)
            if (hadPrevious) files.rename(previous, target)
            return false
        }
        files.deleteRecursively(previous)
        return true
    }

    /** Non-throwing form of [validateHardLinks], for the predicates above. */
    private fun hardLinksResolve(tree: String): Boolean =
        UbuntuRootfsCatalog.REQUIRED_HARD_LINKS.all { hardLink ->
            val file = "$tree/${hardLink.file}"
            val link = "$tree/${hardLink.link}"
            files.entryExists(file) && files.entryExists(link) && files.resolvesToSameFile(file, link)
        }

    /** Non-throwing form of [validateLinkStore], for the predicates above. */
    private fun linkStoreIsInside(tree: String): Boolean {
        val rootPrefix = tree.trimEnd('/') + "/"
        val store = "$tree/${NativeRuntimeLayout.L2S_DIR}"
        if (!store.startsWith(rootPrefix)) return false
        return UbuntuRootfsCatalog.REQUIRED_HARD_LINKS.all { hardLink ->
            listOf(hardLink.file, hardLink.link).all { relative ->
                val target = files.readLink("$tree/$relative") ?: return@all true
                target.startsWith(rootPrefix)
            }
        }
    }

    /**
     * Proves the emulated hard links point somewhere the *guest* can reach.
     *
     * [validateHardLinks] follows the links with the host's `stat`, which is the wrong vantage
     * point for this: PRoot's `-l` writes the store's absolute **host** path into every symlink
     * it creates, so a store that sits beside the rootfs still resolves perfectly on the host
     * while being unreachable inside the guest. PRoot's `canonicalize()` strips the guest root
     * prefix from a symlink target only when the target lies under the root, and re-reads
     * anything else as a *guest* path that does not exist — which is exactly what made
     * `dpkg --unpack perl-base` fail with
     * `error setting ownership of '/usr/bin/perl5.38.2.dpkg-new': No such file or directory`.
     *
     * The guest-visible check is the prefix itself: every symlink that stands in for a hard link
     * must name a path inside the tree that is about to become `/`. A real hard link (no symlink
     * at all) is fine and is skipped — this rejects the wrong *store location*, not the mechanism.
     */
    private fun validateLinkStore(tree: String) {
        val rootPrefix = tree.trimEnd('/') + "/"
        // Derived from the tree being validated, not from the live layout: during an installation
        // the tree is rootfs.installing and its store is inside *it*. Asking layout.l2s here was
        // wrong the moment extraction stopped writing to the live rootfs.
        val store = "$tree/${NativeRuntimeLayout.L2S_DIR}"
        if (!store.startsWith(rootPrefix)) {
            throw UbuntuRootfsException(
                UbuntuInstallStage.VALIDATION,
                "PROOT_L2S_DIR is $store, outside the rootfs $rootPrefix. PRoot's link-to-symlink " +
                    "store has to live inside the guest rootfs, otherwise the emulated hard " +
                    "links resolve on the host but dangle inside the guest.",
            )
        }
        for (hardLink in UbuntuRootfsCatalog.REQUIRED_HARD_LINKS) {
            for (relative in listOf(hardLink.file, hardLink.link)) {
                val path = "$tree/$relative"
                // No symlink means a real hard link (a host filesystem that allows one); there is
                // no store path involved and nothing to check.
                val target = files.readLink(path) ?: continue
                if (target.startsWith(rootPrefix)) continue
                throw UbuntuRootfsException(
                    UbuntuInstallStage.VALIDATION,
                    "$path points at $target, which is outside the guest rootfs. PRoot's " +
                        "link-to-symlink store must live inside the rootfs " +
                        "(PROOT_L2S_DIR=$store); a store kept elsewhere produces hard links the " +
                        "guest cannot open, and apt/dpkg then fail on the first package that " +
                        "contains one.",
                )
            }
        }
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
        val missing = NativeRuntimeLayout.REQUIRED_LIBRARIES
            .filter { name -> !files.entryExists("${layout.nativeLibraryDir}/$name") }
        if (missing.isEmpty()) return
        throw UbuntuRootfsException(
            UbuntuInstallStage.RUNTIME,
            NativeRuntimeProbe.MISSING_APK_MESSAGE +
                " nativeLibraryDir=${layout.nativeLibraryDir} (missing ${missing.joinToString()}).",
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
        // Ubuntu Base ships the deb822 `/etc/apt/sources.list.d/ubuntu.sources`. Keeping it while
        // also writing `/etc/apt/sources.list` makes apt report every single target as
        // "configured multiple times" — dozens of warnings per command, and two sources lists to
        // reason about when one of them is wrong. The shipped file is the duplicate here.
        files.deleteRecursively("$tree/etc/apt/sources.list.d/ubuntu.sources")

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

        // Directories apt, dpkg and apt's own hooks need before the first command runs. They are
        // created rather than required: the base archive does not have to ship an empty
        // /var/cache/apt, and a validation that demanded one would reject a perfectly good Ubuntu
        // userland. Created first, then validated — see validateConfigured.
        for (relative in UbuntuRootfsCatalog.REQUIRED_RUNTIME_DIRECTORIES) {
            files.mkdirs("$tree/$relative")
        }

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

    /**
     * Written only after PRoot has started `/bin/sh` and `/bin/bash` inside the extracted tree.
     * An extracted rootfs that cannot execute a shell must never look installed.
     */
    fun writeInstallMarker() {
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

    fun clearInstallMarker() {
        files.deleteRecursively(layout.marker)
    }

    /**
     * Written only after every package in [UbuntuRootfsCatalog.TOOLCHAIN_PACKAGES] has been
     * installed *and* every entry of [UbuntuRootfsCatalog.REQUIRED_TOOLCHAIN_COMMANDS] has
     * answered inside the guest. A failed or skipped package installation leaves it absent, so
     * the runtime stays not-READY instead of presenting a shell without git, python3 or node.
     */
    fun writeToolchainMarker(verified: List<String>) {
        files.writeText(
            layout.toolchainMarker,
            buildString {
                append("ok\n")
                append("ubuntu=${UbuntuRootfsCatalog.UBUNTU_RELEASE}\n")
                for (label in verified) append("verified=$label\n")
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

    /**
     * `Os.readlink` throws `ErrnoException(EINVAL)` on anything that is not a symlink, which is
     * how "a real hard link, nothing to check" is told apart from "a link naming a path".
     */
    override fun readLink(path: String): String? = try {
        Os.readlink(path)
    } catch (notALink: Exception) {
        null
    }

    override fun rename(from: String, to: String): Boolean = File(from).renameTo(File(to))

    /**
     * Walks [tree] and rewrites the symlinks that name [fromPrefix].
     *
     * Directories are descended by their real type only: `File.isDirectory` follows links, so a
     * symlinked directory would be walked twice (or endlessly), and an emulated hard link to a
     * directory does not exist anyway. Everything else is tested with `readlink` and left alone
     * when it is not a link, which is the common case by a wide margin.
     */
    override fun rewriteSymlinkTargets(tree: String, fromPrefix: String, toPrefix: String): Int {
        var rewritten = 0
        val pending = ArrayDeque<String>()
        pending.addLast(tree)
        while (pending.isNotEmpty()) {
            val current = pending.removeLast()
            val entries = File(current).listFiles() ?: continue
            for (entry in entries) {
                val path = entry.absolutePath
                val isLink = isSymlinkPath(path)
                if (isLink) {
                    val target = try {
                        Os.readlink(path)
                    } catch (unreadable: Exception) {
                        null
                    }
                    if (target != null && target.startsWith(fromPrefix)) {
                        val replacement = toPrefix + target.removePrefix(fromPrefix)
                        // A symlink cannot be edited in place; it is replaced.
                        if (entry.delete() && try {
                                Os.symlink(replacement, path)
                                true
                            } catch (failed: Exception) {
                                false
                            }
                        ) {
                            rewritten++
                        }
                    }
                } else if (entry.isDirectory) {
                    pending.addLast(path)
                }
            }
        }
        return rewritten
    }

    private fun isSymlinkPath(path: String): Boolean = try {
        Os.lstat(path).st_mode and 0xF000 == 0xA000
    } catch (missing: Exception) {
        false
    }

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
