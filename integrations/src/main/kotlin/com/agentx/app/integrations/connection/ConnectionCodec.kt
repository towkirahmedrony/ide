package com.agentx.app.integrations.connection

/**
 * Serializes non-secret connection records. Credentials are never written here,
 * only a [Connection.credentialRef] when one exists.
 *
 * The format is a compact field map so Android SharedPreferences can persist
 * each connection without introducing a JSON library.
 */
object ConnectionCodec {

    const val FIELD_ID = "id"
    const val FIELD_DISPLAY_NAME = "displayName"
    const val FIELD_TYPE = "type"
    const val FIELD_ENDPOINT = "endpoint"
    const val FIELD_AUTH_METHOD = "authMethod"
    const val FIELD_USERNAME = "username"
    const val FIELD_METADATA = "metadata"
    const val FIELD_CAPABILITIES = "capabilities"
    const val FIELD_ENABLED = "enabled"
    const val FIELD_STATUS = "status"
    const val FIELD_STATUS_MESSAGE = "statusMessage"
    const val FIELD_LAST_TESTED = "lastTestedAtMillis"
    const val FIELD_CREDENTIAL_REF = "credentialRef"
    const val FIELD_CREATED = "createdAtMillis"
    const val FIELD_UPDATED = "updatedAtMillis"
    const val FIELD_GRANTED_SCOPES = "grantedScopes"
    const val FIELD_ACCOUNT_LABEL = "accountLabel"
    const val FIELD_CREDENTIALS_EXPIRE_AT = "credentialsExpireAtMillis"
    const val FIELD_REFRESHABLE = "refreshable"

    /** Status name saved before OAuth-first connections existed. */
    const val LEGACY_STATUS_NOT_CONFIGURED = "NOT_CONFIGURED"

    fun encode(connection: Connection): Map<String, String> {
        val fields = LinkedHashMap<String, String>()
        fields[FIELD_ID] = connection.id.value
        fields[FIELD_DISPLAY_NAME] = connection.displayName
        fields[FIELD_TYPE] = connection.type.name
        connection.config.endpoint?.let { fields[FIELD_ENDPOINT] = it }
        fields[FIELD_AUTH_METHOD] = connection.config.authMethod.name
        connection.config.username?.let { fields[FIELD_USERNAME] = it }
        if (connection.config.metadata.isNotEmpty()) {
            fields[FIELD_METADATA] = encodePairs(connection.config.metadata)
        }
        fields[FIELD_CAPABILITIES] = connection.capabilities.joinToString(",") { it.id }
        fields[FIELD_ENABLED] = connection.enabled.toString()
        fields[FIELD_STATUS] = connection.status.name
        connection.statusMessage?.let { fields[FIELD_STATUS_MESSAGE] = it }
        connection.lastTestedAtMillis?.let { fields[FIELD_LAST_TESTED] = it.toString() }
        connection.credentialRef?.let { fields[FIELD_CREDENTIAL_REF] = it }
        if (connection.grantedScopes.isNotEmpty()) {
            fields[FIELD_GRANTED_SCOPES] = connection.grantedScopes.sorted().joinToString(",")
        }
        connection.accountLabel?.let { fields[FIELD_ACCOUNT_LABEL] = it }
        connection.credentialsExpireAtMillis?.let { fields[FIELD_CREDENTIALS_EXPIRE_AT] = it.toString() }
        if (connection.refreshable) fields[FIELD_REFRESHABLE] = "true"
        fields[FIELD_CREATED] = connection.createdAtMillis.toString()
        fields[FIELD_UPDATED] = connection.updatedAtMillis.toString()
        return fields
    }

    fun decode(fields: Map<String, String>): Connection? {
        val id = fields[FIELD_ID]?.takeIf { it.isNotBlank() } ?: return null
        val displayName = fields[FIELD_DISPLAY_NAME]?.takeIf { it.isNotBlank() } ?: return null
        val type = fromName(ConnectionType.entries, fields[FIELD_TYPE]) ?: return null
        val auth = fromName(ConnectionAuthMethod.entries, fields[FIELD_AUTH_METHOD])
            ?: type.defaultAuthMethod
        val capabilities = decodeCapabilities(fields[FIELD_CAPABILITIES], type)
        val status = decodeStatus(fields[FIELD_STATUS])
        return Connection(
            id = ConnectionId(id),
            displayName = displayName,
            type = type,
            config = ConnectionConfig(
                endpoint = fields[FIELD_ENDPOINT]?.takeIf { it.isNotBlank() },
                authMethod = auth,
                username = fields[FIELD_USERNAME]?.takeIf { it.isNotBlank() },
                metadata = decodePairs(fields[FIELD_METADATA]),
            ),
            capabilities = capabilities,
            enabled = fields[FIELD_ENABLED]?.toBooleanStrictOrNull() ?: true,
            status = status,
            statusMessage = fields[FIELD_STATUS_MESSAGE]?.takeIf { it.isNotBlank() },
            lastTestedAtMillis = fields[FIELD_LAST_TESTED]?.toLongOrNull(),
            credentialRef = fields[FIELD_CREDENTIAL_REF]?.takeIf { it.isNotBlank() },
            grantedScopes = decodeScopes(fields[FIELD_GRANTED_SCOPES]),
            accountLabel = fields[FIELD_ACCOUNT_LABEL]?.takeIf { it.isNotBlank() },
            credentialsExpireAtMillis = fields[FIELD_CREDENTIALS_EXPIRE_AT]?.toLongOrNull(),
            refreshable = fields[FIELD_REFRESHABLE]?.toBooleanStrictOrNull() ?: false,
            createdAtMillis = fields[FIELD_CREATED]?.toLongOrNull() ?: 0L,
            updatedAtMillis = fields[FIELD_UPDATED]?.toLongOrNull() ?: 0L,
        )
    }

    /**
     * Reads a stored status. Records written before the OAuth-first model used
     * `NOT_CONFIGURED`, which means exactly what [ConnectionStatus.NOT_CONNECTED]
     * means now; an unreadable value never becomes "connected".
     */
    private fun decodeStatus(raw: String?): ConnectionStatus {
        if (raw == LEGACY_STATUS_NOT_CONFIGURED) return ConnectionStatus.NOT_CONNECTED
        return fromName(ConnectionStatus.entries, raw) ?: ConnectionStatus.NOT_CONNECTED
    }

    private fun decodeScopes(raw: String?): Set<String> = raw
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.toSet()
        .orEmpty()

    private fun decodeCapabilities(raw: String?, type: ConnectionType): Set<ConnectionCapability> {
        if (raw.isNullOrBlank()) return ConnectionCapabilities.defaultsFor(type)
        return raw.split(',')
            .mapNotNull { token ->
                token.trim().takeIf { it.isNotBlank() }?.let { runCatching { ConnectionCapability(it) }.getOrNull() }
            }
            .toSet()
            .ifEmpty { ConnectionCapabilities.defaultsFor(type) }
    }

    private fun encodePairs(values: Map<String, String>): String = values.entries.joinToString("\u001f") { (key, value) ->
        "${escape(key)}=${escape(value)}"
    }

    private fun decodePairs(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split('\u001f').mapNotNull { entry ->
            val index = entry.indexOf('=')
            if (index <= 0) return@mapNotNull null
            val key = unescape(entry.substring(0, index)).takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val value = unescape(entry.substring(index + 1))
            key to value
        }.toMap()
    }

    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\u001f", "\\u001f")
        .replace("=", "\\=")

    private fun unescape(value: String): String = buildString(value.length) {
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '\\' && index + 1 < value.length) {
                val next = value[index + 1]
                when {
                    next == 'u' && value.startsWith("u001f", index + 1) -> {
                        append('\u001f')
                        index += 6
                    }
                    next == '\\' || next == '=' -> {
                        append(next)
                        index += 2
                    }
                    else -> {
                        append(char)
                        index += 1
                    }
                }
            } else {
                append(char)
                index += 1
            }
        }
    }

    private fun <T : Enum<T>> fromName(values: List<T>, raw: String?): T? =
        raw?.let { name -> values.firstOrNull { it.name == name } }
}
