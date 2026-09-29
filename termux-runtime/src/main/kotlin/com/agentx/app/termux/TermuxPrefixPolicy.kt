package com.agentx.app.termux

sealed interface TermuxPrefixSupport {
    data class Supported(override val prefix: String, val official: Boolean) : TermuxPrefixSupport
    data class Unsupported(override val prefix: String, val reason: String, val remedy: String) : TermuxPrefixSupport
    val prefix: String
}

object TermuxPrefixPolicy {

    const val AGENTX_PREFIX: String = TermuxPaths.AGENTX_PREFIX
    const val OFFICIAL_PREFIX: String = TermuxPaths.OFFICIAL_PREFIX

    fun evaluate(paths: TermuxPaths): TermuxPrefixSupport {
        val prefix = paths.prefix
        if (prefix.length > TermuxPaths.MAX_PREFIX_LENGTH) {
            return TermuxPrefixSupport.Unsupported(
                prefix = prefix,
                reason = "The prefix $prefix is ${prefix.length} characters, over the ${TermuxPaths.MAX_PREFIX_LENGTH} Termux allows.",
                remedy = REMEDY,
            )
        }
        if (paths.usesOfficialPrefix || paths.usesOfficialPackageDir || isOfficialPath(prefix)) {
            return TermuxPrefixSupport.Unsupported(
                prefix = prefix,
                reason = "The official Termux prefix $OFFICIAL_PREFIX is never used inside AgentX. " +
                    "Official Termux (${TermuxPaths.OFFICIAL_PACKAGE_NAME}) must stay a separate install.",
                remedy = REMEDY,
            )
        }
        if (paths.usesAgentxPrefix && paths.usesAgentxPackageDir) {
            return TermuxPrefixSupport.Supported(prefix = prefix, official = false)
        }
        return TermuxPrefixSupport.Unsupported(
            prefix = prefix,
            reason = "Arbitrary prefix $prefix is not allowed. AgentX only supports $AGENTX_PREFIX.",
            remedy = REMEDY,
        )
    }

    fun requireMatchingPrefix(runtimePrefix: String, artifactPrefix: String): TermuxPrefixSupport {
        if (isOfficialPath(runtimePrefix) || isOfficialPath(artifactPrefix)) {
            return TermuxPrefixSupport.Unsupported(
                prefix = runtimePrefix,
                reason = "Artifact prefix $artifactPrefix and runtime prefix $runtimePrefix must not use $OFFICIAL_PREFIX.",
                remedy = REMEDY,
            )
        }
        if (runtimePrefix != artifactPrefix) {
            return TermuxPrefixSupport.Unsupported(
                prefix = runtimePrefix,
                reason = "Artifact prefix $artifactPrefix does not match the runtime prefix $runtimePrefix. Incompatible binaries will not be launched.",
                remedy = REMEDY,
            )
        }
        if (runtimePrefix != AGENTX_PREFIX) {
            return TermuxPrefixSupport.Unsupported(
                prefix = runtimePrefix,
                reason = "Arbitrary prefix $runtimePrefix is not allowed. AgentX only supports $AGENTX_PREFIX.",
                remedy = REMEDY,
            )
        }
        return TermuxPrefixSupport.Supported(prefix = runtimePrefix, official = false)
    }

    fun canLaunch(paths: TermuxPaths, executable: String): Boolean {
        if (evaluate(paths) !is TermuxPrefixSupport.Supported) return false
        if (isOfficialPath(executable) || isOfficialPath(paths.prefix)) return false
        return executable.startsWith(paths.prefix) || executable == TermuxShellResolver.SYSTEM_SHELL
    }

    fun isOfficialPath(path: String): Boolean {
        val trimmed = path.trimEnd('/')
        return trimmed == TermuxPaths.OFFICIAL_APP_DATA_DIR ||
            trimmed == OFFICIAL_PREFIX ||
            trimmed.startsWith(TermuxPaths.OFFICIAL_APP_DATA_DIR + "/")
    }

    private const val REMEDY: String =
        "Keep applicationId ${TermuxPaths.AGENTX_PACKAGE_NAME} and install a bootstrap built for $AGENTX_PREFIX."
}
