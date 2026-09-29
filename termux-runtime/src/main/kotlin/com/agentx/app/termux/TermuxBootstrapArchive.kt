package com.agentx.app.termux

/**
 * The parts of bootstrap extraction that are pure logic, separated so they can be tested
 * without a device.
 *
 * Mirrors `TermuxInstaller.setupBootstrapIfNeeded` in termux-app: the archive holds entries
 * relative to `$PREFIX`, one `SYMLINKS.txt` manifest that has to be applied after extraction,
 * and a set of paths that must be made executable.
 */
object TermuxBootstrapArchive {

    const val SYMLINK_MANIFEST: String = "SYMLINKS.txt"

    /**
     * Separator used inside `SYMLINKS.txt`. It is U+2190 LEFTWARDS ARROW, not ASCII `<-`.
     * Upstream splits on the same character; using the wrong one silently drops every symlink
     * and produces a prefix where `sh`, `bash` and `pkg` do not resolve.
     */
    const val SYMLINK_SEPARATOR: Char = '\u2190'

    /** One `linkTarget ← linkPath` line of the manifest. */
    data class Symlink(val target: String, val linkPath: String)

    /** Entry prefixes whose files must be executable; identical to upstream's list. */
    private val EXECUTABLE_PREFIXES = listOf("bin/", "libexec", "lib/apt/apt-helper", "lib/apt/methods")

    /** True when the extracted file needs mode 0700. */
    fun isExecutableEntry(entryName: String): Boolean {
        val name = entryName.removePrefix("./")
        return EXECUTABLE_PREFIXES.any { name.startsWith(it) }
    }

    /** Should the entry be written to disk, or only recorded for later? */
    fun isManifestEntry(entryName: String): Boolean = entryName.removePrefix("./") == SYMLINK_MANIFEST

    /**
     * Parses `SYMLINKS.txt`.
     *
     * @param content manifest text, one link per line.
     * @return the parsed links; a malformed line is reported through [SymlinkParseResult.invalid]
     *   rather than dropped, because silently skipping it produces a broken prefix.
     */
    fun parseSymlinks(content: String): SymlinkParseResult {
        val links = ArrayList<Symlink>()
        val invalid = ArrayList<String>()
        for (raw in content.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val parts = line.split(SYMLINK_SEPARATOR)
            if (parts.size != 2) {
                invalid += line
                continue
            }
            links += Symlink(target = parts[0], linkPath = parts[1])
        }
        return SymlinkParseResult(links = links, invalid = invalid)
    }

    data class SymlinkParseResult(val links: List<Symlink>, val invalid: List<String>) {
        /** A manifest with no usable link at all means the archive is not the Termux bootstrap. */
        val isUsable: Boolean get() = links.isNotEmpty()
    }

    /** Resolves a manifest link path against the staging prefix. */
    fun resolveSymlink(stagingPrefix: String, linkPath: String): String =
        "$stagingPrefix/${linkPath.removePrefix("./")}"

    /**
     * Rejects an entry whose path would escape the staging prefix.
     *
     * `ZipInputStream` does not sanitise names, so an archive entry such as `../../bin/sh`
     * would otherwise be written outside the prefix.
     */
    fun isSafeEntry(entryName: String): Boolean {
        val name = entryName.removePrefix("./")
        if (name.isEmpty()) return false
        if (name.startsWith("/")) return false
        if (name.contains('\u0000')) return false
        return name.split('/').none { it == ".." }
    }
}
