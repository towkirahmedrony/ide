package com.agentx.app.context

/**
 * Finds file paths a user explicitly named in a request ("fix src/auth/Login.kt").
 *
 * This is deliberately a plain, deterministic scan: no model, no index and no
 * embeddings. Candidates are filtered by extension and then verified against
 * the workspace before anything is read, so a stray word with a dot in it
 * never turns into a read attempt.
 */
object MentionedFiles {

    private val candidate = Regex("""[A-Za-z0-9_][A-Za-z0-9_\-./]*\.[A-Za-z][A-Za-z0-9]{0,9}""")

    private val codeExtensions = setOf(
        "kt", "kts", "java", "ts", "tsx", "js", "jsx", "py", "rb", "go", "rs",
        "c", "h", "cpp", "hpp", "cc", "cs", "swift", "m", "mm", "sh", "bash",
        "sql", "md", "txt", "json", "yaml", "yml", "toml", "xml", "gradle",
        "properties", "proto", "html", "css", "scss", "less", "vue", "svelte",
        "php", "dart", "lua", "r", "pl", "ex", "exs", "clj", "hs", "scala",
        "groovy", "ini", "cfg", "conf", "env", "cmake", "tf", "ipynb",
    )

    /**
     * Path-like tokens found in [text], in order of appearance and without
     * duplicates. Nothing is checked against the workspace here; callers that
     * have a filesystem must verify existence before reading.
     */
    fun detect(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val found = LinkedHashSet<String>()
        for (match in candidate.findAll(text)) {
            val token = match.value.trim('.', '/', '-')
            if (token.length < MIN_LENGTH) continue
            if (token.contains("://")) continue
            val fileName = token.substringAfterLast('/')
            if (fileName.isBlank()) continue
            val extension = fileName.substringAfterLast('.', "").lowercase()
            if (extension !in codeExtensions) continue
            found += token
        }
        return found.toList()
    }

    private const val MIN_LENGTH = 3
}
