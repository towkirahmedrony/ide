package com.agentx.app.termux

object TermuxEnvironment {

    const val TERM: String = "xterm-256color"
    const val LANG: String = "en_US.UTF-8"
    const val COLORTERM: String = "truecolor"

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

    private val SECRET_NAME = Regex(
        """(?i).*(api[_-]?key|access[_-]?key|private[_-]?key|secret|token|password|passwd|credential|authorization|auth[_-]?token|bearer|oauth|refresh[_-]?token|cookie|session[_-]?key|client[_-]?secret|signing[_-]?key|keystore|passphrase).*""",
    )

    fun looksSecret(name: String): Boolean {
        if (name.isBlank()) return true
        return SECRET_NAME.matches(name)
    }

    fun build(
        paths: TermuxPaths,
        workingDirectory: String?,
        androidEnv: Map<String, String> = emptyMap(),
        extra: Map<String, String> = emptyMap(),
        isReadableDirectory: (String) -> Boolean = { candidate ->
            java.io.File(candidate).let { it.isDirectory && it.canRead() }
        },
    ): Array<String> {
        java.io.File(paths.home).mkdirs()
        java.io.File(paths.tmp).mkdirs()

        val environment = LinkedHashMap<String, String>()

        environment["HOME"] = paths.home
        environment["PREFIX"] = paths.prefix
        environment["PATH"] = "${paths.bin}:/system/bin"
        environment["TMPDIR"] = paths.tmp
        environment["TERM"] = TERM
        environment["LANG"] = LANG
        environment["COLORTERM"] = COLORTERM
        environment["SHELL"] = "${paths.bin}/bash"

        val working = workingDirectory
            ?.takeIf { it.startsWith("/") && isReadableDirectory(it) }
            ?: paths.home
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

    fun isValidName(name: String): Boolean =
        name.isNotEmpty() && name[0].let { it.isLetter() || it == '_' } &&
            name.all { it.isLetterOrDigit() || it == '_' }
}
