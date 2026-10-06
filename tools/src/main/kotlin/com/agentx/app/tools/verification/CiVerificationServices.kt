package com.agentx.app.tools.verification

import com.agentx.app.core.ForgeResult
import com.agentx.app.core.failure
import com.agentx.app.core.verification.CiLogs
import com.agentx.app.core.verification.CiRepositoryRef
import com.agentx.app.core.verification.CiRun
import com.agentx.app.core.verification.CiVerificationError
import com.agentx.app.core.verification.CiVerificationService

/**
 * A [CiVerificationService] for builds with no GitHub connection behind them.
 *
 * Previews and the in-memory demo have no repository, so instead of inventing a
 * run they report that no connection is available. The real service is wired in
 * the app, exactly like the GitHub-backed push service.
 */
object UnavailableCiVerificationService : CiVerificationService {

    override suspend fun latestRun(
        repository: CiRepositoryRef,
        branch: String,
        headSha: String?,
    ): ForgeResult<CiRun?, CiVerificationError> = failure(CiVerificationError.NoConnection)

    override suspend fun run(
        repository: CiRepositoryRef,
        runId: Long,
    ): ForgeResult<CiRun, CiVerificationError> = failure(CiVerificationError.NoConnection)

    override suspend fun logs(
        repository: CiRepositoryRef,
        runId: Long,
        jobId: Long?,
        maxChars: Int,
    ): ForgeResult<CiLogs, CiVerificationError> = failure(CiVerificationError.NoConnection)
}

/**
 * Bindable [CiVerificationService], the same idea as the delegating Git services:
 * the tool system registers `ci_verification` before the app has created the real
 * GitHub Actions-backed service, then the app binds it once the connection
 * infrastructure exists. Until [bind] is called the tool fails closed.
 */
class DelegatingCiVerificationService(
    @Volatile private var delegate: CiVerificationService = UnavailableCiVerificationService,
) : CiVerificationService {

    fun bind(service: CiVerificationService) {
        delegate = service
    }

    override suspend fun latestRun(
        repository: CiRepositoryRef,
        branch: String,
        headSha: String?,
    ): ForgeResult<CiRun?, CiVerificationError> = delegate.latestRun(repository, branch, headSha)

    override suspend fun run(
        repository: CiRepositoryRef,
        runId: Long,
    ): ForgeResult<CiRun, CiVerificationError> = delegate.run(repository, runId)

    override suspend fun logs(
        repository: CiRepositoryRef,
        runId: Long,
        jobId: Long?,
        maxChars: Int,
    ): ForgeResult<CiLogs, CiVerificationError> = delegate.logs(repository, runId, jobId, maxChars)
}

/**
 * Bindable [CiRepositoryRefProvider]: the app attaches one that resolves the
 * active project's GitHub remote once the workspace runtime and the connection
 * layer exist. Until then nothing is active, so `ci_verification` fails closed.
 */
class DelegatingCiRepositoryRefProvider(
    @Volatile private var delegate: CiRepositoryRefProvider = CiRepositoryRefProvider { null },
) : CiRepositoryRefProvider {

    fun bind(provider: CiRepositoryRefProvider) {
        delegate = provider
    }

    override suspend fun resolve(): CiRepositoryRef? = delegate.resolve()
}
