package com.agentx.app.tools

/**
 * The kind of external service a future tool may request. Mirrors the
 * Connection Manager's type tags without pulling the integrations module into
 * the tool system.
 */
enum class ToolConnectionType {
    GITHUB,
    SUPABASE,
    MCP_SERVER,
    CUSTOM_API,
}

/**
 * A named capability on a connection. Tools declare these; the Connection
 * Manager verifies them when a tool asks for access.
 */
@JvmInline
value class ToolConnectionCapability(val id: String) {
    init {
        require(id.isNotBlank()) { "Connection capability id must not be blank" }
    }

    override fun toString(): String = id

    companion object {
        val REPOSITORY_READ = ToolConnectionCapability("repository_read")
        val REPOSITORY_WRITE = ToolConnectionCapability("repository_write")
        val PULL_REQUEST = ToolConnectionCapability("pull_request")
        val ISSUES = ToolConnectionCapability("issues")

        val DATABASE_READ = ToolConnectionCapability("database_read")
        val DATABASE_WRITE = ToolConnectionCapability("database_write")
        val STORAGE = ToolConnectionCapability("storage")
        val PROJECT_METADATA = ToolConnectionCapability("project_metadata")

        val TOOLS = ToolConnectionCapability("tools")
        val RESOURCES = ToolConnectionCapability("resources")
        val PROMPTS = ToolConnectionCapability("prompts")

        val HTTP_REQUEST = ToolConnectionCapability("http_request")
    }
}

/**
 * Declares that a tool needs a specific external connection. Example:
 *
 * GitHubTool → requires GITHUB + repository_read
 * SupabaseTool → requires SUPABASE + database_read
 *
 * The Agent never sees credentials; it only sees this requirement and, after
 * authorization, an opaque [ToolConnectionHandle].
 */
data class ToolConnectionRequirement(
    val type: ToolConnectionType,
    val capability: ToolConnectionCapability,
    val optional: Boolean = false,
)

/**
 * Opaque handle handed to a tool after the Connection Manager authorizes the
 * request. It identifies the connection and its capabilities; it never carries
 * a secret.
 */
data class ToolConnectionHandle(
    val connectionId: String,
    val displayName: String,
    val type: ToolConnectionType,
    val capabilities: Set<ToolConnectionCapability>,
    val endpoint: String? = null,
    /** Reference into the secret store. The secret itself is never present. */
    val credentialRef: String? = null,
) {
    override fun toString(): String =
        "ToolConnectionHandle(id=$connectionId, type=${type.name}, capabilities=${capabilities.map { it.id }})"
}

/** Why a tool was refused a connection. Safe for tool results and the agent. */
enum class ToolConnectionDenial {
    NOT_FOUND,
    DISABLED,
    MISSING_CAPABILITY,
    MISSING_CREDENTIAL,
    NOT_AUTHORIZED,
}

data class ToolConnectionAuthorizationError(
    val denial: ToolConnectionDenial,
    val type: ToolConnectionType,
    val capability: ToolConnectionCapability,
    val connectionId: String? = null,
    val message: String,
) {
    override fun toString(): String =
        "ToolConnectionAuthorizationError(denial=${denial.name}, type=${type.name}, capability=${capability.id})"
}

/**
 * Port the Connection Manager implements so tools can request a connection
 * without depending on the integrations module.
 */
fun interface ToolConnectionAuthorizer {
    suspend fun authorize(
        requirement: ToolConnectionRequirement,
        connectionId: String?,
    ): ToolConnectionAuthorization
}

sealed interface ToolConnectionAuthorization {
    data class Granted(val handle: ToolConnectionHandle) : ToolConnectionAuthorization

    data class Denied(val error: ToolConnectionAuthorizationError) : ToolConnectionAuthorization
}

/**
 * Authorizer used when no Connection Manager is bound. Every request is denied
 * as missing, so future tools fail closed instead of inventing access.
 */
object MissingToolConnectionAuthorizer : ToolConnectionAuthorizer {
    override suspend fun authorize(
        requirement: ToolConnectionRequirement,
        connectionId: String?,
    ): ToolConnectionAuthorization = ToolConnectionAuthorization.Denied(
        ToolConnectionAuthorizationError(
            denial = ToolConnectionDenial.NOT_FOUND,
            type = requirement.type,
            capability = requirement.capability,
            connectionId = connectionId,
            message = "No connection matches the requested type",
        ),
    )
}

/** Bindable resolver, same idea as [DelegatingWorkspaceFileSystemResolver]. */
class DelegatingToolConnectionAuthorizer(
    @Volatile private var delegate: ToolConnectionAuthorizer = MissingToolConnectionAuthorizer,
) : ToolConnectionAuthorizer {

    fun bind(authorizer: ToolConnectionAuthorizer) {
        delegate = authorizer
    }

    override suspend fun authorize(
        requirement: ToolConnectionRequirement,
        connectionId: String?,
    ): ToolConnectionAuthorization = delegate.authorize(requirement, connectionId)
}
