package com.agentx.app.integrations.github

import com.agentx.app.integrations.connection.ConnectionType
import com.agentx.app.integrations.connection.ConnectionCapability
import com.agentx.app.integrations.connection.ConnectionCapabilities
import com.agentx.app.integrations.connection.ProviderCapabilityInfo
import com.agentx.app.integrations.connection.ProviderDescriptor
import com.agentx.app.integrations.connection.ProviderToolCatalog
import com.agentx.app.integrations.connection.ProviderToolSpec
import com.agentx.app.integrations.connection.ProviderToolCategory
import com.agentx.app.integrations.connection.ConnectionAuthMethod
import com.agentx.app.integrations.oauth.GitHubOAuthProvider
import com.agentx.app.integrations.oauth.OAuthProviderDescriptor
import com.agentx.app.integrations.oauth.OAuthProvider

/**
 * A GitHub repository as AgentX needs it: just enough metadata to select,
 * authenticate clone, and open as a workspace. No access token ever lives here.
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
    /** Never carries a token. The clone URL is a plain HTTPS template. */
    override fun toString(): String =
        "GitHubRepository(id=${id.value}, owner=$owner, name=$name, visibility=${visibility.name}, " +
            "defaultBranch=$defaultBranch, cloneUrl=$cloneUrl, webUrl=$webUrl)"

    companion object {
        /** Public: owner/name read from the GitHub API response. */
        fun parseFromGitHubResponse(json: Map<String, Any?>): GitHubRepository? {
            val id = (json["id"] as? Number)?.toLong()?.toStringOrNull()
                ?: return null
            val owner = (json["owner"] as? Map<*, *>)?.get("login")?.toStringOrNull()
                ?: return null
            val name = json["name"]?.toStringOrNull()
                ?: return null
            val fullName = json["full_name"]?.toStringOrNull()
                ?: return null
            val privateFlag = (json["private"] as? Boolean) ?: false
            val defaultBranch = json["default_branch"]?.toStringOrNull()
                ?: "main"
            val cloneUrl = json["clone_url"]?.toStringOrNull() ?: return null
            val htmlUrl = json["html_url"]?.toStringOrNull() ?: return null
            return GitHubRepository(
                id = RepositoryId(id),
                owner = owner,
                name = name,
                fullName = fullName,
                visibility = if (privateFlag) GitHubRepositoryVisibility.PRIVATE else GitHubRepositoryVisibility.PUBLIC,
                defaultBranch = defaultBranch,
                cloneUrl = GitHubRepositoryCloneUrl.parse(cloneUrl),
                webUrl = htmlUrl,
            )
        }

        private fun Any?.toStringOrNull(): String? = (this as? String)?.takeIf { it.isNotBlank() }
    }
}

/** Opaque repository identity. Present so future tooling can refer to one repo. */
@JvmInline
value class RepositoryId(val value: String) {
    init {
        require(value.isNotBlank()) { "Repository id must not be blank" }
    }
    override fun toString(): String = value
}

enum class GitHubRepositoryVisibility {
    PUBLIC,
    PRIVATE,
    ;

    companion object {
        fun fromPrivateFlag(privateFlag: Boolean): GitHubRepositoryVisibility =
            if (privateFlag) PRIVATE else PUBLIC
    }
}

/**
 * A plain HTTPS clone URL. It contains no credentials.
 *
 * The GitHub access token is injected by the clone transport at clone time,
 * never embedded in this URL, never logged, and never stored in .git/config
 * as plaintext.
 */
data class GitHubRepositoryCloneUrl(val url: String) {
    init {
        require(url.startsWith("https://github.com/")) { "Clone url must be an https github.com URL: $url" }
        require(!url.contains("@")) { "Clone url must not embed credentials: $url" }
    }
    override fun toString(): String = url

    companion object {
        fun parse(raw: String): GitHubRepositoryCloneUrl {
            val trimmed = raw.trim()
            require(trimmed.startsWith("https://github.com/")) {
                "Clone url must be an https github.com URL: $trimmed"
            }
            require(!trimmed.contains("@")) { "Clone url must not embed credentials: $trimmed" }
            return GitHubRepositoryCloneUrl(trimmed)
        }
    }
}
