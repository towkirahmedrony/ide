package com.agentx.app.termux

sealed interface TermuxPrefixSupport {
    data class Supported(override val prefix: String, val official: Boolean) : TermuxPrefixSupport
    data class Unsupported(override val prefix: String, val reason: String, val remedy: String) : TermuxPrefixSupport
    val prefix: String
}

object TermuxPrefixPolicy {
    fun evaluate(paths: TermuxPaths): TermuxPrefixSupport {
        val prefix = paths.prefix

        // 1. Strictly enforce length limit FIRST
        if (prefix.length > TermuxPaths.MAX_PREFIX_LENGTH) {
            return TermuxPrefixSupport.Unsupported(
                prefix = prefix,
                reason = "The prefix $prefix is ${prefix.length} characters, over the ${TermuxPaths.MAX_PREFIX_LENGTH} Termux allows.",
                remedy = remedy(paths)
            )
        }

        // 2. Allow official termux data dir
        if (paths.usesOfficialPrefix && paths.usesOfficialPackageDir) {
            return TermuxPrefixSupport.Supported(prefix = prefix, official = true)
        }

        // 3. Allow our custom app data dir safely
        if (paths.appDataDir == "/data/data/com.agentx.app") {
            return TermuxPrefixSupport.Supported(prefix = prefix, official = false)
        }

        // 4. Reject unauthorized directories
        return TermuxPrefixSupport.Unsupported(
            prefix = prefix,
            reason = "The official Termux bootstrap and its packages are built for the absolute prefix ${TermuxPaths.OFFICIAL_PREFIX} and this app's prefix is$prefix.",
            remedy = remedy(paths)
        )
    }

    private fun remedy(paths: TermuxPaths): String =
        "Build the APK with `-Pagentx.termux.officialPrefix=true` or use the app's standard data directory."
}
