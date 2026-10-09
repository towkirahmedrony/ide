package com.agentx.app.agent.policy

import com.agentx.app.agent.catalog.AgentCatalog
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.domain.PermissionLevel
import com.agentx.app.agent.domain.toToolGrants
import com.agentx.app.agent.runtime.ScopedToolRouter
import com.agentx.app.agent.tools.AgentToolBridge
import com.agentx.app.tools.DefaultToolRegistry
import com.agentx.app.tools.DefaultToolRouter
import com.agentx.app.tools.Json
import com.agentx.app.tools.ToolApproval
import com.agentx.app.tools.ToolCapability
import com.agentx.app.tools.ToolConnectionAuthorization
import com.agentx.app.tools.ToolConnectionAuthorizationError
import com.agentx.app.tools.ToolConnectionAuthorizer
import com.agentx.app.tools.ToolConnectionDenial
import com.agentx.app.tools.ToolConnectionHandle
import com.agentx.app.tools.ToolConnectionRequirement
import com.agentx.app.tools.ToolDefinition
import com.agentx.app.tools.ToolErrorCode
import com.agentx.app.tools.ToolExecutionContext
import com.agentx.app.tools.ToolInput
import com.agentx.app.tools.ToolResult
import com.agentx.app.tools.github.GitHubCatalogResult
import com.agentx.app.tools.github.GitHubCloneRepoTool
import com.agentx.app.tools.github.GitHubCloneResult
import com.agentx.app.tools.github.GitHubListReposTool
import com.agentx.app.tools.github.GitHubRepoSummary
import com.agentx.app.tools.github.GitHubRepositoryCatalog
import com.agentx.app.tools.github.GitHubTools
import com.agentx.app.tools.numberOrNull
import com.agentx.app.tools.stringOrNull
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The connected GitHub identity must reach the Main Agent's tool set.
 *
 * Root-cause regression: `github_list_repos` / `github_clone_repo` declared
 * `ToolCapability.CREDENTIALS` and `ToolCapability.NETWORK`, and
 * [PermissionLevel.allows] refuses any tool that declares CREDENTIALS while MAIN's
 * `WORKSPACE_WRITE` ceiling does not include NETWORK. The tools were therefore
 * dropped by [AgentToolBridge.filterAllowed] before the model was ever told they
 * existed. These tests drive the production classes — the real tool definitions,
 * the real policy, the real bridge and the real routers — rather than a paraphrase
 * of them.
 */
class GitHubToolAccessTest {

    private val repos = listOf(
        GitHubRepoSummary("octo/portfolio", isPrivate = false, defaultBranch = "main"),
        GitHubRepoSummary("octo/notes", isPrivate = true, defaultBranch = "main"),
    )

    private class FakeCatalog(
        private val repositories: List<GitHubRepoSummary>,
        private val clone: GitHubCloneResult =
            GitHubCloneResult.Success("octo/portfolio", "main", false, "octo-portfolio"),
    ) : GitHubRepositoryCatalog {
        var listCalls = 0
        val cloneCalls = mutableListOf<Pair<String, String?>>()

        override suspend fun listAll(): GitHubCatalogResult {
            listCalls += 1
            return GitHubCatalogResult.Success(repositories)
        }

        override suspend fun cloneAndOpen(fullName: String, branch: String?): GitHubCloneResult {
            cloneCalls += fullName to branch
            return clone
        }
    }

    private fun registry(catalog: GitHubRepositoryCatalog): DefaultToolRegistry =
        DefaultToolRegistry().apply { GitHubTools.create(catalog).forEach(::register) }

    private fun definitions(registry: DefaultToolRegistry): (String) -> ToolDefinition? =
        { name -> registry.find(name)?.definition }

    /** Grants the GITHUB connection a tool asks for; carries no credential. */
    private fun grantedConnection(): ToolConnectionAuthorizer = object : ToolConnectionAuthorizer {
        override suspend fun authorize(
            requirement: ToolConnectionRequirement,
            connectionId: String?,
        ): ToolConnectionAuthorization = ToolConnectionAuthorization.Granted(
            ToolConnectionHandle(
                connectionId = "conn-github",
                displayName = "GitHub",
                type = requirement.type,
                capabilities = setOf(requirement.capability),
            ),
        )
    }

    private fun scoped(
        registry: DefaultToolRegistry,
        role: AgentRole,
        offered: Collection<String>,
        level: PermissionLevel,
        connections: ToolConnectionAuthorizer = grantedConnection(),
    ): ScopedToolRouter = ScopedToolRouter(
        inner = DefaultToolRouter(registry = registry, connections = connections),
        role = role,
        allowedTools = offered.toSet(),
        permissionLevel = level,
        definitionOf = definitions(registry),
        toolAllowed = { name ->
            val definition = registry.find(name)?.definition
            definition == null || level.allows(definition.capabilities)
        },
    )

    private fun mainContext(): ToolExecutionContext = ToolExecutionContext(
        sessionId = "s1",
        agentId = AgentRole.MAIN.name,
        workspaceId = "w1",
        grantedPermissions = AgentCatalog.MAIN.effectivePermission.toToolGrants(),
    )

    // --- the defect --------------------------------------------------------

    @Test
    fun `the real GitHub tools fit the Main Agent permission ceiling`() {
        val registry = registry(FakeCatalog(repos))
        val mainTools = AgentToolPolicy.effectiveToolIds(AgentRole.MAIN, definitions(registry))

        listOf(GitHubListReposTool.NAME, GitHubCloneRepoTool.NAME).forEach { name ->
            assertTrue(name in mainTools, "MAIN must be offered '$name'")
            val definition = assertNotNull(registry.find(name)).definition
            assertFalse(
                ToolCapability.CREDENTIALS in definition.capabilities,
                "'$name' must not declare CREDENTIALS: the token stays in the connection layer",
            )
            assertTrue(
                PermissionLevel.WORKSPACE_WRITE.allows(definition.capabilities),
                "'$name' declares capabilities outside MAIN's WORKSPACE_WRITE ceiling: ${definition.capabilities}",
            )
        }
    }

    @Test
    fun `the Main Agent tool list and model request include the GitHub tools`() {
        val registry = registry(FakeCatalog(repos))
        val bridge = AgentToolBridge(registry)

        val offered = bridge.filterAllowed(
            AgentCatalog.MAIN.allowedTools,
            AgentCatalog.MAIN.effectivePermission,
        )
        assertTrue(GitHubListReposTool.NAME in offered, "the model must be offered github_list_repos")
        assertTrue(GitHubCloneRepoTool.NAME in offered, "the model must be offered github_clone_repo")

        val specs = bridge.toModelSpecs(offered).map { it.name }
        assertTrue(GitHubListReposTool.NAME in specs, "the model request must carry the github_list_repos schema")
        assertTrue(GitHubCloneRepoTool.NAME in specs, "the model request must carry the github_clone_repo schema")
    }

    // --- dispatch and result propagation -----------------------------------

    @Test
    fun `an authorized github_list_repos reaches the catalog and returns its result`() = runBlocking {
        val catalog = FakeCatalog(repos)
        val registry = registry(catalog)
        val router = scoped(
            registry = registry,
            role = AgentRole.MAIN,
            offered = AgentCatalog.MAIN.allowedTools,
            level = AgentCatalog.MAIN.effectivePermission,
        )

        val result = router.invoke(
            GitHubListReposTool.NAME,
            ToolInput(mapOf("visibility" to Json.of("all"))),
            mainContext(),
        )

        val success = assertIs<ToolResult.Success>(result)
        assertEquals(1, catalog.listCalls)
        assertEquals(2.0, success.output.content["total"]?.numberOrNull())
        assertTrue(
            success.output.displayText.orEmpty().contains("octo/portfolio"),
            "the structured result must reach the caller: ${success.output.displayText}",
        )
    }

    @Test
    fun `github_clone_repo pauses for approval and only runs the clone once approved`() = runBlocking {
        val catalog = FakeCatalog(repos)
        val registry = registry(catalog)
        val router = scoped(
            registry = registry,
            role = AgentRole.MAIN,
            offered = AgentCatalog.MAIN.allowedTools,
            level = AgentCatalog.MAIN.effectivePermission,
        )
        val input = ToolInput(mapOf("repo" to Json.of("octo/portfolio")))

        val parked = router.invoke(GitHubCloneRepoTool.NAME, input, mainContext())
        assertIs<ToolResult.ApprovalRequired>(parked)
        assertTrue(catalog.cloneCalls.isEmpty(), "an unapproved clone must not run")

        val approved = router.invoke(
            GitHubCloneRepoTool.NAME,
            input,
            mainContext().copy(approval = ToolApproval.granted()),
        )
        val success = assertIs<ToolResult.Success>(approved)
        val expectedCalls: List<Pair<String, String?>> = listOf("octo/portfolio" to null)
        assertEquals(expectedCalls, catalog.cloneCalls.toList())
        assertEquals("octo/portfolio", success.output.content["repository"]?.stringOrNull())
    }

    // --- the scope is not widened ------------------------------------------

    @Test
    fun `read-only roles are neither offered nor allowed to run the GitHub tools`() = runBlocking {
        val catalog = FakeCatalog(repos)
        val registry = registry(catalog)
        val explorerTools = AgentToolPolicy.effectiveToolIds(AgentRole.EXPLORER, definitions(registry))
        assertFalse(GitHubListReposTool.NAME in explorerTools)
        assertFalse(GitHubCloneRepoTool.NAME in explorerTools)

        // A caller that ignores the derived list still cannot run it: the router
        // re-decides from the policy, not from the offered set.
        val router = scoped(
            registry = registry,
            role = AgentRole.EXPLORER,
            offered = setOf(GitHubListReposTool.NAME),
            level = PermissionLevel.READ_ONLY,
        )
        val refused = router.invoke(GitHubListReposTool.NAME, ToolInput(), mainContext())
        val failure = assertIs<ToolResult.Failure>(refused)
        assertEquals(ToolErrorCode.PERMISSION_DENIED, failure.error.code)
        assertEquals(0, catalog.listCalls)
    }

    @Test
    fun `without a connected account the router refuses before the catalog is touched`() = runBlocking {
        val catalog = FakeCatalog(repos)
        val registry = registry(catalog)
        val denied: ToolConnectionAuthorizer = object : ToolConnectionAuthorizer {
            override suspend fun authorize(
                requirement: ToolConnectionRequirement,
                connectionId: String?,
            ): ToolConnectionAuthorization = ToolConnectionAuthorization.Denied(
                ToolConnectionAuthorizationError(
                    denial = ToolConnectionDenial.NOT_FOUND,
                    type = requirement.type,
                    capability = requirement.capability,
                    message = "No connection matches the requested type",
                ),
            )
        }
        val router = scoped(
            registry = registry,
            role = AgentRole.MAIN,
            offered = AgentCatalog.MAIN.allowedTools,
            level = AgentCatalog.MAIN.effectivePermission,
            connections = denied,
        )

        val refused = router.invoke(GitHubListReposTool.NAME, ToolInput(), mainContext())
        val failure = assertIs<ToolResult.Failure>(refused)
        assertEquals(ToolErrorCode.CONNECTION_UNAUTHORIZED, failure.error.code)
        assertEquals(0, catalog.listCalls)
    }
}
