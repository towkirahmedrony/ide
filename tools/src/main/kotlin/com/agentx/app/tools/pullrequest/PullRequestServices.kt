package com.agentx.app.tools.pullrequest

import com.agentx.app.core.ForgeResult
import com.agentx.app.core.failure
import com.agentx.app.core.pullrequest.CreatedPullRequest
import com.agentx.app.core.pullrequest.NewPullRequest
import com.agentx.app.core.pullrequest.PullRequestError
import com.agentx.app.core.pullrequest.PullRequestRef
import com.agentx.app.core.pullrequest.PullRequestService

/**
 * Resolves the repository a pull request should target. Never chosen by the model
 * when the active project can answer: the app binds one that reads the active
 * project's credential-free GitHub remote, exactly like the CI provider.
 */
fun interface PullRequestRepositoryProvider {
    suspend fun resolve(): PullRequestRef?
}

/**
 * A [PullRequestService] for builds with no GitHub connection behind them.
 *
 * Previews and the in-memory demo have no repository, so instead of inventing a
 * pull request they report that no connection is available. The real service is
 * wired in the app, exactly like the authenticated push and CI services.
 */
object UnavailablePullRequestService : PullRequestService {

    override suspend fun create(
        request: NewPullRequest,
    ): ForgeResult<CreatedPullRequest, PullRequestError> = failure(PullRequestError.NoConnection)
}

/**
 * Bindable [PullRequestService]: the tool system registers `create_pr` before the
 * app has created the real GitHub-backed service, then the app binds it once the
 * connection infrastructure exists. Until [bind] is called the tool fails closed.
 */
class DelegatingPullRequestService(
    @Volatile private var delegate: PullRequestService = UnavailablePullRequestService,
) : PullRequestService {

    fun bind(service: PullRequestService) {
        delegate = service
    }

    override suspend fun create(
        request: NewPullRequest,
    ): ForgeResult<CreatedPullRequest, PullRequestError> = delegate.create(request)
}

/**
 * Bindable [PullRequestRepositoryProvider]: the app attaches one that resolves the
 * active project's GitHub remote once the workspace runtime and the connection
 * layer exist. Until then nothing is active, so `create_pr` fails closed.
 */
class DelegatingPullRequestRepositoryProvider(
    @Volatile private var delegate: PullRequestRepositoryProvider = PullRequestRepositoryProvider { null },
) : PullRequestRepositoryProvider {

    fun bind(provider: PullRequestRepositoryProvider) {
        delegate = provider
    }

    override suspend fun resolve(): PullRequestRef? = delegate.resolve()
}
