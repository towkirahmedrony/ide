package com.agentx.app.app

import com.agentx.app.termux.TermuxRuntime
import com.agentx.app.termux.TermuxWorkspaceBindings
import com.agentx.app.ubuntu.LocalUbuntuRuntime
import com.agentx.app.workspace.OwnedWorkspaceProjectStorage
import com.agentx.app.workspace.ProjectDirectoryNaming
import com.agentx.app.workspace.WorkspaceProjectStorage
import com.agentx.app.workspace.WorkspaceRecord

/**
 * Where AgentX keeps its own per-project data, and how it names it.
 *
 * A project relates to app-private storage in exactly two ways, and a project delete has to cover
 * both. Neither is "the project" as the user sees it, and neither is the shared runtime:
 *
 * ```
 * <filesDir>/developer-runtime/workspaces/<name>   Ubuntu runtime materialiser (SAF project copy)
 * <filesDir>/workspaces/<name>                     Legacy Termux workspace mirrors
 * <filesDir>/projects/<name>                       Projects AgentX created itself
 * ```
 *
 * The first two are **copy roots**: AgentX duplicated a project the user selected, so the name
 * there is whatever a runtime called it, and it is taken from [TermuxWorkspaceBindings] — the same
 * functions the runtimes use to create those directories, because a cleaner that re-derives a name
 * is a cleaner that stops matching the moment either side changes.
 *
 * The third is a **managed root**: a project AgentX created is the directory itself
 * ([ManagedProjectDirectory]), so the project is removed by removing that directory — and only when
 * the record's handle really names a direct child of the root, which is what stops a project called
 * `app` from making a delete of an unrelated project called `app` remove the wrong directory.
 *
 * The rootfs, the downloaded archive, PRoot's scratch space and the native libraries live beside
 * these roots, are one for every project, and are therefore never in scope — [copyRoots] and
 * [managedRoots] name only the project-owned directories, which is what keeps that true by
 * construction rather than by care.
 */
object AgentxProjectStorage {

    /**
     * The AgentX-owned roots that can hold a *copy* of one project.
     *
     * A runtime that is not wired into this build contributes no root: there is nothing it could
     * have created, and listing a root anyway would risk a delete targeting a directory that
     * belongs to a different subsystem.
     */
    fun copyRoots(
        developerRuntime: LocalUbuntuRuntime?,
        terminalRuntime: TermuxRuntime?,
    ): List<String> = listOfNotNull(
        developerRuntime?.layout?.workspaces,
        terminalRuntime?.paths?.workspaces,
    ).filter { it.isNotBlank() }

    /**
     * The AgentX-owned roots whose direct children are projects AgentX created.
     *
     * Derived from [ManagedProjectDirectory], which both creates those directories and names the
     * root they live in, so creation and cleanup cannot disagree about where a project is.
     */
    fun managedRoots(managedProjectsRoot: String?): List<String> =
        listOfNotNull(managedProjectsRoot).filter { it.isNotBlank() }

    /**
     * A [WorkspaceProjectStorage] limited to [copyRoots] and [managedRoots].
     *
     * Both copy names are offered because both are in use: the handle-keyed one is what the Ubuntu
     * materialiser writes, and the id-keyed one is the legacy mirror's name. A project owns
     * whichever of them exists; a name that produces no directory is simply skipped.
     */
    fun create(
        copyRoots: List<String>,
        managedRoots: List<String> = emptyList(),
    ): WorkspaceProjectStorage = OwnedWorkspaceProjectStorage(
        ownedRoots = copyRoots,
        naming = ProjectDirectoryNaming { record -> directoryNames(record) },
        managedRoots = managedRoots,
    )

    /** The directory names the runtimes use for a copy of one project, de-duplicated. */
    fun directoryNames(record: WorkspaceRecord): List<String> = listOf(
        TermuxWorkspaceBindings.mirrorSegment(record.handle),
        TermuxWorkspaceBindings.safeSegment(record.metadata.id.value),
    ).distinct()
}
