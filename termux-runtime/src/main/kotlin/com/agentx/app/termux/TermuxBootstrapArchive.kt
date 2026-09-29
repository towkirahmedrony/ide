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
            val target = parts[0]
            val linkPath = parts[1]
            if (!isSafeEntry(linkPath) || !isSafeSymlinkTarget(target)) {
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

    fun isSafeSymlinkTarget(target: String): Boolean {
        if (target.isEmpty() || target.contains('\u0000')) return false
        if (target.startsWith("/")) return false
        return target.split('/').none { it == ".." }
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
