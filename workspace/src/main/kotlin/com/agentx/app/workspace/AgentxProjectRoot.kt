package com.agentx.app.workspace

import java.io.File

/**
 * Where AgentX keeps the projects it owns, and what that folder is called.
 *
 * Every project AgentX creates for the user — a project made by hand and a repository AgentX
 * cloned — is a direct child of one AgentX folder in the user's shared storage:
 *
 * ```
 * <shared storage>/AgentX/<project name>/
 * ```
 *
 * The folder *name* has exactly one source of truth: this object. The *base* deliberately does not,
 * because which directory "shared storage" resolves to is an Android question
 * (`Environment.getExternalStorageDirectory()`) and this module has no Android dependency. The
 * composition root resolves the base and injects the resulting [File] into the existing storage
 * abstractions — [ManagedProjectDirectory] creates projects in it — so the absolute prefix is never
 * written into code.
 *
 * Putting the layout here rather than at each call site is what makes "a project lives in the AgentX
 * folder" one statement instead of a convention every creation path has to remember separately, and
 * it is why the create flow and the clone flow cannot drift apart.
 */
object AgentxProjectRoot {

    /** The user-visible folder every AgentX-owned project is a direct child of. */
    const val DIRECTORY_NAME: String = "AgentX"

    /**
     * The AgentX project root inside [base] — the folder [DIRECTORY_NAME] names.
     *
     * Nothing is created here. [ManagedProjectDirectory] creates the root on first use, because
     * project creation is the operation that needs it to exist, and it is the abstraction that owns
     * that decision.
     */
    fun under(base: File): File = File(base, DIRECTORY_NAME)
}
