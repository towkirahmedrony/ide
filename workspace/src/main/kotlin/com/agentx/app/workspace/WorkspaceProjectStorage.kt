package com.agentx.app.workspace

import java.io.File

/**
 * What removing one project's AgentX-owned storage did.
 *
 * [removed] and [failed] hold absolute paths, so a caller can say exactly what was deleted and
 * what was not instead of reporting a bare success. [ok] is true only when nothing failed: a
 * partial cleanup must not be presented as a finished one.
 */
data class WorkspaceProjectStorageReport(
    val removed: List<String> = emptyList(),
    val failed: List<String> = emptyList(),
) {
    val ok: Boolean get() = failed.isEmpty()

    val isEmpty: Boolean get() = removed.isEmpty() && failed.isEmpty()
}

/**
 * The per-project directory names a runtime creates inside AgentX-owned storage.
 *
 * Injected rather than re-derived here. The cleaner has to name exactly the directories the
 * runtime named, and it cannot know how a runtime spells them: a cleaner that guesses either
 * deletes nothing, or — worse — deletes a directory that belongs to something else.
 */
fun interface ProjectDirectoryNaming {
    /** Every directory name [record]'s project could own under one AgentX-owned root. */
    fun directoryNames(record: WorkspaceRecord): List<String>
}

/**
 * The storage AgentX created *for* a project: a copy it materialised, a mirror it wrote, the
 * directory it created for a project the user asked it to make.
 *
 * Implementations may only ever touch locations the app itself created and named:
 *
 * - never a folder the user selected (a SAF tree, or a path in shared storage) — those files are
 *   the user's, and deleting them is not AgentX's decision;
 * - never the shared runtime (the Ubuntu rootfs, the downloaded archive, PRoot's scratch, the
 *   native libraries) — that is one runtime for every project, not this project's data;
 * - never another project's directory.
 *
 * Removing a project therefore removes its *footprint*, and the two rules above are the reason
 * [ownedLocations] exists: what a delete would touch is inspectable, and testable, before it runs.
 */
interface WorkspaceProjectStorage {

    /** Every AgentX-owned location belonging to [record] that exists right now. */
    fun ownedLocations(record: WorkspaceRecord): List<String>

    /**
     * Deletes those locations.
     *
     * Never throws: a location that could not be removed is reported in
     * [WorkspaceProjectStorageReport.failed] so the caller can tell the user the delete did not
     * fully happen, rather than claiming it did.
     */
    fun remove(record: WorkspaceRecord): WorkspaceProjectStorageReport
}

/** For builds with no project-owned storage (previews, tests, a runtime that never copies). */
object NoWorkspaceProjectStorage : WorkspaceProjectStorage {

    override fun ownedLocations(record: WorkspaceRecord): List<String> = emptyList()

    override fun remove(record: WorkspaceRecord): WorkspaceProjectStorageReport =
        WorkspaceProjectStorageReport()
}

/**
 * [WorkspaceProjectStorage] over a fixed set of AgentX-owned root directories.
 *
 * The roots are the only places this can ever delete from — that is the whole safety argument, and
 * it is why they are a constructor parameter rather than being derived from the workspace handle. A
 * handle is user-controlled data; a root is not.
 *
 * There are two kinds of root, because a project relates to AgentX storage in two ways:
 *
 * - a **copy root** holds copies AgentX made of a project the user selected. The name there is
 *   whatever the runtime called it, so it comes from [naming], and a candidate is
 *   `<root>/<name>`;
 * - a **managed root** holds projects AgentX created itself (`<root>/<name>`, see
 *   [ManagedProjectDirectory]). There the project *is* the directory the record's handle names, so
 *   the candidate is that directory — and only when the handle really is a direct child of the
 *   root, which is what stops a project named like another project from matching it.
 *
 * Either way a name has to be a single safe path segment: empty, `.`, `..`, a separator or a NUL is
 * refused rather than sanitised, so no handle — however hostile — can turn a project delete into a
 * delete of something outside an owned root or of the root itself.
 *
 * Filesystem access is injected so the whole rule set is unit tested without a device.
 */
class OwnedWorkspaceProjectStorage(
    ownedRoots: List<String>,
    private val naming: ProjectDirectoryNaming,
    /**
     * Roots whose direct children are the projects AgentX created. A record whose handle names such
     * a child owns that child.
     */
    managedRoots: List<String> = emptyList(),
    private val isDirectory: (String) -> Boolean = { path -> File(path).isDirectory },
    private val deleteRecursively: (String) -> Boolean = { path -> File(path).deleteRecursively() },
) : WorkspaceProjectStorage {

    /** Normalised, de-duplicated, non-blank roots. Never empty strings, never trailing slashes. */
    private val roots: List<String> = normalize(ownedRoots)

    private val managed: List<String> = normalize(managedRoots)

    override fun ownedLocations(record: WorkspaceRecord): List<String> =
        locations(record).filter(isDirectory)

    override fun remove(record: WorkspaceRecord): WorkspaceProjectStorageReport {
        val removed = ArrayList<String>()
        val failed = ArrayList<String>()
        for (path in locations(record)) {
            if (!isDirectory(path)) continue
            if (deleteRecursively(path)) removed += path else failed += path
        }
        return WorkspaceProjectStorageReport(removed = removed, failed = failed)
    }

    /** `<root>/<name>` per copy root, plus the managed project directory when the handle names one. */
    private fun locations(record: WorkspaceRecord): List<String> {
        val copies = run {
            val names = naming.directoryNames(record)
                .mapNotNull(::safeSegment)
                .distinct()
            if (names.isEmpty() || roots.isEmpty()) emptyList()
            else roots.flatMap { root -> names.map { name -> "$root/$name" } }
        }
        return (copies + listOfNotNull(managedProjectLocation(record))).distinct()
    }

    /**
     * The directory [record] names inside a managed root, when it really names one.
     *
     * The handle has to be a path whose parent is exactly a managed root: a project called `app`
     * must not turn a delete of an unrelated project that was also called `app` into the removal of
     * `projects/app`. A `content://` handle never matches, so a SAF project is never in scope here.
     */
    private fun managedProjectLocation(record: WorkspaceRecord): String? {
        val raw = record.handle.trim().trimEnd('/')
        if (managed.isEmpty() || raw.isEmpty() || !raw.startsWith("/")) return null
        val parent = raw.substringBeforeLast('/', missingDelimiterValue = "")
        val name = safeSegment(raw.substringAfterLast('/')) ?: return null
        val root = managed.firstOrNull { it == normalizePath(parent) } ?: return null
        return "$root/$name"
    }

    private fun normalize(paths: List<String>): List<String> =
        paths.mapNotNull { path -> path.trim().takeIf { it.isNotEmpty() } }
            .map(::normalizePath)
            .filter { it.isNotEmpty() }
            .distinct()

    /**
     * A comparable form of a path: canonical where the platform can resolve it (`/data/data/…`
     * versus `/data/user/0/…` is the same directory), absolute otherwise.
     */
    private fun normalizePath(path: String): String {
        val file = File(path.trim().trimEnd('/').ifEmpty { "/" })
        return runCatching { file.canonicalPath }.getOrElse { file.absolutePath }.trimEnd('/')
    }

    /** A single path segment that cannot traverse, cannot be a directory alias, and cannot hide. */
    private fun safeSegment(raw: String): String? {
        val name = raw.trim()
        if (name.isEmpty()) return null
        if (name == "." || name == "..") return null
        if (name.contains('/') || name.contains('\\') || name.contains('\u0000')) return null
        return name
    }
}
