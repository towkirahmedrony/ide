package com.agentx.app.workspace.process

import java.io.File

/** Finds a shell binary that actually exists on this device. */
fun interface ShellFinder {
    fun find(): String?
}

/**
 * Android ships `/system/bin/sh` on normal non-root devices. Termux or a
 * custom ROM may provide others; those are accepted only when present. Root
 * is never assumed.
 */
object ShellLocator : ShellFinder {

    val DEFAULT_CANDIDATES: List<String> = listOf(
        "/system/bin/sh",
        "/system/xbin/sh",
        "/vendor/bin/sh",
        "/bin/sh",
        "sh",
    )

    override fun find(): String? = find(DEFAULT_CANDIDATES)

    fun find(candidates: List<String>): String? {
        for (candidate in candidates) {
            if (candidate.isBlank()) continue
            if (candidate.contains('/')) {
                val file = File(candidate)
                if (file.isFile && file.canExecute()) return file.absolutePath
            } else {
                val fromPath = findOnPath(candidate)
                if (fromPath != null) return fromPath
            }
        }
        return null
    }

    private fun findOnPath(name: String): String? {
        val path = System.getenv("PATH") ?: return null
        for (dir in path.split(':')) {
            if (dir.isBlank()) continue
            val file = File(dir, name)
            if (file.isFile && file.canExecute()) return file.absolutePath
        }
        return null
    }
}
