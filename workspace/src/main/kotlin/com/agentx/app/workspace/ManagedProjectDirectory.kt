package com.agentx.app.workspace

import com.agentx.app.core.ForgeResult
import com.agentx.app.core.failure
import com.agentx.app.core.success
import java.io.File

/**
 * Creates empty projects inside AgentX-managed storage.
 *
 * A project is a plain directory that is a direct child of one AgentX-owned root — `<root>/<name>`
 * — which by default is the user-visible `AgentX` folder in shared storage that [AgentxProjectRoot]
 * names, and the same root the repositories AgentX clones are put in. So projects stay outside the
 * Ubuntu rootfs and outside any temporary runtime scratch space, and because the root is a real host
 * path the terminal's existing project binding can mount a project at `/workspace` in place —
 * nothing is copied into the guest.
 *
 * The root is injected rather than resolved here: which directory holds AgentX projects is a
 * deployment decision the composition root makes once, and this class only ever writes inside
 * whatever it is given.
 *
 * Only the container is created here. No template, language file, Gradle/Kotlin/Node/Python file,
 * README or Git repository is generated: this is project creation, not scaffolding.
 */
class ManagedProjectDirectory(private val root: File) {

    /**
     * Creates the directory for [name] and returns its real path, or fails.
     *
     * The name is validated first, the resolved target is re-checked for containment, and an
     * existing directory is reported rather than overwritten. The root is created on first use; a
     * root that cannot be created or is not a directory is reported as unavailable storage.
     */
    fun create(name: String): WorkspaceResult<String> {
        val clean = when (val validated = ProjectName.validate(name)) {
            is ForgeResult.Success -> validated.value
            is ForgeResult.Failure -> return validated
        }

        val base = runCatching { root.canonicalFile }.getOrElse { root.absoluteFile }
        val target = runCatching { File(base, clean).canonicalFile }.getOrElse { File(base, clean) }
        // Defence in depth: the name is already a single segment, but the canonical target must
        // still be a direct child of the managed root.
        if (target.parentFile?.path != base.path) {
            return failure(
                WorkspaceError(
                    code = WorkspaceErrorCode.INVALID_PROJECT_NAME,
                    message = "A project name cannot point outside the project folder.",
                ),
            )
        }

        if (target.exists()) return alreadyExists(clean, target.path)

        if (!base.exists() && !base.mkdirs()) return storageUnavailable(clean)
        if (!base.isDirectory) return storageUnavailable(clean)

        val created = runCatching { target.mkdir() }.getOrDefault(false)
        if (!created) {
            return if (target.exists()) alreadyExists(clean, target.path) else storageUnavailable(clean)
        }
        return success(target.path)
    }

    /**
     * Best-effort removal of a project directory this class created.
     *
     * Used to undo a project whose directory was made but which then failed to open, so a retry
     * with the same name is not blocked by an empty leftover.
     */
    fun discard(projectPath: String) {
        runCatching { File(projectPath).deleteRecursively() }
    }

    private fun alreadyExists(name: String, path: String): WorkspaceResult<Nothing> =
        failure(
            WorkspaceError(
                code = WorkspaceErrorCode.PROJECT_ALREADY_EXISTS,
                message = "A project named \"$name\" already exists.",
                path = path,
            ),
        )

    /**
     * The root is unusable — it could not be created, or it is not a directory. The message names
     * what to do about it rather than only what failed, because the usual cause is storage access
     * AgentX has not been granted, and the app's own Permissions page is where that is fixed.
     */
    private fun storageUnavailable(name: String): WorkspaceResult<Nothing> =
        failure(
            WorkspaceError(
                code = WorkspaceErrorCode.PROJECT_STORAGE_UNAVAILABLE,
                message = "Could not create \"$name\" because AgentX cannot write to its project " +
                    "folder. Check the storage access AgentX needs in Settings → Permissions, " +
                    "then try again.",
                path = root.path,
            ),
        )
}
