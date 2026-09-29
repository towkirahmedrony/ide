package com.agentx.app.termux

object TermuxShellResolver {

    val LOGIN_SHELL_BINARIES: List<String> = listOf("login", "bash", "zsh", "fish", "sh")

    const val SYSTEM_SHELL: String = "/system/bin/sh"

    enum class Kind {
        CUSTOM_PREFIX,
        TEMPORARY_SYSTEM,
        INSTALLATION_REQUIRED,
    }

    data class Resolved(
        val executable: String,
        val processName: String,
        val login: Boolean,
        val kind: Kind,
        val reason: String? = null,
    ) {
        val isFullTermux: Boolean get() = kind == Kind.CUSTOM_PREFIX && login
        val isTemporarySystemShell: Boolean get() = kind == Kind.TEMPORARY_SYSTEM
    }

    data class ProbeResult(
        val exists: Boolean,
        val executable: Boolean,
        val abiCompatible: Boolean,
        val runtimeReady: Boolean,
    ) {
        val usable: Boolean get() = exists && executable && abiCompatible && runtimeReady
    }

    fun resolve(
        paths: TermuxPaths,
        isExecutable: (String) -> Boolean,
        prefixSupport: TermuxPrefixSupport = TermuxPrefixPolicy.evaluate(paths),
        allowTemporarySystemShell: Boolean = true,
        probe: (String) -> ProbeResult = { path ->
            val executable = isExecutable(path)
            ProbeResult(
                exists = executable,
                executable = executable,
                abiCompatible = true,
                runtimeReady = true,
            )
        },
    ): Resolved {
        if (TermuxPrefixPolicy.isOfficialPath(paths.prefix) || TermuxPrefixPolicy.isOfficialPath(paths.appDataDir)) {
            return installationRequired(
                "Refusing to probe ${TermuxPaths.OFFICIAL_APP_DATA_DIR} from AgentX.",
            )
        }
        if (prefixSupport !is TermuxPrefixSupport.Supported) {
            return temporaryOrRequired(
                allowTemporarySystemShell,
                (prefixSupport as? TermuxPrefixSupport.Unsupported)?.reason
                    ?: "Unsupported prefix ${paths.prefix}.",
            )
        }

        for (name in LOGIN_SHELL_BINARIES) {
            val candidate = "${paths.bin}/$name"
            if (TermuxPrefixPolicy.isOfficialPath(candidate)) continue
            val inspected = probe(candidate)
            if (!inspected.exists) continue
            if (!inspected.executable || !inspected.abiCompatible || !inspected.runtimeReady) continue
            return Resolved(
                executable = candidate,
                processName = "-$name",
                login = true,
                kind = Kind.CUSTOM_PREFIX,
            )
        }

        return temporaryOrRequired(
            allowTemporarySystemShell,
            "Custom prefix ${paths.prefix} has no executable login shell. Full Termux support is not available.",
        )
    }

    private fun temporaryOrRequired(allowTemporarySystemShell: Boolean, reason: String): Resolved =
        if (allowTemporarySystemShell) {
            Resolved(
                executable = SYSTEM_SHELL,
                processName = "sh",
                login = false,
                kind = Kind.TEMPORARY_SYSTEM,
                reason = reason,
            )
        } else {
            installationRequired(reason)
        }

    private fun installationRequired(reason: String): Resolved = Resolved(
        executable = SYSTEM_SHELL,
        processName = "sh",
        login = false,
        kind = Kind.INSTALLATION_REQUIRED,
        reason = reason,
    )
}

data class TermuxShellStartFailure(
    val executable: String,
    val stderr: String,
    val exitReason: String,
) {
    val message: String
        get() = "Failed to start $executable: $exitReason" +
            if (stderr.isBlank()) "" else "\n$stderr"
}
