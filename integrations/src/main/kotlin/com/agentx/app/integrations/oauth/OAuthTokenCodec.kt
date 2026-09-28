package com.agentx.app.integrations.oauth

/**
 * Serializes an [OAuthTokenSet] so it can live as a single opaque value inside
 * [com.agentx.app.integrations.connection.ConnectionSecretStore].
 *
 * The encoded payload is itself a secret: it is written only to the platform's
 * secure storage and never to a connection record, a log, a tool result or the
 * agent's context. Decoding never throws, so a corrupt payload degrades to "no
 * stored grant" instead of a crash, and no partial value is ever returned.
 */
object OAuthTokenCodec {

    private const val VERSION = "v1"
    private const val ENTRY_SEPARATOR = '\u001f'
    private const val FIELD_SEPARATOR = '\u001e'
    private const val PREFIX_SCOPES = "s"

    fun encode(tokens: OAuthTokenSet): String {
        val fields = listOf(
            tokens.accessToken,
            tokens.refreshToken.orEmpty(),
            tokens.tokenType,
            tokens.obtainedAtMillis.toString(),
            tokens.expiresAtMillis?.toString().orEmpty(),
            tokens.refreshExpiresAtMillis?.toString().orEmpty(),
            tokens.accountLabel.orEmpty(),
        )
        return buildString {
            append(VERSION)
            append(ENTRY_SEPARATOR)
            append(fields.joinToString(FIELD_SEPARATOR.toString()))
            append(ENTRY_SEPARATOR)
            append(PREFIX_SCOPES + tokens.scopes.sorted().joinToString(","))
        }
    }

    /** Returns null when [payload] is absent, malformed, or not an OAuth grant. */
    fun decode(payload: String?): OAuthTokenSet? {
        if (payload.isNullOrBlank()) return null
        if (!payload.startsWith("$VERSION$ENTRY_SEPARATOR")) return null

        val parts = payload.split(ENTRY_SEPARATOR)
        if (parts.size < 3) return null

        val fields = parts[1].split(FIELD_SEPARATOR)
        if (fields.size < 7) return null
        val accessToken = fields[0]
        if (accessToken.isBlank()) return null

        val scopes = parts[2].removePrefix(PREFIX_SCOPES)
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

        return OAuthTokenSet(
            accessToken = accessToken,
            refreshToken = fields[1].takeIf { it.isNotBlank() },
            tokenType = fields[2].takeIf { it.isNotBlank() } ?: "bearer",
            obtainedAtMillis = fields[3].toLongOrNull() ?: 0L,
            expiresAtMillis = fields[4].toLongOrNull(),
            refreshExpiresAtMillis = fields[5].toLongOrNull(),
            accountLabel = fields[6].takeIf { it.isNotBlank() },
            scopes = scopes,
        )
    }

    /** True when [payload] looks like an encoded OAuth grant. */
    fun isOAuthGrant(payload: String?): Boolean = decode(payload) != null
}
