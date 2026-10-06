package com.agentx.app.integrations.github

import com.agentx.app.core.ForgeError
import com.agentx.app.core.ForgeErrorCode
import com.agentx.app.core.ForgeResult
import com.agentx.app.core.failure
import com.agentx.app.core.success
import com.agentx.app.integrations.connection.ConnectionCredentialGateway
import com.agentx.app.integrations.connection.ConnectionId

/** The credential the fake gateway lends. Never a real token. */
internal const val TEST_TOKEN: String = "gho-test-token-value"

internal val TEST_CONNECTION_ID: ConnectionId = ConnectionId("c-github")

/**
 * A [ConnectionCredentialGateway] over nothing: no store, no refresh, no network.
 *
 * It lends [token] for the duration of the block, or refuses with [refusal] the
 * way the real manager does when a connection is missing, disabled, or expired.
 */
internal class FakeCredentialGateway(
    private val token: String? = TEST_TOKEN,
    private val refusal: ForgeError? = null,
) : ConnectionCredentialGateway {

    /** How many times a credential was asked for, so tests can assert it was not. */
    var calls: Int = 0
        private set

    override suspend fun <T> withCredential(
        connectionId: ConnectionId,
        block: suspend (String) -> T,
    ): ForgeResult<T, ForgeError> {
        calls++
        refusal?.let { return failure(it) }
        val value = token ?: return failure(
            ForgeError(
                code = ForgeErrorCode.CONNECTION_UNAUTHORIZED,
                message = "The connection has no stored credential",
            ),
        )
        return success(block(value))
    }
}

/** A [GitHubRestClient] that answers from a script and records what it was asked. */
internal class RecordingGitHubRestClient(
    private val handler: (String) -> GitHubRestResponse,
) : GitHubRestClient {

    val requests: MutableList<Request> = mutableListOf()

    data class Request(val url: String, val headers: Map<String, String>)

    override suspend fun get(url: String, headers: Map<String, String>): GitHubRestResponse {
        requests += Request(url, headers)
        return handler(url)
    }
}

/** One repository exactly as `GET /user/repos` reports it. */
internal fun repositoryJson(
    id: Long = 1L,
    owner: String = "octocat",
    name: String = "hello-world",
    privateRepository: Boolean = false,
    defaultBranch: String = "main",
    cloneUrl: String = "https://github.com/$owner/$name.git",
    webUrl: String = "https://github.com/$owner/$name",
): String = """
    {
      "id": $id,
      "name": "$name",
      "full_name": "$owner/$name",
      "private": $privateRepository,
      "default_branch": "$defaultBranch",
      "clone_url": "$cloneUrl",
      "html_url": "$webUrl",
      "owner": { "login": "$owner", "id": 9 }
    }
""".trimIndent()

/** The matching domain model, built without going through a response. */
internal fun githubRepository(
    owner: String = "octocat",
    name: String = "hello-world",
    id: String = "1",
): GitHubRepository = GitHubRepository(
    id = RepositoryId(id),
    owner = owner,
    name = name,
    fullName = "$owner/$name",
    visibility = GitHubRepositoryVisibility.PUBLIC,
    defaultBranch = GitHubRepository.DEFAULT_BRANCH,
    cloneUrl = checkNotNull(GitHubRepositoryCloneUrl.parse("https://github.com/$owner/$name.git")),
    webUrl = "https://github.com/$owner/$name",
)
