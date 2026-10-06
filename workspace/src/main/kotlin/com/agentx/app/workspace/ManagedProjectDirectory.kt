package com.agentx.app.workspace

import com.agentx.app.core.ForgeResult
import com.agentx.app.core.failure
import com.agentx.app.core.success
import java.io.File

/**
 * Creates empty projects inside AgentX-managed storage.
 *
 * Projects live as plain directories under one app-private root
 * (`<filesDir>/projects/<name>`), the same place AgentX keeps the repositories it clones: outside
 * the Ubuntu rootfs and outside any temporary runtime scratch space. Because it is a real host
 * path, the terminal's existing project binding can mount it at `/workspace` in place — nothing
 * is copied into the guest.
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
                    message = "A project name cannot point outside the projects folder.",
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

    private fun storageUnavailable(name: String): WorkspaceResult<Nothing> =
        failure(
            WorkspaceError(
                code = WorkspaceErrorCode.PROJECT_STORAGE_UNAVAILABLE,
                message = "Could not create \"$name\" because project storage is not available.",
            ),
        )

    companion object {
        /**
         * Directory under the app's private `filesDir` that holds managed projects — the same
         * location AgentX uses for the repositories it clones.
         */
        const val DIRECTORY_NAME: String = "projects"
    }
}
