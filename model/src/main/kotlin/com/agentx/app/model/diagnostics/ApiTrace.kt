package com.agentx.app.model.diagnostics

import com.agentx.app.core.logging.ForgeLogger
import java.util.UUID

/**
 * The provider API operation one [ApiTrace] follows.
 *
 * The label is part of every log line, so a Developer Log search can isolate one
 * kind of traffic (discovery versus a normal completion) without a separate
 * logging framework.
 */
enum class ApiOperation(val label: String) {
    DISCOVERY("DISCOVERY"),
    COMPLETION("COMPLETION"),
    STREAM("STREAM"),
    CONNECTION("CONNECTION"),
    CONNECT("CONNECT"),
}

/**
 * A correlatable, production-safe record of one provider API call.
 *
 * Every line is `[PROVIDER][OPERATION][id] STAGE key=value …`, so one Developer
 * Log search follows a single request from configuration → HTTP exchange →
 * parsing → model normalization → filtering → final outcome:
 *
 * ```
 * [GEMINI][DISCOVERY][a1b2c3] START provider=gemini …
 * [GEMINI][DISCOVERY][a1b2c3] REQUEST method=GET path=… authScheme=api-key-header
 * [GEMINI][DISCOVERY][a1b2c3] RESPONSE status=200 elapsedMs=184 bodyBytes=1024
 * [GEMINI][DISCOVERY][a1b2c3] MODEL raw=models/gemini-2.0-flash normalized=gemini-2.0-flash accepted=true
 * [GEMINI][DISCOVERY][a1b2c3] SELECT selected=gemini-2.0-flash
 * [GEMINI][DISCOVERY][a1b2c3] COMPLETE outcome=found catalog=2
 * ```
 *
 * Credentials never travel through this type: only structural values are written
 * (`status`, `bodyBytes`, `hasApiKey=YES/NO`, `authScheme`), and the free-text
 * helpers below redact anything that looks like a key before it reaches a sink.
 * This is a thin formatter over the existing [ForgeLogger], not a second logging
 * framework.
 */
class ApiTrace internal constructor(
    private val logger: ForgeLogger?,
    val provider: String,
    val operation: ApiOperation,
    val id: String,
) {

    private val prefix: String = "[$provider][${operation.label}][$id]"

    /** A milestone in the operation, at INFO so the default app level keeps it. */
    fun stage(stage: String, vararg fields: Pair<String, Any?>) {
        logger?.info("$prefix $stage", fields.toMap())
    }

    /** A recoverable problem (a fallback, a skipped retry), at WARN. */
    fun warn(stage: String, vararg fields: Pair<String, Any?>) {
        logger?.warn("$prefix $stage", fields.toMap())
    }

    /**
     * A failure, at ERROR, so an operator's Error filter still surfaces it and an
     * HTTP 401/403/404/429/5xx is immediately obvious.
     */
    fun failure(stage: String, vararg fields: Pair<String, Any?>) {
        logger?.error("$prefix $stage", null, fields.toMap())
    }

    companion object {

        /**
         * Builds a trace. Never fails: with no logger every call is a no-op, so
         * instrumentation stays a pure addition that cannot change behaviour.
         */
        fun create(
            logger: ForgeLogger?,
            provider: String,
            operation: ApiOperation,
            id: String = newId(),
        ): ApiTrace = ApiTrace(
            logger = logger,
            provider = provider.trim().uppercase().ifBlank { UNKNOWN_PROVIDER },
            operation = operation,
            id = id,
        )

        /** Short correlation id; long enough to be unique per operation. */
        fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(ID_LENGTH)

        internal const val UNKNOWN_PROVIDER: String = "API"
        private const val ID_LENGTH: Int = 6
    }
}

/** `YES`/`NO`, so a log never has to contain a credential to show it is set. */
internal fun configuredFlag(present: Boolean): String = if (present) "YES" else "NO"

/**
 * Redacts anything that looks like a provider credential and flattens whitespace.
 *
 * A response body is not trusted: a provider can echo a key back inside an error
 * message, so redaction happens here, before a value reaches any log sink.
 */
internal fun sanitizeForLog(value: String): String =
    SECRET_PATTERNS.fold(value) { text, pattern -> pattern.replace(text, REDACTED) }
        .replace(WHITESPACE, " ")
        .trim()

/**
 * A short, safe preview of a response body for a parse failure.
 *
 * Returns null when a preview would be more risk than information: an empty body,
 * a body that is not JSON (a provider's HTML error page tells us nothing about a
 * DTO mapping problem), or an oversized one. The preview is redacted and
 * quote-normalized so it stays one readable token in a `key=value` log line.
 */
internal fun safeResponsePreview(body: String, maxChars: Int = MAX_PREVIEW_CHARS): String? {
    val trimmed = body.trim()
    if (trimmed.isEmpty()) return null
    if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return null
    if (trimmed.length > MAX_PREVIEW_SOURCE_CHARS) return null
    val sanitized = sanitizeForLog(trimmed).replace('"', '\'')
    return if (sanitized.length > maxChars) sanitized.take(maxChars) + "…" else sanitized
}

/** First value of a response header, matched case-insensitively. */
internal fun Map<String, List<String>>.firstHeaderValue(name: String): String? = entries
    .firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }
    ?.value
    ?.firstOrNull()
    ?.trim()
    ?.takeIf { it.isNotEmpty() }

/** Header names a log is allowed to mention, so rate limits are diagnosable. */
internal fun Map<String, List<String>>.rateLimitHeaderNames(): List<String> = keys
    .filter { it.lowercase().startsWith("x-ratelimit") || it.equals("retry-after", ignoreCase = true) }
    .map { it.lowercase() }
    .sorted()

private const val REDACTED = "[redacted]"
private const val MAX_PREVIEW_CHARS = 160
private const val MAX_PREVIEW_SOURCE_CHARS = 2_000
private val WHITESPACE = Regex("\\s+")

/**
 * Credential shapes that must never reach a log, even inside a provider-supplied
 * message. Deliberately narrow so ordinary prose is untouched.
 */
private val SECRET_PATTERNS = listOf(
    // Google API keys.
    Regex("AIza[0-9A-Za-z_\\-]{10,}"),
    // Groq keys.
    Regex("gsk_[0-9A-Za-z]{10,}"),
    // OpenAI-style keys.
    Regex("sk-[0-9A-Za-z_\\-]{10,}"),
    // An explicit bearer credential.
    Regex("(?i)bearer\\s+[0-9A-Za-z_\\-\\.]{8,}"),
    // A private key block.
    Regex("-----BEGIN[^-]{0,40}PRIVATE KEY-----"),
)
