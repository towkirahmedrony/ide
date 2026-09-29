package com.agentx.app.termux

/**
 * Whether the official Termux artifacts can be installed into this app's prefix.
 *
 * termux-packages compiles every binary and rewrites every script shebang to the absolute
 * prefix `/data/data/com.termux/files/usr` (`scripts/build/termux_step_replace_guess_scripts.sh`,
 * `scripts/build/termux_step_massage.sh`) and its variable validator refuses a prefix that
 * differs from `TERMUX_PREFIX_CLASSICAL` (`scripts/properties.sh`). Relocating the prefix
 * therefore is not something the published bootstrap or the `packages.termux.dev` repository
 * can survive: an app whose data directory is somewhere else cannot run them.
 *
 * Instead of silently producing a broken environment, the runtime asks this policy first and
 * reports exactly what is wrong when the prefix does not match.
 */
sealed interface TermuxPrefixSupport {

    /** Official bootstrap and `pkg`/`apt` into [prefix] will work. */
    data class Supported(override val prefix: String, val official: Boolean) : TermuxPrefixSupport

    /** A real Termux userspace cannot be installed into [prefix]. */
    data class Unsupported(
        override val prefix: String,
        val reason: String,
        val remedy: String,
    ) : TermuxPrefixSupport

    val prefix: String
}

/**
 * Decides whether a prefix can host the official Termux userland.
 *
 * Pure logic on purpose: the decision is the difference between a working environment and a
 * shell full of "not found" errors, so it is unit tested rather than discovered at runtime.
 */
object TermuxPrefixPolicy {

    fun evaluate(paths: TermuxPaths): TermuxPrefixSupport {
        val prefix = paths.prefix
        if (prefix.length > TermuxPaths.MAX_PREFIX_LENGTH) {
            return TermuxPrefixSupport.Unsupported(
                prefix = prefix,
                reason = "The prefix $prefix is ${prefix.length} characters, over the " +
                    "${TermuxPaths.MAX_PREFIX_LENGTH} Termux allows.",
                remedy = remedy(paths),
            )
        }
        if (paths.usesOfficialPrefix && paths.usesOfficialPackageDir) {
            return TermuxPrefixSupport.Supported(prefix = prefix, official = true)
        }
        return TermuxPrefixSupport.Unsupported(
            prefix = prefix,
            reason = "The official Termux bootstrap and its packages are built for the absolute " +
                "prefix ${TermuxPaths.OFFICIAL_PREFIX} and this app's prefix is $prefix. " +
                "Termux rewrites script shebangs to its build-time prefix, so those artifacts " +
                "cannot run from a different directory.",
            remedy = remedy(paths),
        )
    }

    /**
     * What the owner can do about an unsupported prefix. Two options are real; a third
     * (patching the downloaded binaries) is deliberately not offered because the replacement
     * prefix is longer than the original and binaries cannot be rewritten in place.
     */
    private fun remedy(paths: TermuxPaths): String =
        "Build the APK with `-Pagentx.termux.officialPrefix=true` so the application id and " +
            "data directory become ${TermuxPaths.OFFICIAL_APP_DATA_DIR} and the published " +
            "bootstrap applies as-is, or rebuild the bootstrap for ${paths.appDataDir} with " +
            "termux-packages after overriding TERMUX_APP__PACKAGE_NAME (see " +
            "docs/termux-bootstrap.md). Until then the terminal runs a real PTY shell in the " +
            "workspace but has no Termux packages."
}
