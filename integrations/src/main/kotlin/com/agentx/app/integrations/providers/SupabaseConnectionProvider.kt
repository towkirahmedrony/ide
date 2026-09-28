package com.agentx.app.integrations.providers

import com.agentx.app.integrations.connection.Connection
import com.agentx.app.integrations.connection.ConnectionAuthMethod
import com.agentx.app.integrations.connection.ConnectionCapabilities
import com.agentx.app.integrations.connection.ConnectionCapability
import com.agentx.app.integrations.connection.ConnectionType
import com.agentx.app.integrations.connection.ProviderCapabilityInfo
import com.agentx.app.integrations.connection.ProviderDescriptor
import com.agentx.app.integrations.connection.ProviderToolCatalog
import com.agentx.app.integrations.connection.ProviderToolCategory
import com.agentx.app.integrations.connection.ProviderToolSpec
import com.agentx.app.integrations.oauth.OAuthFlowRunner
import com.agentx.app.integrations.oauth.OAuthProvider
import com.agentx.app.integrations.oauth.SupabaseOAuthProvider

/**
 * Supabase, described for the Connections UI and the Tool System.
 *
 * Supabase fixes an OAuth app's scopes when the app is registered, so the granted
 * scopes are the ones the operator configured; [capabilitiesFor] therefore maps
 * those scopes onto capabilities and never invents access. Project information is
 * only shown when the provider really returned it.
 */
class SupabaseConnectionProvider(
    oauthProvider: OAuthProvider,
    flow: OAuthFlowRunner,
    clock: () -> Long = System::currentTimeMillis,
) : OAuthBackedConnectionProvider(oauthProvider, flow, clock) {

    override val descriptor: ProviderDescriptor = ProviderDescriptor(
        type = ConnectionType.SUPABASE,
        displayName = "Supabase",
        description = "Projects, database, storage and platform management.",
        details = "Connect Supabase to let the AI agent inspect your projects and database " +
            "through the access you approve on Supabase's own authorization page.",
        capabilities = listOf(
            ProviderCapabilityInfo(
                capability = ConnectionCapabilities.PROJECT_METADATA,
                label = "Project information",
                description = "List projects and read their metadata.",
                scopes = setOfNotNull(scopeFor(ConnectionCapabilities.PROJECT_METADATA)),
            ),
            ProviderCapabilityInfo(
                capability = ConnectionCapabilities.DATABASE_READ,
                label = "Read database",
                description = "Inspect schema and run read-only queries.",
                scopes = setOfNotNull(scopeFor(ConnectionCapabilities.DATABASE_READ)),
            ),
            ProviderCapabilityInfo(
                capability = ConnectionCapabilities.DATABASE_WRITE,
                label = "Write database",
                description = "Run statements that change data.",
                scopes = setOfNotNull(scopeFor(ConnectionCapabilities.DATABASE_WRITE)),
                mutating = true,
            ),
            ProviderCapabilityInfo(
                capability = ConnectionCapabilities.STORAGE,
                label = "Storage",
                description = "Inspect storage buckets.",
                scopes = setOfNotNull(scopeFor(ConnectionCapabilities.STORAGE)),
            ),
        ),
        authMethods = listOf(ConnectionAuthMethod.OAUTH, ConnectionAuthMethod.ACCESS_TOKEN),
        authorizationNote = "Supabase scopes are configured on the OAuth app, so access is fixed when it is registered.",
    )

    override fun toolCatalog(): ProviderToolCatalog = ProviderToolCatalog(
        provider = ConnectionType.SUPABASE,
        tools = listOf(
            ProviderToolSpec(
                toolName = "supabase.list_projects",
                title = "List projects",
                description = "Lists the Supabase projects the connected account can access.",
                requiredCapability = ConnectionCapabilities.PROJECT_METADATA,
                category = ProviderToolCategory.PROJECT_METADATA,
                mutating = false,
                implemented = false,
            ),
            ProviderToolSpec(
                toolName = "supabase.get_project",
                title = "Project metadata",
                description = "Reads one project's metadata: region, status, database host.",
                requiredCapability = ConnectionCapabilities.PROJECT_METADATA,
                category = ProviderToolCategory.PROJECT_METADATA,
                mutating = false,
                implemented = false,
            ),
            ProviderToolSpec(
                toolName = "supabase.inspect_database",
                title = "Database inspection",
                description = "Runs a read-only introspection query. Declared, not enabled in this build.",
                requiredCapability = ConnectionCapabilities.DATABASE_READ,
                category = ProviderToolCategory.DATABASE_READ,
                mutating = false,
                implemented = false,
            ),
            ProviderToolSpec(
                toolName = "supabase.query_database",
                title = "SQL query",
                description = "Runs a SQL statement. Declared, not enabled in this build.",
                requiredCapability = ConnectionCapabilities.DATABASE_WRITE,
                category = ProviderToolCategory.DATABASE_WRITE,
                mutating = true,
                implemented = false,
            ),
            ProviderToolSpec(
                toolName = "supabase.list_storage_buckets",
                title = "Storage buckets",
                description = "Lists storage buckets. Declared, not enabled in this build.",
                requiredCapability = ConnectionCapabilities.STORAGE,
                category = ProviderToolCategory.STORAGE,
                mutating = false,
                implemented = false,
            ),
        ),
    )

    /** Reads the scope Supabase uses for [capability], from the provider's own map. */
    private fun scopeFor(capability: ConnectionCapability): String? =
        SupabaseOAuthProvider.SCOPE_BY_CAPABILITY[capability]
}
