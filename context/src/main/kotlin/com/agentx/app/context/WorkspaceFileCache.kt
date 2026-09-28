package com.agentx.app.context

import java.util.concurrent.ConcurrentHashMap

/**
 * Short-lived cache of raw file contents for the Context Engine.
 *
 * A single agent task builds context many times (once per model step, plus
 * every attached tool result), and the same file is usually part of all of
 * them. Caching the raw text keeps storage reads at one per file per validity
 * window. Entries expire, and can be dropped explicitly when the workspace
 * changes.
 *
 * Only text is cached; nothing is written anywhere.
 */
class WorkspaceFileCache(
    private val validityMillis: Long = DEFAULT_VALIDITY_MILLIS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private class Entry(val content: String, val loadedAtMillis: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    /** Cached content for [key], or null when absent or expired. */
    fun get(key: String): String? {
        val entry = entries[key] ?: return null
        if (clock() - entry.loadedAtMillis > validityMillis) {
            entries.remove(key, entry)
            return null
        }
        return entry.content
    }

    fun put(key: String, content: String) {
        entries[key] = Entry(content, clock())
    }

    /** Drops one entry; call when a file is known to have changed. */
    fun invalidate(key: String) {
        entries.remove(key)
    }

    fun clear() {
        entries.clear()
    }

    val size: Int get() = entries.size

    companion object {
        /** Default validity window for a cached file. */
        const val DEFAULT_VALIDITY_MILLIS = 30_000L

        /** Cache key for a file inside a workspace. */
        fun key(workspaceId: String?, path: String): String = "${workspaceId.orEmpty()}|$path"
    }
}
