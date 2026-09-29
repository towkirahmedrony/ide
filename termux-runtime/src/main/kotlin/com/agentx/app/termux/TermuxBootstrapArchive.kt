package com.agentx.app.termux

import java.io.File

object TermuxBootstrapArchive {

    const val SYMLINK_MANIFEST: String = "SYMLINKS.txt"
    const val SYMLINK_SEPARATOR: Char = '\u2190'
    const val INSTALL_MARKER: String = "etc/termux/agentx-bootstrap.ok"

    data class Symlink(val target: String, val linkPath: String)

    private val EXECUTABLE_PREFIXES = listOf("bin/", "libexec", "lib/apt/apt-helper", "lib/apt/methods")

    fun isExecutableEntry(entryName: String): Boolean {
        val name = entryName.removePrefix("./")
        return EXECUTABLE_PREFIXES.any { name.startsWith(it) }
    }

    fun isManifestEntry(entryName: String): Boolean = entryName.removePrefix("./") == SYMLINK_MANIFEST

    fun parseSymlinks(
        content: String,
        stagingPrefix: String,
        finalPrefix: String,
    ): SymlinkParseResult {
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
            val target = parts[0]
            val linkPath = parts[1]
            if (!isSafeEntry(linkPath) ||
                !isSafeSymlinkTarget(stagingPrefix, finalPrefix, linkPath, target)
            ) {
                invalid += line
                continue
            }
            links += Symlink(target = target, linkPath = linkPath)
        }
        return SymlinkParseResult(links = links, invalid = invalid)
    }

    data class SymlinkParseResult(val links: List<Symlink>, val invalid: List<String>) {
        val isUsable: Boolean get() = links.isNotEmpty()
    }

    fun resolveSymlink(stagingPrefix: String, linkPath: String): String? {
        if (!isSafeEntry(linkPath)) return null
        return resolvedInside(stagingPrefix, linkPath.removePrefix("./"))
    }

    /**
     * True when a symlink at [linkPath] pointing at [target] stays inside the Termux prefix.
     *
     * A real bootstrap archive mixes three target shapes, and all three occur in the
     * published `bootstrap-aarch64.zip`:
     *
     *  - `coreutils` for `bin/ls` — a bare name, resolved against the link's own
     *    directory, where `bin/coreutils` really exists (1111 of 1213 links);
     *  - `../ncurses.h` for `include/ncursesw/term.h` — relative, and `..` here stays
     *    inside the prefix (79 links);
     *  - `/data/data/<pkg>/files/usr/share/termux-keyring/<key>.gpg` for
     *    `share/pacman/keyrings/<key>.gpg` — absolute. Such a link is dangling while the
     *    archive is being unpacked into `usr-staging`, and correct once the staging
     *    directory is renamed to `usr`, which is exactly what upstream's own installer
     *    relies on (20 links).
     *
     * What matters is where the target resolves to, not how it is spelled. Rejecting
     * every absolute target or every target containing `..` — what this check did before
     * it was measured against a real archive — refuses 99 of those 1213 links, so an
     * install could never complete.
     */
    fun isSafeSymlinkTarget(
        stagingPrefix: String,
        finalPrefix: String,
        linkPath: String,
        target: String,
    ): Boolean {
        if (target.isEmpty() || target.contains('\u0000')) return false
        val link = normalizeInsidePrefix(linkPath) ?: return false
        if (target.startsWith("/")) {
            val normalized = normalizeAbsolute(target) ?: return false
            return normalized.isSameOrUnder(finalPrefix) || normalized.isSameOrUnder(stagingPrefix)
        }
        val directory = link.substringBeforeLast('/', missingDelimiterValue = "")
        val relative = if (directory.isEmpty()) target else "$directory/$target"
        return normalizeInsidePrefix(relative) != null
    }

    /**
     * Normalises a relative path, returning null when it is empty, absolute, contains a
     * NUL, or climbs above its own root. Pure string work: no filesystem, so the rules
     * can be unit tested and behave identically on any host.
     */
    private fun normalizeInsidePrefix(path: String): String? {
        if (path.isEmpty() || path.contains('\u0000') || path.startsWith("/")) return null
        val parts = ArrayList<String>()
        for (part in path.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.size - 1)
                else -> parts += part
            }
        }
        return if (parts.isEmpty()) null else parts.joinToString("/")
    }

    private fun normalizeAbsolute(path: String): String? {
        if (!path.startsWith("/") || path.contains('\u0000')) return null
        val parts = ArrayList<String>()
        for (part in path.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.size - 1)
                else -> parts += part
            }
        }
        return "/" + parts.joinToString("/")
    }

    private fun String.isSameOrUnder(prefix: String): Boolean {
        val normalizedPrefix = prefix.trimEnd('/')
        return this == normalizedPrefix || startsWith("$normalizedPrefix/")
    }

    fun isSafeEntry(entryName: String): Boolean {
        val name = entryName.removePrefix("./")
        if (name.isEmpty()) return false
        if (name.startsWith("/")) return false
        if (name.contains('\u0000')) return false
        return name.split('/').none { it == ".." }
    }

    fun resolvedInside(prefix: String, relative: String): String? {
        val prefixFile = File(prefix).canonicalFile
        val resolved = File(prefixFile, relative).canonicalFile
        val prefixPath = prefixFile.path
        val resolvedPath = resolved.path
        if (resolvedPath != prefixPath && !resolvedPath.startsWith(prefixPath + File.separator)) {
            return null
        }
        return resolved.path
    }
}
