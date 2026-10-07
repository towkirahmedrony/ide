package com.agentx.app.tools

import com.agentx.app.tools.github.GitHubCatalogFailure
import com.agentx.app.tools.github.GitHubCatalogResult
import com.agentx.app.tools.github.GitHubCloneRepoTool
import com.agentx.app.tools.github.GitHubCloneResult
import com.agentx.app.tools.github.GitHubListReposTool
import com.agentx.app.tools.github.GitHubRepoSummary
import com.agentx.app.tools.github.GitHubRepositoryCatalog
import com.agentx.app.tools.github.GitHubTools
import com.agentx.app.tools.github.UnavailableGitHubRepositoryCatalog
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The GitHub tools against a fake catalog: argument handling, paging, and error mapping. */
class GitHubToolsTest {

    private class FakeCatalog(
        private val repos: List<GitHubRepoSummary> = emptyList(),
        private val listFailure: GitHubCatalogResult.Failure? = null,
        private val cloneResult: GitHubCloneResult =
            GitHubCloneResult.Success("octo/portfolio", "main", false, "octo-portfolio"),
    ) : GitHubRepositoryCatalog {
        val cloneCalls = mutableListOf<Pair<String, String?>>()

        override suspend fun listAll(): GitHubCatalogResult =
            listFailure ?: GitHubCatalogResult.Success(repos)

        override suspend fun cloneAndOpen(fullName: String, branch: String?): GitHubCloneResult {
            cloneCalls += fullName to branch
            return cloneResult
        }
    }

    private val repos = listOf(
        GitHubRepoSummary("octo/portfolio", isPrivate = false, defaultBranch = "main"),
        GitHubRepoSummary("octo/portfolio-api", isPrivate = true, defaultBranch = "develop"),
        GitHubRepoSummary("octo/notes", isPrivate = true, defaultBranch = "main"),
        GitHubRepoSummary("acme/Portfolio-tools", isPrivate = false, defaultBranch = "trunk"),
    )

    private val context = ToolExecutionContext(workspaceId = "w1")

    // --- registration ------------------------------------------------------

    @Test
    fun `the factory registers exactly the two policy-granted tool names`() {
        val names = GitHubTools.create(UnavailableGitHubRepositoryCatalog).map { it.definition.name }
        assertEquals(setOf(GitHubListReposTool.NAME, GitHubCloneRepoTool.NAME), names.toSet())
        assertEquals("github_list_repos", GitHubListReposTool.NAME)
        assertEquals("github_clone_repo", GitHubCloneRepoTool.NAME)
    }

    @Test
    fun `listing is read-only and cloning needs approval`() {
        val catalog = FakeCatalog()
        assertEquals(ToolPermissionDecision.ALLOW, GitHubListReposTool(catalog).definition.permission)
        assertEquals(ToolPermissionDecision.ASK, GitHubCloneRepoTool(catalog).definition.permission)
    }

    // --- github_list_repos -------------------------------------------------

    @Test
    fun `list filters by a case-insensitive fragment and sorts by name`() = runBlocking {
        val output = GitHubListReposTool(FakeCatalog(repos)).execute(
            ToolInput(mapOf("query" to Json.of("PORTFOLIO"))),
            context,
        )
        assertEquals(3.0, output.content["total"]?.numberOrNull())
        val text = output.displayText.orEmpty()
        assertTrue(text.indexOf("acme/Portfolio-tools") < text.indexOf("octo/portfolio · "), text)
        assertTrue("octo/notes" !in text, text)
    }

    @Test
    fun `list can be limited to private repositories`() = runBlocking {
        val output = GitHubListReposTool(FakeCatalog(repos)).execute(
            ToolInput(mapOf("visibility" to Json.of("private"))),
            context,
        )
        assertEquals(2.0, output.content["total"]?.numberOrNull())
        val text = output.displayText.orEmpty()
        assertTrue("octo/notes" in text && "octo/portfolio-api" in text, text)
    }

    @Test
    fun `list pages with limit and offset`() = runBlocking {
        val tool = GitHubListReposTool(FakeCatalog(repos))
        val first = tool.execute(ToolInput(mapOf("limit" to Json.of(2))), context)
        assertEquals(2.0, first.content["returned"]?.numberOrNull())
        assertEquals(true, first.content["hasMore"]?.booleanOrNull())
        assertEquals(2.0, first.content["nextOffset"]?.numberOrNull())

        val second = tool.execute(ToolInput(mapOf("limit" to Json.of(2), "offset" to Json.of(2))), context)
        assertEquals(2.0, second.content["returned"]?.numberOrNull())
        assertEquals(false, second.content["hasMore"]?.booleanOrNull())
    }

    @Test
    fun `list rejects an unknown visibility`() = runBlocking {
        val error = assertFailsWith<ToolExecutionError> {
            GitHubListReposTool(FakeCatalog(repos)).execute(
                ToolInput(mapOf("visibility" to Json.of("secret"))),
                context,
            )
        }
        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, error.code)
    }

    @Test
    fun `list without a connected account is a permission error`() = runBlocking {
        val error = assertFailsWith<ToolExecutionError> {
            GitHubListReposTool(UnavailableGitHubRepositoryCatalog).execute(ToolInput(), context)
        }
        assertEquals(ToolErrorCode.PERMISSION_DENIED, error.code)
    }

    @Test
    fun `list maps a transient failure to an execution error`() = runBlocking {
        val catalog = FakeCatalog(
            listFailure = GitHubCatalogResult.Failure(GitHubCatalogFailure.RATE_LIMITED, "rate limited"),
        )
        val error = assertFailsWith<ToolExecutionError> {
            GitHubListReposTool(catalog).execute(ToolInput(), context)
        }
        assertEquals(ToolErrorCode.EXECUTION_FAILED, error.code)
    }

    // --- github_clone_repo -------------------------------------------------

    @Test
    fun `clone passes the repository and branch to the catalog`() = runBlocking {
        val catalog = FakeCatalog()
        val output = GitHubCloneRepoTool(catalog).execute(
            ToolInput(mapOf("repo" to Json.of("octo/portfolio"), "branch" to Json.of("dev"))),
            context,
        )
        assertEquals(listOf("octo/portfolio" to "dev"), catalog.cloneCalls)
        assertEquals("octo/portfolio", output.content["repository"]?.stringOrNull())
        assertEquals(false, output.content["alreadyCloned"]?.booleanOrNull())
    }

    @Test
    fun `clone reports when an existing clone was reused`() = runBlocking {
        val catalog = FakeCatalog(
            cloneResult = GitHubCloneResult.Success("octo/portfolio", "main", true, "octo-portfolio"),
        )
        val output = GitHubCloneRepoTool(catalog).execute(
            ToolInput(mapOf("repo" to Json.of("octo/portfolio"))),
            context,
        )
        assertEquals(true, output.content["alreadyCloned"]?.booleanOrNull())
        assertTrue("existing clone" in output.displayText.orEmpty())
    }

    @Test
    fun `clone rejects a malformed repository before touching the catalog`() = runBlocking {
        val catalog = FakeCatalog()
        listOf("", "not a repo", "../etc/passwd", "octo/", "/portfolio", "a/b/c").forEach { bad ->
            val error = assertFailsWith<ToolExecutionError> {
                GitHubCloneRepoTool(catalog).execute(ToolInput(mapOf("repo" to Json.of(bad))), context)
            }
            assertEquals(ToolErrorCode.INVALID_ARGUMENTS, error.code, "repo '$bad'")
        }
        assertTrue(catalog.cloneCalls.isEmpty())
    }

    @Test
    fun `clone rejects a suspicious branch name`() = runBlocking {
        val catalog = FakeCatalog()
        listOf("..", "a..b", "-rf", "with space", "x;rm").forEach { bad ->
            val error = assertFailsWith<ToolExecutionError> {
                GitHubCloneRepoTool(catalog).execute(
                    ToolInput(mapOf("repo" to Json.of("octo/portfolio"), "branch" to Json.of(bad))),
                    context,
                )
            }
            assertEquals(ToolErrorCode.INVALID_ARGUMENTS, error.code, "branch '$bad'")
        }
        assertTrue(catalog.cloneCalls.isEmpty())
    }

    @Test
    fun `clone of an unknown repository is an argument error`() = runBlocking {
        val catalog = FakeCatalog(
            cloneResult = GitHubCloneResult.Failure(GitHubCatalogFailure.NOT_FOUND, "not among your repositories"),
        )
        val error = assertFailsWith<ToolExecutionError> {
            GitHubCloneRepoTool(catalog).execute(ToolInput(mapOf("repo" to Json.of("octo/missing"))), context)
        }
        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, error.code)
    }

    @Test
    fun `clone without a connected account fails closed`() = runBlocking {
        val error = assertFailsWith<ToolExecutionError> {
            GitHubCloneRepoTool(UnavailableGitHubRepositoryCatalog)
                .execute(ToolInput(mapOf("repo" to Json.of("octo/portfolio"))), context)
        }
        assertEquals(ToolErrorCode.PERMISSION_DENIED, error.code)
    }
}
