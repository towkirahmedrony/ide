package com.agentx.app.agent.verification

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
import com.agentx.app.core.verification.VerificationCategory
import com.agentx.app.core.verification.VerificationStatus
import com.agentx.app.tools.verification.CiRepositoryRefProvider
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The CI runner, driven entirely by fake responses. It must poll for a run that
 * appears late, classify a failure, map transport errors to the right category,
 * and stop on cancellation — without ever contacting GitHub.
 */
class CiVerificationRunnerTest {

    private val ref = CiRepositoryRef("towkirahmedrony", "ide")

    private class FakeService(
        private val runs: MutableList<ForgeResult<CiRun?, CiVerificationError>>,
        private val detail: ForgeResult<CiRun, CiVerificationError>? = null,
        private val logs: CiLogs = CiLogs(0, null, "", false),
    ) : CiVerificationService {
        var runCalls = 0

        override suspend fun latestRun(
            repository: CiRepositoryRef,
            branch: String,
            headSha: String?,
        ): ForgeResult<CiRun?, CiVerificationError> =
            if (runs.size > 1) runs.removeFirst() else runs.first()

        override suspend fun run(repository: CiRepositoryRef, runId: Long): ForgeResult<CiRun, CiVerificationError> {
            runCalls += 1
            return detail ?: failure(CiVerificationError.NotFound("missing"))
        }

        override suspend fun logs(
            repository: CiRepositoryRef,
            runId: Long,
            jobId: Long?,
            maxChars: Int,
        ): ForgeResult<CiLogs, CiVerificationError> = success(logs)
    }

    private fun runner(
        service: CiVerificationService,
        repository: CiRepositoryRefProvider = CiRepositoryRefProvider { ref },
        onCancelled: () -> Boolean = { false },
        maxAttempts: Int = 5,
    ) = CiVerificationRunner(
        service = service,
        repository = repository,
        maxAttempts = maxAttempts,
        pollIntervalMillis = 0,
        pause = { },
        onCancelled = onCancelled,
    )

    private fun run(
        conclusion: CiConclusion,
        state: CiRunState = CiRunState.COMPLETED,
        jobs: List<CiJob> = emptyList(),
    ) = CiRun(
        id = 100,
        name = "ci",
        workflowName = "Build Android Release APK",
        headSha = "abc",
        headBranch = "main",
        event = "push",
        state = state,
        conclusion = conclusion,
        jobs = jobs,
    )

    @Test
    fun `a successful run verifies as passing`() = runBlocking {
        val service = FakeService(mutableListOf(success(run(CiConclusion.SUCCESS))))

        val outcome = runner(service).verify()

        assertEquals(VerificationStatus.PASSED, outcome.status)
    }

    @Test
    fun `a failing run verifies as failing with the CI category and logs`() = runBlocking {
        val job = CiJob(
            1,
            "build",
            CiRunState.COMPLETED,
            CiConclusion.FAILURE,
            steps = listOf(CiStep(1, "Build Release APK", CiConclusion.FAILURE)),
        )
        val finished = run(CiConclusion.FAILURE, jobs = listOf(job))
        val service = FakeService(
            runs = mutableListOf(success(finished)),
            detail = success(finished),
            logs = CiLogs(100, 1, "BUILD FAILED", false),
        )

        val outcome = runner(service).verify()

        assertEquals(VerificationStatus.FAILED, outcome.status)
        assertEquals(VerificationCategory.CI_FAILURE, outcome.failureCategory)
        assertTrue(outcome.checks.single().errorOutput.contains("BUILD FAILED"))
    }

    @Test
    fun `a run that appears on a later poll is still observed`() = runBlocking {
        val service = FakeService(
            runs = mutableListOf(
                success(null),
                success(null),
                success(run(CiConclusion.SUCCESS)),
            ),
        )

        val outcome = runner(service).verify()

        assertEquals(VerificationStatus.PASSED, outcome.status)
    }

    @Test
    fun `a run that never appears is unavailable after the attempt budget`() = runBlocking {
        val service = FakeService(mutableListOf(success(null)))

        val outcome = runner(service, maxAttempts = 3).verify()

        assertEquals(VerificationStatus.UNAVAILABLE, outcome.status)
    }

    @Test
    fun `a network failure maps to the network category`() = runBlocking {
        val service = FakeService(mutableListOf(failure(CiVerificationError.NetworkFailure)))

        val outcome = runner(service).verify()

        assertEquals(VerificationStatus.FAILED, outcome.status)
        assertEquals(VerificationCategory.NETWORK_FAILURE, outcome.failureCategory)
    }

    @Test
    fun `an expired credential maps to authentication`() = runBlocking {
        val service = FakeService(mutableListOf(failure(CiVerificationError.Unauthenticated)))

        val outcome = runner(service).verify()

        assertEquals(VerificationCategory.AUTHENTICATION_FAILURE, outcome.failureCategory)
    }

    @Test
    fun `cancellation short-circuits the poll`() = runBlocking {
        val service = FakeService(mutableListOf(success(null)))

        val outcome = runner(service, onCancelled = { true }).verify()

        assertEquals(VerificationStatus.CANCELLED, outcome.status)
    }

    @Test
    fun `a missing repository is unavailable`() = runBlocking {
        val outcome = runner(
            service = FakeService(mutableListOf(success(run(CiConclusion.SUCCESS)))),
            repository = CiRepositoryRefProvider { null },
        ).verify()

        assertEquals(VerificationStatus.UNAVAILABLE, outcome.status)
    }
}
