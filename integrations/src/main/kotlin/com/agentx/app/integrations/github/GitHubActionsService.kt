package com.agentx.app.integrations.github

import com.agentx.app.core.ForgeError
import com.agentx.app.core.ForgeErrorCode
import com.agentx.app.core.ForgeResult
import com.agentx.app.core.failure
import com.agentx.app.core.success
import com.agentx.app.core.verification.CiConclusion
import com.agentx.app.core.verification.CiJob
import com.agentx.app.core.verification.CiLogs
import com.agentx.app.core.verification.CiRepositoryRef
import com.agentx.app.core.verification.CiRun
import com.agentx.app.core.verification.CiRunState
import com.agentx.app.core.verification.CiStep
import com.agentx.app.core.verification.CiVerificationError
import com.agentx.app.core.verification.CiVerificationService
import com.agentx.app.integrations.connection.ConnectionCredentialGateway
import com.agentx.app.integrations.connection.ConnectionId
import com.agentx.app.integrations.oauth.OAuthJson
import com.agentx.app.integrations.oauth.OAuthJsonValue
import com.agentx.app.integrations.oauth.long
import com.agentx.app.integrations.oauth.string
import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * Resolves the GitHub connection a CI read should authenticate with.
 *
 * A tiny seam, mirroring the push service's connection resolver, so the Actions
 * client never depends on the Connection Manager's whole surface: the production
 * resolver asks the manager to authorize `GITHUB` + a read capability, which
 * enforces enabled state, connection status and capability before a credential is
 * ever requested.
 */
fun interface GitHubActionsConnectionResolver {
    suspend fun resolveReadConnection(): ConnectionId?
}

/**
 * Derives the CI repository coordinates from a credential-free GitHub remote URL.
 *
 * Pure and shared so the app can resolve the active project's repository without
 * re-implementing URL parsing, and so the parsing is directly testable.
 */
object GitHubRepositoryRefs {

    /** Returns null when [raw] is not a credential-free GitHub HTTPS clone URL. */
    fun fromCloneUrl(raw: String): CiRepositoryRef? {
        val clone = GitHubRepositoryCloneUrl.parse(raw) ?: return null
        val path = clone.url
            .removePrefix(GitHubRepositoryCloneUrl.PREFIX)
            .trim('/')
            .removeSuffix(".git")
        val parts = path.split('/')
        if (parts.size != 2) return null
        val owner = parts[0].takeIf { it.isNotBlank() } ?: return null
        val name = parts[1].takeIf { it.isNotBlank() } ?: return null
        return CiRepositoryRef(owner, name)
    }
}

/**
 * Reads GitHub Actions workflow runs, jobs and logs through the existing
 * [GitHubRestClient] and [ConnectionCredentialGateway].
 *
 * This is deliberately a read-only slice of the Actions API — exactly what the
 * autonomous verification loop needs to observe a build — and never a general
 * Actions management platform. The token is lent only into the HTTP call through
 * the gateway, so it cannot reach a result, a log line, the UI or the model.
 */
class GitHubActionsServiceImpl(
    private val credentialGateway: ConnectionCredentialGateway,
    private val connections: GitHubActionsConnectionResolver,
    private val restClient: GitHubRestClient = UrlConnectionGitHubRestClient(),
    private val baseUrl: String = DEFAULT_BASE_URL,
) : CiVerificationService {

    override suspend fun latestRun(
        repository: CiRepositoryRef,
        branch: String,
        headSha: String?,
    ): ForgeResult<CiRun?, CiVerificationError> = withCredential { token ->
        val query = buildString {
            append("?branch=").append(encode(branch)).append("&per_page=").append(DEFAULT_RUNS_PER_PAGE)
            if (!headSha.isNullOrBlank()) append("&head_sha=").append(encode(headSha))
        }
        val url = "$baseUrl/repos/${repository.owner}/${repository.name}/actions/runs$query"
        when (val response = get(url, token)) {
            is ForgeResult.Failure -> response
            is ForgeResult.Success -> {
                val body = response.value.body
                if (body.isBlank()) {
                    success(null)
                } else {
                    val runs = GitHubActionsParsing.parseRunList(body)
                        ?: return@withCredential failure(
                            CiVerificationError.MalformedResponse("Expected a JSON object of workflow runs"),
                        )
                    success(runs.firstOrNull())
                }
            }
        }
    }

    override suspend fun run(
        repository: CiRepositoryRef,
        runId: Long,
    ): ForgeResult<CiRun, CiVerificationError> = withCredential { token ->
        val url = "$baseUrl/repos/${repository.owner}/${repository.name}/actions/runs/$runId"
        when (val response = get(url, token)) {
            is ForgeResult.Failure -> response
            is ForgeResult.Success -> {
                val parsed = GitHubActionsParsing.parseRun(response.value.body)
                    ?: return@withCredential failure(
                        CiVerificationError.MalformedResponse("Expected a JSON workflow run"),
                    )
                // Jobs are fetched separately; a run that reports no jobs is still usable.
                val jobs = when (
                    val jobsResponse = get(
                        "$baseUrl/repos/${repository.owner}/${repository.name}/actions/runs/$runId/jobs",
                        token,
                    )
                ) {
                    is ForgeResult.Success -> GitHubActionsParsing.parseJobs(jobsResponse.value.body).orEmpty()
                    is ForgeResult.Failure -> emptyList()
                }
                success(parsed.copy(jobs = jobs))
            }
        }
    }

    override suspend fun logs(
        repository: CiRepositoryRef,
        runId: Long,
        jobId: Long?,
        maxChars: Int,
    ): ForgeResult<CiLogs, CiVerificationError> = withCredential { token ->
        val resolvedJobId = jobId ?: resolveJobId(repository, runId, token)
            ?: return@withCredential failure(CiVerificationError.NotFound("No job is available for run $runId"))
        val url = "$baseUrl/repos/${repository.owner}/${repository.name}/actions/jobs/$resolvedJobId/logs"
        when (val response = get(url, token)) {
            is ForgeResult.Failure -> response
            is ForgeResult.Success -> {
                // The raw text is bounded here; redaction happens at the tool
                // boundary, where the shared [SecretRedactor] lives.
                val raw = response.value.body
                val bounded = raw.take(maxChars)
                success(
                    CiLogs(
                        runId = runId,
                        jobId = resolvedJobId,
                        text = bounded,
                        truncated = bounded.length < raw.length,
                    ),
                )
            }
        }
    }

    /** The first failing job of a run, or its first job, so logs have a target. */
    private suspend fun resolveJobId(
        repository: CiRepositoryRef,
        runId: Long,
        token: String,
    ): Long? {
        val url = "$baseUrl/repos/${repository.owner}/${repository.name}/actions/runs/$runId/jobs"
        val response = when (val result = get(url, token)) {
            is ForgeResult.Success -> result.value
            is ForgeResult.Failure -> return null
        }
        val jobs = GitHubActionsParsing.parseJobs(response.body).orEmpty()
        return jobs.firstOrNull { it.failed }?.id ?: jobs.firstOrNull()?.id
    }

    private suspend fun get(url: String, token: String): ForgeResult<GitHubRestResponse, CiVerificationError> {
        val response = try {
            restClient.get(
                url = url,
                headers = mapOf(
                    "Authorization" to "Bearer $token",
                    "Accept" to "application/vnd.github+json",
                    "X-GitHub-Api-Version" to GITHUB_API_VERSION,
                    "User-Agent" to USER_AGENT,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (network: GitHubRestNetworkException) {
            return failure(CiVerificationError.NetworkFailure)
        } catch (offline: IOException) {
            return failure(CiVerificationError.NetworkFailure)
        }
        return when {
            response.isSuccess -> success(response)
            response.statusCode == 401 -> failure(CiVerificationError.Unauthenticated)
            response.statusCode == 403 -> failure(
                if (response.header("x-ratelimit-remaining") == "0") {
                    CiVerificationError.RateLimited
                } else {
                    CiVerificationError.Forbidden
                },
            )
            response.statusCode == 404 -> failure(CiVerificationError.NotFound("GitHub returned 404"))
            response.statusCode == 429 -> failure(CiVerificationError.RateLimited)
            response.statusCode >= 500 -> failure(CiVerificationError.ServerError(response.statusCode))
            else -> failure(
                CiVerificationError.Unknown("GitHub answered with status ${response.statusCode}"),
            )
        }
    }

    private suspend fun <T> withCredential(
        block: suspend (String) -> ForgeResult<T, CiVerificationError>,
    ): ForgeResult<T, CiVerificationError> {
        val connectionId = connections.resolveReadConnection()
            ?: return failure(CiVerificationError.NoConnection)
        return when (val handed = credentialGateway.withCredential(connectionId, block)) {
            is ForgeResult.Success -> handed.value
            is ForgeResult.Failure -> failure(gatewayError(handed.error))
        }
    }

    private fun gatewayError(error: ForgeError): CiVerificationError = when (error.code) {
        ForgeErrorCode.CONNECTION_CREDENTIAL_EXPIRED -> CiVerificationError.Unauthenticated
        ForgeErrorCode.CONNECTION_NOT_FOUND,
        ForgeErrorCode.CONNECTION_UNAUTHORIZED,
        -> CiVerificationError.NoConnection
        else -> CiVerificationError.Unknown(error.message ?: "The GitHub credential could not be used")
    }

    private fun encode(value: String): String =
        value.replace("%", "%25").replace(" ", "%20").replace("#", "%23")

    private companion object {
        const val DEFAULT_BASE_URL: String = "https://api.github.com"
        const val GITHUB_API_VERSION: String = "2022-11-28"
        const val USER_AGENT: String = "AgentX-Android"
        const val DEFAULT_RUNS_PER_PAGE: Int = 10
    }
}

/**
 * Pure parsing of the GitHub Actions JSON the client reads.
 *
 * Kept separate from the transport so every branch — success, failure, job
 * failure, missing run, malformed body — is directly testable without a network,
 * exactly like the repository service's parsing.
 */
object GitHubActionsParsing {

    /** The `workflow_runs` array of a runs listing, or null when the body is not that shape. */
    fun parseRunList(body: String): List<CiRun>? {
        val root = OAuthJson.parse(body) ?: return null
        val runs = root.arrayField("workflow_runs") ?: return null
        return runs.mapNotNull { it.toRun() }
    }

    /** One workflow run, or null when the body is not a run object. */
    fun parseRun(body: String): CiRun? {
        val root = OAuthJson.parse(body) ?: return null
        return root.toRun()
    }

    /** The `jobs` array of a run's jobs listing, or null when the body is not that shape. */
    fun parseJobs(body: String): List<CiJob>? {
        val root = OAuthJson.parse(body) ?: return null
        val jobs = root.arrayField("jobs") ?: return null
        return jobs.mapNotNull { it.toJob() }
    }

    fun mapState(raw: String?): CiRunState = when (raw?.lowercase()) {
        "queued", "requested", "waiting", "pending" -> CiRunState.QUEUED
        "in_progress" -> CiRunState.IN_PROGRESS
        "completed" -> CiRunState.COMPLETED
        else -> CiRunState.UNKNOWN
    }

    fun mapConclusion(raw: String?): CiConclusion? = when (raw?.lowercase()) {
        null -> null
        "success" -> CiConclusion.SUCCESS
        "failure" -> CiConclusion.FAILURE
        "cancelled" -> CiConclusion.CANCELLED
        "timed_out" -> CiConclusion.TIMED_OUT
        "startup_failure" -> CiConclusion.STARTUP_FAILURE
        "action_required" -> CiConclusion.ACTION_REQUIRED
        "neutral" -> CiConclusion.NEUTRAL
        "skipped" -> CiConclusion.SKIPPED
        "stale" -> CiConclusion.STALE
        else -> CiConclusion.UNKNOWN
    }

    private fun OAuthJsonValue.toRun(): CiRun? {
        val id = long("id") ?: return null
        val headSha = string("head_sha") ?: return null
        return CiRun(
            id = id,
            name = string("name").orEmpty(),
            workflowName = string("name") ?: string("display_title") ?: "workflow",
            headSha = headSha,
            headBranch = string("head_branch"),
            event = string("event"),
            state = mapState(string("status")),
            conclusion = mapConclusion(string("conclusion")),
            htmlUrl = string("html_url"),
        )
    }

    private fun OAuthJsonValue.toJob(): CiJob? {
        val id = long("id") ?: return null
        val steps = arrayField("steps")
            ?.mapNotNull { it.toStep() }
            .orEmpty()
        return CiJob(
            id = id,
            name = string("name") ?: "job",
            state = mapState(string("status")),
            conclusion = mapConclusion(string("conclusion")),
            steps = steps,
        )
    }

    private fun OAuthJsonValue.toStep(): CiStep? {
        val name = string("name") ?: return null
        return CiStep(
            number = long("number")?.toInt() ?: 0,
            name = name,
            conclusion = mapConclusion(string("conclusion")),
        )
    }

    private fun OAuthJsonValue.arrayField(field: String): List<OAuthJsonValue>? {
        val value = (this as? OAuthJsonValue.Obj)?.fields?.get(field) ?: return null
        return (value as? OAuthJsonValue.Arr)?.items
    }
}
