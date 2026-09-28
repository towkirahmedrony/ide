package com.agentx.app.tools

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ToolConnectionAuthorizationTest {

    private fun githubTool(): Tool = object : Tool {
        override val definition = ToolDefinition(
            name = "github_read",
            description = "Reserved GitHub reader. Uses a connection, never a secret.",
            connectionRequirement = ToolConnectionRequirement(
                type = ToolConnectionType.GITHUB,
                capability = ToolConnectionCapability.REPOSITORY_READ,
            ),
        )

        override suspend fun execute(input: ToolInput, context: ToolExecutionContext): ToolOutput =
            ToolOutput(displayText = "would read")
    }

    private fun routerWith(
        authorizer: ToolConnectionAuthorizer,
        vararg tools: Tool,
    ): ToolRouter {
        val registry = DefaultToolRegistry()
        tools.forEach(registry::register)
        return DefaultToolRouter(registry = registry, connections = authorizer)
    }

    @Test
    fun `a tool requesting a missing connection is refused`() = runBlocking {
        val result = routerWith(MissingToolConnectionAuthorizer, githubTool())
            .invoke("github_read")
        val failure = assertIs<ToolResult.Failure>(result)
        assertEquals(ToolErrorCode.CONNECTION_UNAUTHORIZED, failure.error.code)
        assertEquals("No connection matches the requested type", failure.error.message)
    }

    @Test
    fun `a disabled connection is refused`() = runBlocking {
        val authorizer = ToolConnectionAuthorizer { requirement, _ ->
            ToolConnectionAuthorization.Denied(
                ToolConnectionAuthorizationError(
                    denial = ToolConnectionDenial.DISABLED,
                    type = requirement.type,
                    capability = requirement.capability,
                    connectionId = "conn-1",
                    message = "The matching connection is disabled",
                ),
            )
        }
        val result = routerWith(authorizer, githubTool()).invoke("github_read")
        val failure = assertIs<ToolResult.Failure>(result)
        assertEquals(ToolErrorCode.CONNECTION_UNAUTHORIZED, failure.error.code)
        assertEquals("The matching connection is disabled", failure.error.message)
    }

    @Test
    fun `an unauthorized capability is refused`() = runBlocking {
        val authorizer = ToolConnectionAuthorizer { requirement, _ ->
            ToolConnectionAuthorization.Denied(
                ToolConnectionAuthorizationError(
                    denial = ToolConnectionDenial.MISSING_CAPABILITY,
                    type = requirement.type,
                    capability = requirement.capability,
                    connectionId = "conn-1",
                    message = "The connection does not declare the required capability",
                ),
            )
        }
        val result = routerWith(authorizer, githubTool()).invoke("github_read")
        val failure = assertIs<ToolResult.Failure>(result)
        assertEquals(ToolErrorCode.CONNECTION_UNAUTHORIZED, failure.error.code)
    }

    @Test
    fun `an authorized handle never includes a secret`() = runBlocking {
        val handle = ToolConnectionHandle(
            connectionId = "conn-1",
            displayName = "Work GitHub",
            type = ToolConnectionType.GITHUB,
            capabilities = setOf(ToolConnectionCapability.REPOSITORY_READ),
            credentialRef = "connections.secret.conn-1",
        )
        assertTrue("ghp-" !in handle.toString())
        assertTrue("secret-value" !in handle.toString())

        val authorizer = ToolConnectionAuthorizer { _, _ -> ToolConnectionAuthorization.Granted(handle) }
        val result = routerWith(authorizer, githubTool()).invoke("github_read")
        assertIs<ToolResult.Success>(result)
    }
}
