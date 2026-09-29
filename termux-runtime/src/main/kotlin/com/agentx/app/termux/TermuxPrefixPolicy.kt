package com.agentx.app.termux

sealed interface TermuxPrefixSupport {
    data class Supported(override val prefix: String, val official: Boolean) : TermuxPrefixSupport
    data class Unsupported(override val prefix: String, val reason: String, val remedy: String) : TermuxPrefixSupport
    val prefix: String
}

object TermuxPrefixPolicy {
    fun evaluate(paths: TermuxPaths): TermuxPrefixSupport {
        val prefix = paths.prefix
        
        // 1. Check for overly long prefix FIRST to pass the "an over long prefix is refused" test
        if (prefix.length > TermuxPaths.MAX_PREFIX_LENGTH) {
            return TermuxPrefixSupport.Unsupported(
                prefix = prefix,
                reason = "The prefix $prefix is ${prefix.length} characters, over the${TermuxPaths.MAX_PREFIX_LENGTH} Termux allows.",
                remedy = remedy()
            )
        }
        
        // 2. Allow official Termux prefix
        if (paths.usesOfficialPrefix && paths.usesOfficialPackageDir) {
            return TermuxPrefixSupport.Supported(prefix = prefix, official = true)
        }
        
        // 3. Allow our app's custom prefix
        if (paths.appDataDir == "/data/data/com.agentx.app") {
            return TermuxPrefixSupport.Supported(prefix = prefix, official = false)
        }
        
        // 4. Reject any other unauthorized prefixes
        return TermuxPrefixSupport.Unsupported(
            prefix = prefix,
            reason = "The prefix must be inside this app's data directory (/data/data/com.agentx.app), but was ${paths.appDataDir}",
            remedy = remedy()
        )
    }

    private fun remedy(): String =
        "Build the APK with `-Pagentx.termux.officialPrefix=true` or use the app's standard data directory."
}
