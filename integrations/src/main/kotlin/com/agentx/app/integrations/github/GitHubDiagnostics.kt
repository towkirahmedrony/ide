package com.agentx.app.integrations.github

import com.agentx.app.core.logging.ForgeLogger
import com.agentx.app.core.logging.LogLevel

/**
 * Structured GitHub diagnostics for the app's existing Developer Log.
 *
 * This is a thin façade over the platform's [ForgeLogger], not a second logging
 * system. Records it emits carry the `component=github` marker that the app's
 * `DeveloperLogSink` maps onto the Developer Log's `GITHUB` category, and a
 * `GitHub/<stage>` message prefix so one connection attempt reads in order:
 * config → auth → callback → API → repositories.
 *
 * With no logger installed every call is a no-op, so tests, previews and the
 * platform-independent modules are unaffected until the app wires it up.
 *
 * Nothing sensitive is ever passed in by construction: connection/repository
 * metadata, stage names, HTTP status codes and exception types only. Tokens,
 * authorization codes, PKCE verifiers, device codes and client secrets are never
 * logged, and a client id is only ever logged through [maskClientId].
 */
object GitHubDiagnostics {

    /** Stage vocabulary shared by every call site. */
    const val STAGE_CONFIG: String = "GitHub/Config"
    const val STAGE_AUTH: String = "GitHub/Auth"
    const val STAGE_CALLBACK: String = "GitHub/Callback"
    const val STAGE_API: String = "GitHub/API"
    const val STAGE_REPOSITORIES: String = "GitHub/Repositories"

    private const val COMPONENT_FIELD = "component"
    private const val COMPONENT = "github"

    @Volatile
    private var logger: ForgeLogger? = null

    /** True once the app has installed a destination. */
    val enabled: Boolean get() = logger != null

    /**
     * Points diagnostics at the app's structured logger. Passing `null` disables
     * them again, which is what tests and headless callers rely on.
     */
    fun install(logger: ForgeLogger?) {
        this.logger = logger
    }

    fun config(message: String, fields: Map<String, Any?> = emptyMap()) =
        emit(LogLevel.INFO, STAGE_CONFIG, message, null, fields)

    fun auth(message: String, fields: Map<String, Any?> = emptyMap()) =
        emit(LogLevel.INFO, STAGE_AUTH, message, null, fields)

    fun callback(message: String, fields: Map<String, Any?> = emptyMap()) =
        emit(LogLevel.INFO, STAGE_CALLBACK, message, null, fields)

    fun api(message: String, fields: Map<String, Any?> = emptyMap()) =
        emit(LogLevel.INFO, STAGE_API, message, null, fields)

    fun repositories(message: String, fields: Map<String, Any?> = emptyMap()) =
        emit(LogLevel.INFO, STAGE_REPOSITORIES, message, null, fields)

    /** A non-fatal problem, e.g. a slow-down, a retryable status or a cancel. */
    fun warn(stage: String, message: String, fields: Map<String, Any?> = emptyMap()) =
        emit(LogLevel.WARN, stage, message, null, fields)

    /** A failure at [stage]. The exception type is recorded; its message is not trusted. */
    fun failure(
        stage: String,
        message: String,
        error: Throwable? = null,
        fields: Map<String, Any?> = emptyMap(),
    ) = emit(LogLevel.ERROR, stage, message, error, fields)

    /**
     * Masks a client id so a log line shows a recognisable shape without the value.
     * A short value is only reported as present, with its length.
     */
    fun maskClientId(clientId: String): String {
        val trimmed = clientId.trim()
        return when {
            trimmed.isEmpty() -> "(absent)"
            trimmed.length <= 8 -> "present(len=${trimmed.length})"
            else -> "${trimmed.take(4)}••••${trimmed.takeLast(4)}"
        }
    }

    private fun emit(
        level: LogLevel,
        stage: String,
        message: String,
        error: Throwable?,
        fields: Map<String, Any?>,
    ) {
        val sink = logger ?: return
        val merged = buildMap<String, Any?> {
            putAll(fields)
            put(COMPONENT_FIELD, COMPONENT)
            if (error != null) {
                put("exception", error.javaClass.name)
                put("errorMessage", error.message ?: "(no message)")
            }
        }
        val line = "$stage $message"
        when (level) {
            LogLevel.DEBUG -> sink.debug(line, merged)
            LogLevel.INFO -> sink.info(line, merged)
            LogLevel.WARN -> sink.warn(line, merged)
            LogLevel.ERROR -> sink.error(line, error, merged)
        }
    }
}
