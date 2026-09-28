package com.agentx.app.codeintel

/**
 * Reuses the last analysis of a file whose content has not changed.
 *
 * The editor re-requests structure on every cursor move and on every recompose,
 * while the file itself only changes between keystrokes. The key is therefore
 * (path, language, content hash): a re-request for unchanged text is a map hit,
 * and an edit invalidates exactly that file's entry because its hash changes.
 *
 * Small and bounded on purpose — this is a working set for open files, not an
 * index of the repository. Least-recently-used entries are evicted once
 * [maxEntries] is reached, so memory stays flat while files are opened and
 * closed.
 *
 * Immutable results and synchronized access make it safe to share between the
 * background dispatcher that parses and the main thread that renders.
 */
internal class ParseCache(maxEntries: Int) {

    private val capacity: Int = maxEntries.coerceAtLeast(1)

    private val entries: LinkedHashMap<String, ParsedFile> =
        object : LinkedHashMap<String, ParsedFile>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ParsedFile>?): Boolean =
                size > capacity
        }

    @Synchronized
    fun get(key: String): ParsedFile? = entries[key]

    @Synchronized
    fun put(key: String, value: ParsedFile) {
        entries[key] = value
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    fun size(): Int = entries.size

    companion object {

        /** Key for one analysis of one file version. */
        fun key(path: String, language: CodeLanguage, contentHash: Long): String =
            "$path\u0000${language.name}\u0000$contentHash"

        /**
         * FNV-1a over UTF-16 code units. Not cryptographic: it only has to be
         * stable and to change when the text changes.
         */
        fun hashOf(content: String): Long {
            var hash = FNV_OFFSET_BASIS
            for (index in content.indices) {
                hash = hash xor content[index].code.toLong()
                hash *= FNV_PRIME
            }
            return hash
        }

        /** 0xcbf29ce484222325 as a signed Long. */
        private const val FNV_OFFSET_BASIS = -0x340d631b7bdddcdbL

        /** 0x100000001b3. */
        private const val FNV_PRIME = 0x100000001b3L
    }
}
