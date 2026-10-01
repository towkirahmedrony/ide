package com.agentx.app.ubuntu

/**
 * The environment handed to the guest shell and to guest processes.
 *
 * Deliberately a Linux environment, not an Android one. Only `TZ` is passed through from the
 * app process, because a wrong timezone is user-visible and it is not a secret. Android's
 * internal variables (`ANDROID_ROOT`, `BOOTCLASSPATH`, …) are not forwarded: inside the guest
 * they are noise at best and a path leak at worst.
 *
 * As in the legacy runtime, any name that looks like a credential is refused even when a
 * caller passes it explicitly, so an OAuth token, model API key or signing material cannot be
 * read from the terminal.
 */
object UbuntuEnvironment {

    const val TERM: String = "xterm-256color"
    const val LANG: String = "C.UTF-8"
    const val RUNTIME_MARKER: String = "ubuntu"

    /** The brief's PATH, in the brief's order. */
    const val GUEST_PATH: String =
        "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    /** Android variables that are intentionally passed through. */
    val PASSTHROUGH: Set<String> = setOf("TZ")

    private val SECRET_NAME = Regex(
        """(?i).*(api[_-]?key|access[_-]?key|private[_-]?key|secret|token|password|passwd|credential|authorization|auth[_-]?token|bearer|oauth|refresh[_-]?token|cookie|session[_-]?key|client[_-]?secret|signing[_-]?key|keystore|passphrase).*""",
    )

    fun looksSecret(name: String): Boolean {
        if (name.isBlank()) return true
        return SECRET_NAME.matches(name)
    }

    fun isValidName(name: String): Boolean =
        name.isNotEmpty() && name[0].let { it.isLetter() || it == '_' } &&
            name.all { it.isLetterOrDigit() || it == '_' }

    /**
     * Builds the `KEY=VALUE` array the pty is started with.
     *
     * @param projectGuestPath the guest path of the bound project, or null. Exposed as
     *   `AGENTX_PROJECT` so a tool or a future agent can `cd` to it without guessing.
     * @param androidEnv the host environment, filtered down to [PASSTHROUGH].
     * @param extra caller-supplied variables; identifiers only, credentials refused.
     */
    fun build(
        projectGuestPath: String? = null,
        androidEnv: Map<String, String> = emptyMap(),
        extra: Map<String, String> = emptyMap(),
    ): Array<String> {
        val environment = LinkedHashMap<String, String>()
        environment["HOME"] = ProotCommand.GUEST_HOME
        environment["USER"] = "root"
        environment["LOGNAME"] = "root"
        environment["SHELL"] = "/bin/bash"
        environment["PATH"] = GUEST_PATH
        environment["TERM"] = TERM
        environment["LANG"] = LANG
        environment["TMPDIR"] = "/tmp"
        environment["AGENTX_RUNTIME"] = RUNTIME_MARKER
        if (!projectGuestPath.isNullOrBlank()) {
            environment["AGENTX_PROJECT"] = projectGuestPath
        }

        for (name in PASSTHROUGH) {
            if (looksSecret(name)) continue
            val value = androidEnv[name] ?: continue
            if (value.isBlank()) continue
            environment[name] = value
        }

        for ((name, value) in extra) {
            if (!isValidName(name) || looksSecret(name)) continue
            environment[name] = value
        }

        return environment.map { (name, value) -> "$name=$value" }.toTypedArray()
    }

    /** A `KEY=VALUE` file for a login shell that did not inherit the pty's environment. */
    fun toEnvFile(environment: Array<String>): String = buildString {
        for (entry in environment) {
            val separator = entry.indexOf('=')
            if (separator <= 0) continue
            val name = entry.substring(0, separator)
            val value = entry.substring(separator + 1)
            append(name).append('=').append(quote(value)).append('\n')
        }
    }

    private fun quote(value: String): String =
        if (value.isEmpty() || value.any { it == ' ' || it == '"' || it == '\'' || it == '$' || it == '\\' }) {
            "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        } else {
            value
        }
}
