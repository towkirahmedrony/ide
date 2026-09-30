package com.agentx.app.tools

/**
 * Strips secrets from tool output and log fields. Never reverse this; redacted
 * values must stay redacted.
 */
object SecretRedactor {

    private val keyNames = setOf(
        "api_key",
        "apikey",
        "api-key",
        "token",
        "access_token",
        "refresh_token",
        "password",
        "secret",
        "private_key",
        "authorization",
        "auth",
        "credential",
        "credentials",
    )

    private val envLine = Regex(
        """(?im)^(?:export\s+)?([A-Z0-9_]*?(?:KEY|TOKEN|SECRET|PASSWORD|CREDENTIAL)[A-Z0-9_]*)\s*=\s*.+$""",
    )
    /**
     * `key=value` and `key: value`. The optional quote after the name covers JSON
     * payloads — `{"password":"hunter2"}` — which is the shape tool arguments and
     * tool results actually take, so without it a secret in a JSON body survived.
     */
    private val assignment = Regex(
        """(?i)\b([A-Za-z0-9_.-]*(?:api[_-]?key|token|secret|password|credential)[A-Za-z0-9_.-]*)"?\s*[:=]\s*([^\s,;]+)""",
    )

    const val REDACTED: String = "[REDACTED]"

    fun looksSecret(name: String): Boolean {
        val normalized = name.lowercase().replace('-', '_')
        return keyNames.any { it in normalized }
    }

    fun redactText(text: String): String {
        var result = envLine.replace(text) { match -> "${match.groupValues[1]}=$REDACTED" }
        result = assignment.replace(result) { match -> "${match.groupValues[1]}=$REDACTED" }
        return result
    }

    fun redact(value: JsonValue): JsonValue = when (value) {
        is JsonValue.Null, is JsonValue.Bool, is JsonValue.Num -> value
        is JsonValue.Str -> JsonValue.Str(redactText(value.value))
        is JsonValue.Arr -> JsonValue.Arr(value.items.map { redact(it) })
        is JsonValue.Obj -> JsonValue.Obj(redactObject(value.fields))
    }

    fun redactObject(fields: JsonObject): JsonObject = fields.mapValues { (key, value) ->
        if (looksSecret(key) || key.equals(".env", ignoreCase = true)) Json.of(REDACTED) else redact(value)
    }

    fun redactOutput(output: ToolOutput): ToolOutput = output.copy(
        content = redactObject(output.content),
        displayText = output.displayText?.let(::redactText),
    )
}
