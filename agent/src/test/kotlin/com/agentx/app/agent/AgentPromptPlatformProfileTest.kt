package com.agentx.app.agent

import com.agentx.app.agent.catalog.AgentCatalog
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.domain.AgentStatus
import com.agentx.app.agent.domain.CollectingEventSink
import com.agentx.app.agent.prompt.PromptManager
import com.agentx.app.agent.protocol.AgentProtocol
import com.agentx.app.agent.runtime.AgentLoop
import com.agentx.app.agent.runtime.AgentLoopRequest
import com.agentx.app.agent.tools.AgentToolBridge
import com.agentx.app.context.ContextBudget
import com.agentx.app.context.DefaultContextEngine
import com.agentx.app.context.DesignContextResolver
import com.agentx.app.context.PlatformProfile
import com.agentx.app.context.PlatformProfileId
import com.agentx.app.context.PlatformProfiles
import com.agentx.app.context.PlatformProfileResolver
import com.agentx.app.context.ProjectDesign
import com.agentx.app.context.ProjectDesignContextResolver
import com.agentx.app.context.ProjectPlatformProfileResolver
import com.agentx.app.context.SkillContextProvider
import com.agentx.app.context.WorkspaceContextProvider
import com.agentx.app.context.WorkspaceSnapshot
import com.agentx.app.model.DefaultModelGateway
import com.agentx.app.model.ModelRole
import com.agentx.app.skills.DefaultSkillManager
import com.agentx.app.skills.InMemorySkillStore
import com.agentx.app.tools.DefaultToolRegistry
import com.agentx.app.tools.DefaultToolRouter
import com.agentx.app.workspace.WorkspaceFileSystem
import com.agentx.app.workspace.memory.InMemoryWorkspaceFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * How the platform profile reaches a prompt.
 *
 * The properties that matter to the rest of the prompt: the profile is chosen from
 * the project, it arrives for the roles that build or judge UI (and for none
 * others), it states its own precedence, it coexists with the universal skill and
 * the project's design direction exactly once, and a project that cannot be
 * classified leaves the prompt as it was.
 */
class AgentPromptPlatformProfileTest {

    private class ProjectWorkspace(private val files: Map<String, String>) : WorkspaceContextProvider {
        override suspend fun snapshot(): WorkspaceSnapshot =
            WorkspaceSnapshot(name = "demo", rootPath = ".", workspaceId = "ws-1")

        override suspend fun fileSystem(): WorkspaceFileSystem = InMemoryWorkspaceFileSystem(files)
    }

    private val androidPlugin = """plugins { id("com.android.application") }"""
    private val composeDependency = """dependencies { implementation("androidx.compose.ui:ui") }"""

    private val webProject = mapOf(
        "index.html" to "<html></html>",
        "styles.css" to "body {}",
    )

    private val composeProject = mapOf(
        "app/build.gradle.kts" to "$androidPlugin\n$composeDependency",
        "app/src/main/AndroidManifest.xml" to "<manifest/>",
    )

    private fun platform(files: Map<String, String>): PlatformProfileResolver =
        ProjectPlatformProfileResolver(
            workspace = ProjectWorkspace(files),
            engine = DefaultContextEngine(),
        )

    private fun design(files: Map<String, String>): DesignContextResolver =
        ProjectDesignContextResolver(
            workspace = ProjectWorkspace(files),
            engine = DefaultContextEngine(),
        )

    private suspend fun loop(
        provider: ScriptedModelProvider,
        platformProfile: PlatformProfileResolver? = null,
        designContext: DesignContextResolver? = null,
        antiSlopEnabled: Boolean = false,
    ): AgentLoop {
        val skills = DefaultSkillManager(store = InMemorySkillStore()).also { manager ->
            manager.refresh()
            if (antiSlopEnabled) manager.setEnabled("anti-slop-design", true)
        }
        return AgentLoop(
            gateway = DefaultModelGateway().also { it.register(provider) },
            toolRouter = DefaultToolRouter(DefaultToolRegistry()),
            bridge = AgentToolBridge(DefaultToolRegistry()),
            prompts = PromptManager(),
            skillContext = SkillContextProvider(skills, DefaultContextEngine()),
            designContext = designContext,
            platformProfile = platformProfile,
        )
    }

    private fun request(role: AgentRole) = AgentLoopRequest(
        sessionId = "s",
        parentSessionId = if (role == AgentRole.MAIN) null else "parent",
        definition = AgentCatalog.definition(role),
        allowedTools = listOf(AgentProtocol.FINISH_TOOL),
        permissionLevel = AgentCatalog.definition(role).effectivePermission,
        maxSteps = 2,
        userPrompt = "build the settings screen",
        objective = null,
        scopedContext = "",
        workspaceId = null,
        modelConfig = testConfig(),
    )

    private fun scripted(role: AgentRole): ScriptedModelProvider = ScriptedModelProvider(
        mapOf(
            role to mutableListOf(
                response("", toolCall(AgentProtocol.FINISH_TOOL, AgentProtocol.ARG_SUMMARY to "done")),
            ),
        ),
    )

    private suspend fun systemPromptFor(
        role: AgentRole,
        platformProfile: PlatformProfileResolver? = null,
        designContext: DesignContextResolver? = null,
        antiSlopEnabled: Boolean = false,
    ): Pair<AgentStatus, String> {
        val provider = scripted(role)
        val result = loop(provider, platformProfile, designContext, antiSlopEnabled).run(
            request = request(role),
            sink = CollectingEventSink(),
            onCancelled = { false },
        )
        val system = provider.requests.first().messages.first { it.role == ModelRole.SYSTEM }.content
        return result.status to system
    }

    private fun occurrences(haystack: String, needle: String): Int =
        haystack.windowed(needle.length).count { it == needle }

    @Test
    fun `a web project gives the coder the web profile`() = runAgent {
        val (status, system) = systemPromptFor(AgentRole.CODER, platformProfile = platform(webProject))

        assertEquals(AgentStatus.COMPLETED, status)
        assertTrue(system.contains(PlatformProfiles.WEB.heading), "missing the web heading")
        assertTrue(system.contains("keyboard"), "the web conventions are not in the prompt")
    }

    @Test
    fun `a Compose project gives the coder the Compose profile`() = runAgent {
        val (_, system) = systemPromptFor(AgentRole.CODER, platformProfile = platform(composeProject))

        assertTrue(system.contains(PlatformProfiles.ANDROID_COMPOSE.heading))
        assertFalse(system.contains(PlatformProfiles.WEB.heading))
    }

    @Test
    fun `a role that does not build UI never receives a platform profile`() = runAgent {
        val (_, explorer) = systemPromptFor(AgentRole.EXPLORER, platformProfile = platform(webProject))

        assertFalse(explorer.contains(PlatformProfile.HEADING_PREFIX), explorer)
    }

    @Test
    fun `a project whose platform cannot be determined leaves the prompt as it was`() = runAgent {
        val (status, system) = systemPromptFor(
            AgentRole.CODER,
            platformProfile = platform(mapOf("README.md" to "# Demo", "src/main.kt" to "fun main() {}")),
        )

        assertEquals(AgentStatus.COMPLETED, status)
        assertFalse(system.contains(PlatformProfile.HEADING_PREFIX), system)
    }

    @Test
    fun `a run without a platform resolver is unchanged`() = runAgent {
        val (status, system) = systemPromptFor(AgentRole.CODER)

        assertEquals(AgentStatus.COMPLETED, status)
        assertFalse(system.contains(PlatformProfile.HEADING_PREFIX), system)
    }

    @Test
    fun `anti-slop, design direction and platform profile coexist exactly once each`() = runAgent {
        val project = composeProject + mapOf(
            ProjectDesign.FILE_NAME to "Warm and editorial. Audience: independent studios.",
        )

        val (status, system) = systemPromptFor(
            AgentRole.CODER,
            platformProfile = platform(project),
            designContext = design(project),
            antiSlopEnabled = true,
        )

        assertEquals(AgentStatus.COMPLETED, status)
        assertEquals(1, occurrences(system, "# Skills"), system)
        assertEquals(1, occurrences(system, PlatformProfile.HEADING_PREFIX), system)
        assertEquals(1, occurrences(system, ProjectDesign.HEADING), system)

        // The platform profile is the most general UI layer, so it is read before the
        // project's own direction, which refines it.
        assertTrue(system.indexOf(PlatformProfile.HEADING_PREFIX) < system.indexOf(ProjectDesign.HEADING))
        assertTrue(system.indexOf("# Skills") < system.indexOf(PlatformProfile.HEADING_PREFIX))
    }

    @Test
    fun `the profile states that the project's own direction comes first`() = runAgent {
        val (_, system) = systemPromptFor(AgentRole.CODER, platformProfile = platform(webProject))

        assertTrue(
            system.contains("the project's own design direction and the rules above take precedence"),
            system,
        )
    }

    @Test
    fun `the selected profile is bounded by the request budget`() = runAgent {
        val profile = platform(webProject)

        val resolved = profile.resolve("CODER", ContextBudget.DEFAULT)

        assertEquals(PlatformProfileId.WEB, resolved.profileId)
        assertTrue(resolved.rendered.length <= ContextBudget.DEFAULT.maxPlatformChars)
    }
}
