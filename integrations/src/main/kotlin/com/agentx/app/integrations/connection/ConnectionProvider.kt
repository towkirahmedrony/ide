package com.agentx.app.integrations.connection

import com.agentx.app.core.ForgeError
import com.agentx.app.core.ForgeErrorCode
import com.agentx.app.core.ForgeResult
import com.agentx.app.core.failure
import com.agentx.app.integrations.oauth.DeviceFlowState

/**
 * Everything the Connections UI and the Connection Manager need from a service
 * provider, with no provider-specific code anywhere else.
 *
 * A provider owns its own authorization mechanism (OAuth with PKCE, a manual
 * credential form, or nothing at all), its identity lookup, its verification
 * call, its revocation behaviour and its capability/tool mapping. The manager
 * only ever sees:
 *
 * - an [AuthorizationStart] (a URL to open, or "this provider uses a form"),
 * - an opaque credential payload that it stores in the platform secret store and
 *   hands back for verification, refresh and revoke,
 * - a [ProviderGrant] / [ProviderIdentity] describing the account.
 *
 * The credential payload is deliberately opaque: the manager never parses it and
 * never exposes it to the agent, the model, the context engine or a tool result.
 */
interface ConnectionProvider {

    val descriptor: ProviderDescriptor

    /**
     * True when this build can really start this provider's authorization. A
     * provider that needs a client id and redirect URI the app owner has not
     * configured reports false, so the UI can explain it instead of failing later.
     */
    val configured: Boolean get() = true

    /** Why authorization cannot start, when [configured] is false. */
    val configurationProblem: String? get() = null

    /** Authentication methods this provider can really carry out. */
    fun authMethods(): Set<ConnectionAuthMethod>

    /** Tools this provider can enable, and the capability each one needs. */
    fun toolCatalog(): ProviderToolCatalog

    /** Scopes/claims to request for [connection], derived from its capabilities. */
    fun scopesFor(connection: Connection): Set<String>

    /** Capabilities the granted [scopes] may produce for [connection]. */
    fun capabilitiesFor(connection: Connection, scopes: Set<String>): Set<ConnectionCapability>

    /**
     * Starts authorization. Returns a URL for the provider's own page, or
     * [AuthorizationStart.ManualFormRequired] when the provider has no hosted
     * authorization flow.
     */
    suspend fun beginAuthorization(connection: Connection): ForgeResult<AuthorizationStart, ForgeError>

    /**
     * Completes an authorization from the redirect the app received. Implementations
     * must validate `state` (single use) and the PKCE verifier, and must not return
     * a grant for a denial or an error response.
     */
    suspend fun completeAuthorization(
        connection: Connection,
        callbackUri: String,
    ): ForgeResult<ProviderGrant, ForgeError>

    /**
     * True when this provider can authorize with a device code the user approves
     * on the provider's own page, instead of a browser redirect back to the app.
     * The default is false: a provider opts in explicitly.
     */
    val supportsDeviceAuthorization: Boolean get() = false

    /**
     * Starts a device authorization. Returns only the user-facing values (user code
     * and verification URI); the device code the client later polls with stays
     * inside the provider.
     */
    suspend fun beginDeviceAuthorization(
        connection: Connection,
    ): ForgeResult<DeviceAuthorization, ForgeError> = failure(
        connectionFailure(
            code = ForgeErrorCode.CONNECTION_OAUTH_UNAVAILABLE,
            message = "${descriptor.displayName} does not support device authorization in this build.",
            details = mapOf("connectionId" to connection.id.value),
        ),
    )

    /**
     * Polls a device authorization until it is answered or fails, reporting each
     * [DeviceFlowState] transition. The provider owns the polling interval and the
     * cancellation discipline; the returned grant holds the credential payload the
     * manager stores, and is never handed to the UI.
     */
    suspend fun completeDeviceAuthorization(
        connection: Connection,
        onState: suspend (DeviceFlowState) -> Unit,
    ): ForgeResult<ProviderGrant, ForgeError> = failure(
        connectionFailure(
            code = ForgeErrorCode.CONNECTION_OAUTH_UNAVAILABLE,
            message = "${descriptor.displayName} does not support device authorization in this build.",
            details = mapOf("connectionId" to connection.id.value),
        ),
    )

    /**
     * True when this provider is holding an authorization for [connectionId] that
     * has not been answered yet. The manager uses it to route a callback to the
     * right connection; the provider still validates `state` itself.
     */
    fun hasPendingAuthorization(connectionId: ConnectionId): Boolean = false

    /** Forgets a pending authorization the user abandoned. */
    suspend fun cancelAuthorization(connectionId: ConnectionId) = Unit

    /** Confirms [payload] still works and reports the authorized account. */
    suspend fun verify(connection: Connection, payload: String): ForgeResult<ProviderIdentity, ForgeError>

    /**
     * Returns a usable payload, refreshing it when the provider supports it. A
     * provider without refresh support returns the payload unchanged only when it
     * is still valid; otherwise it fails so the connection can be marked expired.
     */
    suspend fun refresh(connection: Connection, payload: String): ForgeResult<String, ForgeError>

    /** True when [payload] is close to expiry and should be refreshed. */
    fun needsRefresh(connection: Connection, payload: String): Boolean = false

    /**
     * The usable credential for a service call.
     *
     * The stored payload is opaque to the manager; the provider turns it into the
     * value its API expects (an access token). The result is only ever passed into
     * [ConnectionCredentialGateway.withCredential]'s lambda.
     */
    fun credential(connection: Connection, payload: String): String = payload

    /** Releases the grant at the provider, when the provider supports that. */
    suspend fun revoke(connection: Connection, payload: String): ProviderRevocation

    /** Verifies a manually entered credential before it is stored. */
    suspend fun verifyManualCredential(
        connection: Connection,
        credential: String,
    ): ForgeResult<ProviderIdentity, ForgeError> = failure(
        connectionFailure(
            code = ForgeErrorCode.CONNECTION_OPERATION_FAILED,
            message = "${descriptor.displayName} cannot verify a manual credential",
            details = mapOf("connectionId" to connection.id.value),
        ),
    )
}

/** Public, non-secret description of a service: what the Connections page renders. */
data class ProviderDescriptor(
    val type: ConnectionType,
    val displayName: String,
    /** Card copy, e.g. "Code hosting, repositories, branches, pull requests and issues." */
    val description: String,
    /** Longer copy for the service details screen. */
    val details: String,
    /** Capabilities the service can expose, with the labels the UI shows. */
    val capabilities: List<ProviderCapabilityInfo>,
    val authMethods: List<ConnectionAuthMethod>,
    /** True when the user has to supply a server URL (MCP, custom APIs). */
    val requiresEndpoint: Boolean = false,
    /** Human-readable note about the authorization mechanism, when useful. */
    val authorizationNote: String? = null,
) {
    fun capabilityInfo(capability: ConnectionCapability): ProviderCapabilityInfo? =
        capabilities.firstOrNull { it.capability == capability }

    /** True when the provider offers a hosted authorization page. */
    val supportsHostedAuthorization: Boolean get() = ConnectionAuthMethod.OAUTH in authMethods
}

/** One capability a provider can grant, described for humans. */
data class ProviderCapabilityInfo(
    val capability: ConnectionCapability,
    /** e.g. "Read repositories". */
    val label: String,
    val description: String = "",
    /** Provider scope(s) that must be granted for this capability. */
    val scopes: Set<String> = emptySet(),
    /** True when using this capability changes data at the provider. */
    val mutating: Boolean = false,
)

/** The tools a provider can install once its connection is available. */
data class ProviderToolCatalog(
    val provider: ConnectionType,
    val tools: List<ProviderToolSpec>,
) {
    fun tool(name: String): ProviderToolSpec? = tools.firstOrNull { it.toolName == name }

    /** Tools whose required capability is present in [capabilities]. */
    fun toolsFor(capabilities: Set<ConnectionCapability>): List<ProviderToolSpec> =
        tools.filter { it.requiredCapability in capabilities }

    companion object {
        val EMPTY_TOOLS: ProviderToolCatalog = ProviderToolCatalog(ConnectionType.CUSTOM_API, emptyList())
    }
}

/**
 * One tool a provider contributes to the Tool System.
 *
 * [implemented] is honest metadata: a catalog entry with `implemented = false` is
 * registered as an unavailable tool (it refuses to run) instead of pretending the
 * capability exists. [mutating] never becomes a permission by itself — the Tool
 * System's own permission policy decides whether a mutating tool may run.
 */
data class ProviderToolSpec(
    val toolName: String,
    val title: String,
    val description: String,
    val requiredCapability: ConnectionCapability,
    val category: ProviderToolCategory,
    val mutating: Boolean,
    val implemented: Boolean,
)

/** Tool categories a provider tool can belong to, kept free of the tools module. */
enum class ProviderToolCategory {
    REPOSITORY_READ,
    REPOSITORY_WRITE,
    PULL_REQUEST,
    ISSUES,
    PROJECT_METADATA,
    DATABASE_READ,
    DATABASE_WRITE,
    STORAGE,
    MCP,
    OTHER,
}

/** The first step of an authorization, as the UI needs to see it. */
sealed interface AuthorizationStart {

    /** Open [authorizationUrl] in an external browser. */
    data class OpenUrl(
        val connectionId: ConnectionId,
        val type: ConnectionType,
        val displayName: String,
        val authorizationUrl: String,
        val scopes: Set<String>,
        val expiresAtMillis: Long,
    ) : AuthorizationStart {
        override fun toString(): String =
            "AuthorizationStart.OpenUrl(connectionId=$connectionId, type=${type.name}, scopes=${scopes.sorted()})"
    }

    /**
     * The provider has no hosted authorization page: the user supplies a
     * credential through the app's own form, which is then verified.
     */
    data class ManualFormRequired(
        val connectionId: ConnectionId,
        val type: ConnectionType,
        val displayName: String,
        val reason: String,
        val credentialLabel: String,
    ) : AuthorizationStart
}

/**
 * What the UI needs to let the user authorize through a device code: the code to
 * type and where to type it. It never carries the device code or a token.
 */
data class DeviceAuthorization(
    val connectionId: ConnectionId,
    val type: ConnectionType,
    val displayName: String,
    /** Short code the user enters on the provider's page, e.g. `ABCD-1234`. */
    val userCode: String,
    /** Provider page the user opens to enter the code. */
    val verificationUri: String,
    val expiresAtMillis: Long,
    val intervalSeconds: Long,
) {
    /** Never prints the user code; it is user-facing, but not for a log. */
    override fun toString(): String =
        "DeviceAuthorization(connectionId=$connectionId, type=${type.name}, userCode=present, " +
            "verificationUri=$verificationUri)"
}

/** What a completed authorization produced, before it is persisted. */
data class ProviderGrant(
    val connectionId: ConnectionId,
    /** Opaque credential payload for the secret store. Never logged or displayed. */
    val payload: String,
    val scopes: Set<String>,
    val identity: ProviderIdentity,
    /**
     * Capabilities the grant really covers: the intersection of what the granted
     * scopes allow and what the connection asked for.
     */
    val capabilities: Set<ConnectionCapability> = emptySet(),
    val expiresAtMillis: Long? = null,
    val refreshable: Boolean = false,
) {
    override fun toString(): String =
        "ProviderGrant(connectionId=$connectionId, scopes=${scopes.sorted()}, identity=${identity.accountLabel})"
}

/** The authenticated account behind a connection. Never contains a token. */
data class ProviderIdentity(
    /** e.g. "@octocat" or an organization name. Shown in the UI. */
    val accountLabel: String,
    /** Optional secondary detail, e.g. "3 organizations". */
    val detail: String? = null,
    val message: String = "",
    /** When the provider told us the credential expires. */
    val expiresAtMillis: Long? = null,
) {
    override fun toString(): String = "ProviderIdentity(accountLabel=$accountLabel, detail=$detail)"
}

/** Outcome of asking a provider to release a grant. */
data class ProviderRevocation(
    val supported: Boolean,
    val revoked: Boolean,
    val message: String = "",
) {
    override fun toString(): String = "ProviderRevocation(supported=$supported, revoked=$revoked)"
}

/**
 * One provider tool and whether the Tool System should have it installed right now.
 *
 * Tool availability is deliberately separate from tool permission: [enabled] only
 * says the connection exists and grants the capability, while [mutating] and the
 * tool's own required permission levels still decide whether a call may run.
 */
data class InstalledTool(
    val provider: ConnectionType,
    val toolName: String,
    val title: String,
    val description: String,
    val requiredCapability: ConnectionCapability,
    val mutating: Boolean,
    /** False for catalog entries whose implementation is not written yet. */
    val implemented: Boolean,
    /** True when the connection is connected, declares the capability and the tool exists. */
    val enabled: Boolean,
    /** Why the tool is not enabled, when it is not. */
    val reason: String? = null,
    /** The connection the tool would use, when there is one. */
    val connectionId: ConnectionId? = null,
)

/** Availability of a provider for the UI, including why it is unavailable. */
data class ProviderAvailability(
    val type: ConnectionType,
    val displayName: String,
    val description: String,
    val registered: Boolean,
    /** True when the build is configured for the provider's hosted authorization. */
    val configured: Boolean,
    val authMethods: List<ConnectionAuthMethod>,
    val unavailableReason: String? = null,
) {
    val supportsHostedAuthorization: Boolean
        get() = registered && configured && ConnectionAuthMethod.OAUTH in authMethods
}
