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
 * A project's footprint is not "the project": it is the copies AgentX made of it, and there are
 * exactly two places that can hold one. Both are app-private directories this app created for
 * itself:
 *
 * ```
 * <filesDir>/developer-runtime/workspaces/<name>   Ubuntu runtime materialiser (SAF project copy)
 * <filesDir>/workspaces/<name>                     Legacy Termux workspace mirrors
 * ```
 *
 * Neither is the shared runtime. The rootfs, the downloaded archive, PRoot's scratch space and the
 * native libraries live beside them, are one for every project, and are therefore never in scope
 * for a project delete — [ownedRoots] names only the two project-copy roots, which is what keeps
 * that true by construction rather than by care.
 *
 * The names are taken from [TermuxWorkspaceBindings], the same functions the runtimes use to create
 * those directories, because a cleaner that re-derives a name is a cleaner that stops matching the
 * moment either side changes.
 */
object AgentxProjectStorage {

    /**
     * The AgentX-owned roots that can hold a copy of one project.
     *
     * A runtime that is not wired into this build contributes no root: there is nothing it could
     * have created, and listing a root anyway would risk a delete targeting a directory that
     * belongs to a different subsystem.
     */
    fun ownedRoots(
        developerRuntime: LocalUbuntuRuntime?,
        terminalRuntime: TermuxRuntime?,
    ): List<String> = listOfNotNull(
        developerRuntime?.layout?.workspaces,
        terminalRuntime?.paths?.workspaces,
    ).filter { it.isNotBlank() }

    /**
     * A [WorkspaceProjectStorage] limited to [roots].
     *
     * Both names are offered because both are in use: the handle-keyed one is what the Ubuntu
     * materialiser writes, and the id-keyed one is the legacy mirror's name. A project owns
     * whichever of them exists; a name that produces no directory is simply skipped.
     */
    fun create(roots: List<String>): WorkspaceProjectStorage = OwnedWorkspaceProjectStorage(
        ownedRoots = roots,
        naming = ProjectDirectoryNaming { record -> directoryNames(record) },
    )

    /** The directory names the runtimes use for one project, de-duplicated. */
    fun directoryNames(record: WorkspaceRecord): List<String> = listOf(
        TermuxWorkspaceBindings.mirrorSegment(record.handle),
        TermuxWorkspaceBindings.safeSegment(record.metadata.id.value),
    ).distinct()
}
