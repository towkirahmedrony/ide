package com.agentx.app.workspace.process

import com.agentx.app.workspace.ProcessEnvironment

/**
 * Builds a controlled environment for a shell or one-shot command.
 *
 * Parent environment is inherited only when [ProcessEnvironment.inheritParent]
 * is true, and even then names that look like secrets are stripped. App
 * credential stores, `.env` files, OAuth tokens and private keys are never
 * copied in.
 */
object ProcessEnvironmentBuilder {

    private val secretName = Regex(
        """(?i).*(api[_-]?key|token|secret|password|credential|private[_-]?key|authorization|oauth).*""",
    )

    private val defaultPath = listOf(
        "/system/bin",
        "/system/xbin",
        "/vendor/bin",
        "/bin",
        "/usr/bin",
    ).joinToString(":")

    fun build(
        environment: ProcessEnvironment,
        workingDirectory: String?,
        extra: Map<String, String> = emptyMap(),
    ): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        if (environment.inheritParent) {
            System.getenv().forEach { (key, value) ->
                if (!looksSecret(key)) result[key] = value
            }
        }
        if (!result.containsKey("PATH") || result["PATH"].isNullOrBlank()) {
            result["PATH"] = defaultPath
        }
        if (!result.containsKey("HOME") || result["HOME"].isNullOrBlank()) {
            result["HOME"] = workingDirectory ?: (System.getProperty("user.home") ?: "/")
        }
        if (!workingDirectory.isNullOrBlank()) {
            result["PWD"] = workingDirectory
        }
        extra.forEach { (key, value) ->
            if (!looksSecret(key)) result[key] = value
        }
        environment.variables.forEach { (key, value) ->
            if (!looksSecret(key)) result[key] = value
        }
        return result
    }

    fun looksSecret(name: String): Boolean {
        if (name.isBlank()) return true
        return secretName.matches(name)
    }
}
