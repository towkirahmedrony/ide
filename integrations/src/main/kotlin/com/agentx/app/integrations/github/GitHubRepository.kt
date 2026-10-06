package com.agentx.app.integrations.github

/**
 * A GitHub repository as AgentX needs it: just enough metadata to select it,
 * clone it with an authenticated transport, and open the clone as a workspace.
 *
 * No access token ever lives here. [cloneUrl] is the plain HTTPS URL GitHub
 * reports; the credential is injected by the clone transport at clone time and
 * never written into `.git/config`.
 */
data class GitHubRepository(
    val id: RepositoryId,
    val owner: String,
    val name: String,
    val fullName: String,
    val visibility: GitHubRepositoryVisibility,
    val defaultBranch: String,
    val cloneUrl: GitHubRepositoryCloneUrl,
    val webUrl: String,
) {
    /** The directory a clone of this repository gets under the managed workspace root. */
    val directoryName: String get() = "$owner-$name"

    /** Deliberately excludes [cloneUrl] so a log line can never carry a URL people copy. */
    override fun toString(): String =
        "GitHubRepository(id=${id.value}, fullName=$fullName, visibility=${visibility.name}, " +
            "defaultBranch=$defaultBranch)"

    companion object {
        /** Used when GitHub reports no default branch for a repository. */
        const val DEFAULT_BRANCH: String = "main"
    }
}

/** Opaque repository identity. Stable across renames, unlike the name. */
@JvmInline
value class RepositoryId(val value: String) {
    init {
        require(value.isNotBlank()) { "Repository id must not be blank" }
    }

    override fun toString(): String = value
}

/** Whether a repository is visible to everyone or only to the account. */
enum class GitHubRepositoryVisibility(val apiParameter: String) {
    PUBLIC("public"),
    PRIVATE("private"),
    ;

    companion object {
        fun fromPrivateFlag(privateFlag: Boolean): GitHubRepositoryVisibility =
            if (privateFlag) PRIVATE else PUBLIC
    }
}

/**
 * A plain HTTPS clone URL that carries no credentials.
 *
 * Only `https://github.com/...` is accepted, and a URL with an `@` (which would
 * embed a user name, a password, or a token) is rejected outright so a
 * credential can never travel inside a clone URL.
 */
class GitHubRepositoryCloneUrl private constructor(val url: String) {

    override fun toString(): String = url

    override fun equals(other: Any?): Boolean =
        other is GitHubRepositoryCloneUrl && other.url == url

    override fun hashCode(): Int = url.hashCode()

    companion object {
        const val PREFIX: String = "https://github.com/"

        /** Returns null when [raw] is not a credential-free GitHub HTTPS clone URL. */
        fun parse(raw: String): GitHubRepositoryCloneUrl? {
            val trimmed = raw.trim()
            if (!trimmed.startsWith(PREFIX)) return null
            if (trimmed.contains('@')) return null
            return GitHubRepositoryCloneUrl(trimmed)
        }
    }
}
