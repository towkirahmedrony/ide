package com.agentx.app.tools.verification

import com.agentx.app.core.verification.VerificationCategory
import com.agentx.app.git.GitChangeType
import com.agentx.app.git.GitStatus

/**
 * A single safety violation found while preparing a commit or a push.
 *
 * It carries a structured [category] and a message, never a secret.
 */
data class SafetyViolation(
    val category: VerificationCategory,
    val message: String,
    val location: String? = null,
)

/**
 * Pure guard rails for the two repository writes the agent may perform.
 *
 * These are deliberately pure functions over already-fetched state (a diff, a git
 * status, a branch name) so they are directly testable and so the agent loop can
 * run them without a shell: the tools already hold the data.
 *
 * The guard never contains repository policy that a service already enforces —
 * for example the push service itself refuses any branch other than `main` — it
 * only adds the checks the close-out workflow needs before it writes.
 */
object CommitSafetyGuard {

    /** The only remote branch the agent may push to. */
    const val MAIN_BRANCH: String = "main"

    /**
     * Scans a staged/diff text for likely secrets and returns one violation per
     * finding. Locations name the file and line only.
     */
    fun scanForSecrets(diff: String): List<SafetyViolation> =
        SecretScan.scan(diff).map { finding ->
            SafetyViolation(
                category = VerificationCategory.SECRET_DETECTED,
                message = "A likely secret was detected and must be removed before committing",
                location = finding.safeDescription,
            )
        }

    /**
     * Reports staged paths that are not part of [intended]. This is how the agent
     * distinguishes the changes it made from pre-existing user work: anything it
     * did not intend to commit is surfaced instead of being committed silently.
     */
    fun unintendedStagedChanges(status: GitStatus, intended: Set<String>): List<SafetyViolation> {
        val staged = status.staged.map { it.path }.toSet()
        if (intended.isEmpty()) return emptyList()
        return staged.filter { it !in intended }.map { path ->
            SafetyViolation(
                category = VerificationCategory.WORKSPACE_SECURITY_FAILURE,
                message = "A change not created by this task is staged and would be committed",
                location = path,
            )
        }
    }

    /** A push is only allowed from `main`; anything else is a hard stop. */
    fun branchAllowsPush(branch: String?): SafetyViolation? {
        if (branch == MAIN_BRANCH) return null
        return SafetyViolation(
            category = VerificationCategory.WORKSPACE_SECURITY_FAILURE,
            message = "Refusing to push from '${branch ?: "(detached)"}'; the current branch must be '$MAIN_BRANCH'",
            location = branch,
        )
    }

    /**
     * Rejects paths that would write repository internals or leave the workspace,
     * used as a second line of defence before staging. Returns the offending path,
     * or null when every path is safe.
     */
    fun unsafePath(path: String): SafetyViolation? {
        val normalized = path.trim()
        val unsafe = normalized.isEmpty() ||
            normalized.startsWith("/") ||
            normalized.startsWith(":") ||
            normalized == ".git" ||
            normalized.startsWith(".git/") ||
            normalized.split('/').any { it == ".." }
        if (!unsafe) return null
        return SafetyViolation(
            category = VerificationCategory.WORKSPACE_SECURITY_FAILURE,
            message = "Refusing the protected or escaping path '$path'",
            location = path,
        )
    }

    /** True when a git change represents a deletion (never a secret source). */
    fun isDeletion(change: GitChangeType): Boolean = change == GitChangeType.DELETED
}
