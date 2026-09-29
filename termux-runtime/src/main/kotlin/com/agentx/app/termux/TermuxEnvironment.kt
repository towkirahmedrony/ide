package com.agentx.app.termux

/**
 * Builds the environment for a Termux shell session.
 *
 * Follows `TermuxShellEnvironment` from termux-app: the Termux values (HOME, PREFIX, PATH,
 * TMPDIR, TERM, LANG, COLORTERM) are set explicitly, and only a fixed allow-list of Android
 * variables is forwarded from the app process. **Nothing else is inherited**, because the IDE
 * process holds OAuth tokens, model API keys and connection secrets and a shell must not be
 * able to read them.
 *
 * Pure functions, no Android API: the exact variable set is unit tested.
 */
object TermuxEnvironment {

    /** Upstream `AndroidShellEnvironment`. */
    const val TERM: String = "xterm-256color"
    const val LANG: String = "en_US.UTF-8"
    const val COLORTERM: String = "truecolor"

    /**
     * Names forwarded from the app process, and only when they are actually present.
     * These are the ones Android needs for `/system/bin/am`, the runtime and the framework
     * to behave inside a spawned process; none of them carry app or user secrets.
     */
    val PASSTHROUGH: Set<String> = setOf(
        "ANDROID_ASSETS",
        "ANDROID_DATA",
        "ANDROID_ROOT",
        "ANDROID_STORAGE",
        "EXTERNAL_STORAGE",
        "ASEC_MOUNTPOINT",
        "LOOP_MOUNTPOINT",
        "ANDROID_RUNTIME_ROOT",
        "ANDROID_ART_ROOT",
        "ANDROID_I18N_ROOT",
        "ANDROID_TZDATA_ROOT",
        "BOOTCLASSPATH",
        "DEX2OATBOOTCLASSPATH",
        "SYSTEMSERVERCLASSPATH",
        "TZ",
    )

    /**
     * Names that must never reach a shell, even if a caller passes them in [extra].
     * Deliberately broad: a false positive costs one unavailable variable, a false negative
     * leaks a credential into a process the user can print.
     */
    private val SECRET_NAME = Regex(
        """(?i).*(api[_-]?key|access[_-]?key|private[_-]?key|secret|token|password|passwd|credential|authorization|auth[_-]?token|bearer|oauth|refresh[_-]?token|cookie|session[_-]?key|client[_-]?secret|signing[_-]?key|keystore|passphrase).*""",
    )

    /** True when [name] looks like it carries a credential. */
    fun looksSecret(name: String): Boolean {
        if (name.isBlank()) return true
        return SECRET_NAME.matches(name)
    }

    /**
     * Builds the `KEY=VALUE` array handed to the PTY.
     *
     * @param paths layout of the embedded runtime; supplies HOME, PREFIX, PATH and TMPDIR.
     * @param workingDirectory initial `PWD`; falls back to [TermuxPaths.home] so the shell
     *   never starts with a path the process cannot enter.
     * @param androidEnv the app process environment, filtered against [PASSTHROUGH].
     * @param extra session-specific variables (for example `CODER_WORKSPACE`). Filtered
     *   against [looksSecret] as well.
     */
    fun build(
        paths: TermuxPaths,
        workingDirectory: String?,
        androidEnv: Map<String, String> = emptyMap(),
        extra: Map<String, String> = emptyMap(),
    ): Array<String> {
        // Ensure the home and tmp directories exist before starting the shell
        java.io.File(paths.home).mkdirs()
        java.io.File(paths.tmp).mkdirs()

        val environment = LinkedHashMap<String, String>()

        // Termux's own values first: nothing below may overwrite them.
        environment["HOME"] = paths.home
        environment["PREFIX"] = paths.prefix
        environment["PATH"] = paths.bin
        environment["TMPDIR"] = paths.tmp
        environment["TERM"] = TERM
        environment["LANG"] = LANG
        environment["COLORTERM"] = COLORTERM
        environment["SHELL"] = "${paths.bin}/bash"

        val working = workingDirectory?.takeIf { it.startsWith("/") } ?: paths.home
        environment["PWD"] = working

        for (name in PASSTHROUGH) {
            if (looksSecret(name)) continue
            val value = androidEnv[name] ?: continue
            if (value.isBlank()) continue
            environment[name] = value
        }

        for ((name, value) in extra) {
            if (name.isBlank() || looksSecret(name)) continue
            if (!isValidName(name)) continue
            environment[name] = value
        }

        return environment.map { (name, value) -> "$name=$value" }.toTypedArray()
    }

    /**
     * Renders the same environment as a `termux.env` file.
     *
     * `$PREFIX/etc/profile` sources this file in a login shell, which is what lets a child
     * process spawned without our `envp` still see `$PREFIX` and `$HOME`.
     */
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

    /** POSIX-ish variable names only; a name with `=` or a dash would corrupt the array. */
    fun isValidName(name: String): Boolean =
        name.isNotEmpty() && name[0].let { it.isLetter() || it == '_' } &&
            name.all { it.isLetterOrDigit() || it == '_' }
}
