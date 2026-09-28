package com.agentx.app.integrations.oauth

import com.agentx.app.integrations.connection.ConnectionType

/**
 * Centralized OAuth callback / deep-link configuration for this personal IDE.
 *
 * Callback URIs are generated from the application's own scheme and host, never
 * hard-coded per screen. The same authority is reused for every future
 * integration: only the path segment changes.
 *
 * Example with the default application identity:
 * `agentx://oauth/github`, `agentx://oauth/supabase`, `agentx://oauth/mcp`.
 */
data class OAuthCallbackAuthority(
    val scheme: String,
    val host: String,
) {
    init {
        require(scheme.isNotBlank()) { "OAuth callback scheme must not be blank" }
        require(host.isNotBlank()) { "OAuth callback host must not be blank" }
        require(SCHEME_PATTERN.matches(scheme)) {
            "OAuth callback scheme '$scheme' is not a valid URI scheme"
        }
        require(HOST_PATTERN.matches(host)) {
            "OAuth callback host '$host' is not a valid URI host"
        }
    }

    /** Deep-link URI registered with the provider for [type]. */
    fun uriFor(type: ConnectionType): String = "$scheme://$host/${pathSegment(type)}"

    /** Path segment used under the callback host, e.g. `github`. */
    fun pathSegment(type: ConnectionType): String = PATH_BY_TYPE.getValue(type)

    /**
     * Resolves a callback URI back to the integration that owns it.
     * Query/fragment are ignored; an unknown path is not guessed.
     */
    fun typeForUri(uri: String): ConnectionType? {
        val match = URI_PATTERN.matchEntire(uri.trim().substringBefore('?').substringBefore('#'))
            ?: return null
        val schemeMatch = match.groupValues[1]
        val hostMatch = match.groupValues[2]
        val path = match.groupValues[3].trimStart('/')
        if (!schemeMatch.equals(scheme, ignoreCase = true)) return null
        if (!hostMatch.equals(host, ignoreCase = true)) return null
        return PATH_BY_TYPE.entries.firstOrNull { it.value.equals(path, ignoreCase = true) }?.key
    }

    /** True when [uri] is a well-formed callback this app would register. */
    fun isValidUri(uri: String): Boolean = typeForUri(uri) != null

    override fun toString(): String = "OAuthCallbackAuthority(scheme=$scheme, host=$host)"

    companion object {
        private val SCHEME_PATTERN = Regex("[a-z][a-z0-9+.-]*")
        private val HOST_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9.-]*")
        private val URI_PATTERN = Regex("^([a-z][a-z0-9+.-]*)://([^/?#]+)/?([^?#]*)$", RegexOption.IGNORE_CASE)

        val PATH_BY_TYPE: Map<ConnectionType, String> = mapOf(
            ConnectionType.GITHUB to "github",
            ConnectionType.SUPABASE to "supabase",
            ConnectionType.MCP_SERVER to "mcp",
            ConnectionType.CUSTOM_API to "custom",
        )

        /** Default identity derived from the application id `com.agentx.app`. */
        val DEFAULT: OAuthCallbackAuthority = OAuthCallbackAuthority(scheme = "agentx", host = "oauth")

        fun from(scheme: String, host: String): OAuthCallbackAuthority =
            OAuthCallbackAuthority(scheme = scheme.trim().lowercase(), host = host.trim().lowercase())
    }
}

/** Validates a redirect URI the owner would register with a provider. */
object OAuthRedirectUris {

    fun isValid(uri: String): Boolean {
        val trimmed = uri.trim()
        if (trimmed.isBlank()) return false
        if (trimmed.any { it.isWhitespace() }) return false
        val match = URI_PATTERN.matchEntire(trimmed.substringBefore('?').substringBefore('#')) ?: return false
        val scheme = match.groupValues[1]
        val host = match.groupValues[2]
        return scheme.isNotBlank() && host.isNotBlank()
    }

    fun problems(uri: String): List<String> {
        val trimmed = uri.trim()
        if (trimmed.isBlank()) return listOf("Callback URL is missing")
        if (trimmed.any { it.isWhitespace() }) return listOf("Callback URL must not contain spaces")
        if (!isValid(trimmed)) return listOf("Callback URL must be a valid URI")
        return emptyList()
    }

    private val URI_PATTERN = Regex("^([a-z][a-z0-9+.-]*)://([^/?#]+)/?([^?#]*)$", RegexOption.IGNORE_CASE)
}
