package com.agentx.app.ubuntu

import com.agentx.app.termux.DeveloperLogCategory
import com.agentx.app.termux.DeveloperLogger
import com.agentx.app.termux.TermuxRuntime
import java.io.File
import java.util.Locale

/**
 * What kind of storage one measured location is.
 *
 * This is the distinction the app-size question actually turns on. "AgentX is using 1 GB" is not a
 * diagnosis: a persistent runtime that is meant to be there, a downloadable archive that could be
 * dropped at any time, scratch space that is only alive during an install, and a duplicate of a
 * project the user still has elsewhere are four completely different findings with four completely
 * different answers. The class is carried into the log so the reading can be acted on.
 */
enum class StorageClass(val summary: String) {
    /** Installed and meant to persist: the Ubuntu rootfs and the toolchain inside it. */
    PERSISTENT_RUNTIME("Persistent runtime (intentional — one runtime serves every project)"),

    /** The rootfs archive, kept so a retry costs no download. Re-fetchable. */
    RUNTIME_CACHE("Re-downloadable runtime cache"),

    /** Only meaningful while an install is running, or while PRoot has a process. */
    TEMPORARY("Temporary (scratch / build-time)"),

    /** A copy or mirror of a project that also exists somewhere else. Duplicated project data. */
    PROJECT_COPY("Project copies AgentX owns (duplicated project data)"),

    /** Logs and diagnostics written by the app, bounded by rotation. */
    DIAGNOSTICS("Logs and diagnostics"),

    /** The app's own data: sessions, imported skills. */
    APP_DATA("App data"),

    /** The APK's native libraries, unpacked by Android into `nativeLibraryDir`. */
    APK("APK native libraries (reinstalled with the app)"),
}

/** Bytes and file count for one measured location. */
data class StorageUsage(
    val bytes: Long,
    val files: Int,
    /** True when the walk hit [AgentxStorageAudit.MAX_ENTRIES] and the numbers are a floor. */
    val truncated: Boolean = false,
) {
    val isEmpty: Boolean get() = bytes == 0L && files == 0
}

/** One labelled location under the app's private storage (or the APK's native library directory). */
data class StorageEntry(
    val label: String,
    val path: String,
    val usage: StorageUsage,
    val storageClass: StorageClass,
)

/**
 * A reading of the app's private storage, grouped so the numbers answer "what is using the space".
 *
 * [entries] are disjoint: every byte under `filesDir`, `cacheDir` or `nativeLibraryDir` is counted
 * once, so the totals can be compared without double-counting. [projectCopies] is a *view* of the
 * project-copy entries, broken down per project, because "is this a duplicate of my project?" is
 * answered by the individual directories, not by their total.
 */
data class StorageBreakdown(
    val entries: List<StorageEntry>,
    val projectCopies: List<StorageEntry>,
    /** Files in `filesDir` plus `cacheDir`: the app's data as Android reports it. */
    val appPrivateBytes: Long,
    val appPrivateFiles: Int,
    val truncated: Boolean,
) {
    val byClass: Map<StorageClass, Long>
        get() = entries.groupBy { it.storageClass }
            .mapValues { (_, group) -> group.sumOf { it.usage.bytes } }

    /** The breakdown as developer-log lines: a total, a per-class summary, then each location. */
    fun render(): String = buildString {
        appendLine("Storage breakdown")
        appendLine("  app-private total: ${format(appPrivateBytes)} (${appPrivateFiles} file(s))")
        for (storageClass in StorageClass.entries) {
            val bytes = byClass[storageClass] ?: continue
            val files = entries.filter { it.storageClass == storageClass }.sumOf { it.usage.files }
            appendLine(
                "  ${format(bytes).padStart(10)}  ${files.toString().padStart(7)} file(s)  " +
                    "${storageClass.name}: ${storageClass.summary}",
            )
        }
        for (entry in entries.sortedByDescending { it.usage.bytes }) {
            appendLine(
                "    ${format(entry.usage.bytes).padStart(10)}  ${entry.usage.files.toString().padStart(7)}" +
                    " file(s)  ${entry.label}  ${entry.path}",
            )
        }
        if (projectCopies.isNotEmpty()) {
            appendLine("  project copies AgentX owns (each is removed with its own project):")
            for (copy in projectCopies.sortedByDescending { it.usage.bytes }) {
                appendLine(
                    "    ${format(copy.usage.bytes).padStart(10)}  " +
                        "${copy.usage.files.toString().padStart(7)} file(s)  ${copy.path}",
                )
            }
        }
        val apk = entries.filter { it.storageClass == StorageClass.APK }
        if (apk.isNotEmpty()) {
            appendLine(
                "  APK on disk: ${format(apk.sumOf { it.usage.bytes })} — reported by Android as app " +
                    "size, not as user data.",
            )
        }
        if (truncated) {
            appendLine(
                "  NOTE: the walk stopped at ${AgentxStorageAudit.MAX_ENTRIES} entries; the sizes " +
                    "above are lower bounds.",
            )
        }
    }

    private fun format(bytes: Long): String = AgentxStorageAudit.format(bytes)
}

/**
 * Measures where the app's storage actually goes.
 *
 * Written because "the app grew to 1 GB after opening a project" has several possible causes that
 * look identical from Android's app-info screen — a 30 MB archive that stays cached, ~110 MB of
 * extracted rootfs, a few hundred MB of `apt`-installed toolchain inside it, scratch space during
 * an install, and a duplicate of the project itself — and the only honest answer is to measure them
 * separately on the device. Nothing here is inferred, and nothing here changes any behaviour: it
 * reads, it counts, it logs.
 *
 * The known locations are labelled, and the rest of each directory is bucketed rather than ignored,
 * so the totals always reconcile with what Android reports.
 */
object AgentxStorageAudit {

    /**
     * Upper bound on entries visited per measurement, so a pathological tree cannot make the
     * diagnostic hang. Hitting it marks the reading as a lower bound instead of silently reporting
     * a wrong number.
     */
    const val MAX_ENTRIES: Int = 200_000

    /** The app's private subdirectory that holds the embedded developer runtime. */
    private const val RUNTIME_DIR: String = NativeRuntimeLayout.RUNTIME_SUBDIR

    /** The directory name a project copy or mirror is created under. */
    private const val PROJECT_COPY_DIR: String = NativeRuntimeLayout.WORKSPACES_DIR

    /** Imported skills. Created by the application's composition root. */
    private const val SKILLS_DIR: String = "skills"

    /** Persisted agent sessions, one JSON file each. Created by the composition root. */
    private const val AGENT_SESSIONS_DIR: String = "agent-sessions"

    /** The directory holding [DeveloperLogger.RELATIVE_PATH]. */
    private val DEVELOPER_LOG_DIR: String = DeveloperLogger.RELATIVE_PATH.substringBefore('/')

    /**
     * Reads the storage layout for one application.
     *
     * @param filesDir `context.filesDir` — everything the app persists.
     * @param cacheDir `context.cacheDir` — Android may evict this, so it is reported separately.
     * @param nativeLibraryDir `applicationInfo.nativeLibraryDir` — the APK's executable side.
     */
    fun measure(
        filesDir: String,
        cacheDir: String? = null,
        nativeLibraryDir: String? = null,
    ): StorageBreakdown {
        val budget = Budget(MAX_ENTRIES)
        val entries = ArrayList<StorageEntry>()
        val projectCopies = ArrayList<StorageEntry>()

        for (location in plan(filesDir, cacheDir, nativeLibraryDir)) {
            val file = File(location.path)
            val usage = measure(file, budget)
            entries += StorageEntry(
                label = location.label,
                path = location.path,
                usage = usage,
                storageClass = location.storageClass,
            )
            // Per-project detail, so a project copy is visible as itself and not only as a total.
            if (location.isProjectCopyRoot && file.isDirectory) {
                for (child in file.listFiles().orEmpty().sortedBy { it.name }) {
                    if (!child.isDirectory) continue
                    projectCopies += StorageEntry(
                        label = child.name,
                        path = child.absolutePath,
                        usage = measure(child, budget),
                        storageClass = StorageClass.PROJECT_COPY,
                    )
                }
            }
        }

        val appPrivate = entries.filter { it.storageClass != StorageClass.APK }
        return StorageBreakdown(
            entries = entries,
            projectCopies = projectCopies,
            appPrivateBytes = appPrivate.sumOf { it.usage.bytes },
            appPrivateFiles = appPrivate.sumOf { it.usage.files },
            truncated = budget.exhausted,
        )
    }

    /**
     * Measures and records the breakdown in the developer log.
     *
     * Blocking — it walks the whole runtime tree, so call it off the main thread.
     */
    fun log(
        filesDir: String,
        cacheDir: String? = null,
        nativeLibraryDir: String? = null,
    ): StorageBreakdown {
        val breakdown = measure(filesDir, cacheDir, nativeLibraryDir)
        DeveloperLogger.info(DeveloperLogCategory.STORAGE, "Storage audit: filesDir=$filesDir")
        breakdown.render().lineSequence()
            .filter { it.isNotBlank() }
            .forEach { line -> DeveloperLogger.info(DeveloperLogCategory.STORAGE, line) }
        return breakdown
    }

    /** `1.42 GB`, `183.4 MB`, `12.0 kB` — one decimal, units that fit a 1 GB reading. */
    fun format(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return when {
            bytes < 1024L -> "$bytes B"
            mb < 1.0 -> String.format(Locale.US, "%.1f kB", bytes / 1024.0)
            mb < 1024.0 -> String.format(Locale.US, "%.1f MB", mb)
            else -> String.format(Locale.US, "%.2f GB", mb / 1024.0)
        }
    }

    /** One location to measure, and how to label it. */
    private data class Location(
        val label: String,
        val path: String,
        val storageClass: StorageClass,
        val isProjectCopyRoot: Boolean = false,
    )

    /**
     * The locations to measure, in a fixed order.
     *
     * The runtime directory is *expanded* rather than reported as one blob: its children are the
     * whole point — the extracted rootfs, the cached archive, PRoot's scratch, the project copies —
     * and a single "developer-runtime: 900 MB" line would hide exactly the distinction that matters.
     */
    private fun plan(filesDir: String, cacheDir: String?, nativeLibraryDir: String?): List<Location> {
        val locations = ArrayList<Location>()
        val root = filesDir.trimEnd('/')

        for (child in File(root).listFiles().orEmpty().sortedBy { it.name }) {
            if (child.isDirectory && child.name == RUNTIME_DIR) {
                for (runtimeChild in child.listFiles().orEmpty().sortedBy { it.name }) {
                    locations += Location(
                        label = runtimeLabel(runtimeChild.name, runtimeChild.isDirectory),
                        path = runtimeChild.absolutePath,
                        storageClass = runtimeClass(runtimeChild.name),
                        isProjectCopyRoot = runtimeChild.isDirectory &&
                            runtimeChild.name == PROJECT_COPY_DIR,
                    )
                }
                continue
            }
            locations += Location(
                label = appLabel(child.name),
                path = child.absolutePath,
                storageClass = appClass(child.name),
                isProjectCopyRoot = child.isDirectory && child.name == PROJECT_COPY_DIR,
            )
        }

        cacheDir?.trimEnd('/')?.takeIf { it.isNotEmpty() }?.let { cache ->
            locations += Location(
                label = "Android cache (cacheDir)",
                path = cache,
                storageClass = StorageClass.TEMPORARY,
            )
        }
        nativeLibraryDir?.trimEnd('/')?.takeIf { it.isNotEmpty() }?.let { libraries ->
            locations += Location(
                label = "APK native libraries (nativeLibraryDir)",
                path = libraries,
                storageClass = StorageClass.APK,
            )
        }
        return locations
    }

    /** A child of the runtime directory: the persistent tree, its caches, or its scratch. */
    private fun runtimeLabel(name: String, directory: Boolean): String = when (name) {
        NativeRuntimeLayout.ROOTFS_DIR -> "Ubuntu rootfs + installed toolchain"
        NativeRuntimeLayout.INSTALLING_DIR -> "Rootfs under construction"
        NativeRuntimeLayout.LEGACY_STAGING_DIR -> "Legacy staging directory"
        NativeRuntimeLayout.DOWNLOADS_DIR -> "Cached rootfs archive(s)"
        NativeRuntimeLayout.TMP_DIR -> "PRoot scratch (PROOT_TMP_DIR)"
        NativeRuntimeLayout.WORKSPACES_DIR -> "Project copies (materialiser)"
        else -> if (directory) "Developer runtime: $name" else "Developer runtime file: $name"
    }

    private fun runtimeClass(name: String): StorageClass = when (name) {
        NativeRuntimeLayout.ROOTFS_DIR -> StorageClass.PERSISTENT_RUNTIME
        NativeRuntimeLayout.DOWNLOADS_DIR -> StorageClass.RUNTIME_CACHE
        NativeRuntimeLayout.TMP_DIR,
        NativeRuntimeLayout.INSTALLING_DIR,
        NativeRuntimeLayout.LEGACY_STAGING_DIR,
        -> StorageClass.TEMPORARY
        NativeRuntimeLayout.WORKSPACES_DIR -> StorageClass.PROJECT_COPY
        else -> StorageClass.PERSISTENT_RUNTIME
    }

    /** A child of `filesDir` that is not part of the developer runtime. */
    private fun appLabel(name: String): String = when (name) {
        RUNTIME_DIR -> "Developer runtime"
        PROJECT_COPY_DIR -> "Project mirrors (app-owned copies)"
        SKILLS_DIR -> "Installed skills"
        AGENT_SESSIONS_DIR -> "Agent sessions"
        DEVELOPER_LOG_DIR -> "Developer log"
        TermuxRuntime.DIAGNOSTICS_FILE -> "Terminal diagnostics"
        else -> "App files: $name"
    }

    private fun appClass(name: String): StorageClass = when (name) {
        PROJECT_COPY_DIR -> StorageClass.PROJECT_COPY
        SKILLS_DIR, AGENT_SESSIONS_DIR -> StorageClass.APP_DATA
        DEVELOPER_LOG_DIR, TermuxRuntime.DIAGNOSTICS_FILE -> StorageClass.DIAGNOSTICS
        else -> StorageClass.PERSISTENT_RUNTIME
    }

    /**
     * Bytes and files under [root], bounded by [budget].
     *
     * An iterative walk, not a recursive one: the rootfs is deep, and a self-inflicted stack
     * overflow would be a poor way to answer a question about the app's size.
     */
    private fun measure(root: File, budget: Budget): StorageUsage {
        if (root.isFile) return StorageUsage(bytes = root.length(), files = 1)
        if (!root.isDirectory) return StorageUsage(bytes = 0L, files = 0)

        var bytes = 0L
        var files = 0
        val pending = ArrayDeque<File>()
        pending.addLast(root)
        while (pending.isNotEmpty()) {
            val directory = pending.removeLast()
            val children = directory.listFiles() ?: continue
            for (child in children) {
                if (!budget.visit()) {
                    return StorageUsage(bytes = bytes, files = files, truncated = true)
                }
                if (child.isDirectory) pending.addLast(child) else {
                    bytes += child.length()
                    files += 1
                }
            }
        }
        return StorageUsage(bytes = bytes, files = files)
    }

    /** The shared entry budget for one reading, so several walks cannot exceed it together. */
    private class Budget(private val maximum: Int) {
        var exhausted: Boolean = false
            private set
        private var visited: Int = 0

        fun visit(): Boolean {
            if (visited >= maximum) {
                exhausted = true
                return false
            }
            visited += 1
            return true
        }
    }
}
