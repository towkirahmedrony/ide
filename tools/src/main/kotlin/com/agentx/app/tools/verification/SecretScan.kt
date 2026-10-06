package com.agentx.app.tools.verification

/**
 * A lightweight, dependency-free secret detector for changed content.
 *
 * It is the detection counterpart of [com.agentx.app.tools.SecretRedactor]: where
 * the redactor masks a secret so it cannot reach the model, this scanner reports
 * *that* a likely secret is present — and where — without ever carrying the
 * secret value. Findings are safe to log, show and feed back to the agent.
 */
object SecretScan {

    /**
     * One likely-secret finding.
     *
     * [snippet] is always the line passed through the shared redactor, so even a
     * finding that is shown to the model or written to a log cannot leak a
     * credential.
     */
    data class Finding(
        val path: String,
        val line: Int,
        val reason: String,
        val snippet: String,
    ) {
        /** A safe one-line description: file, line and reason — never the value. */
        val safeDescription: String
            get() = "$path:$line — $reason"
    }

    /** Repository-relative path shown when a finding has no known file (e.g. a raw diff). */
    const val UNKNOWN_PATH: String = "(unknown)"

    private val privateKeyHeader = Regex(
        "-----BEGIN (?:RSA |EC |OPENSSH |DSA |PGP )?PRIVATE KEY-----",
    )

    private val tokenPrefixes = listOf(
        "ghp_", "gho_", "ghu_", "ghs_", "github_pat_", "glpat-", "xoxb-", "xoxp-", "AKIA", "sk-",
    )

    private val secretAssignment = Regex(
        """(?i)\b([A-Za-z0-9_.-]*(?:api[_-]?key|token|secret|password|passwd|credential|access[_-]?key)[A-Za-z0-9_.-]*)"?\s*[:=]\s*\S+""",
    )

    /**
     * Scans a unified diff for likely secrets in added lines only.
     *
     * Tracking only `+` lines means a removal of an old secret is not reported, and
     * a path is attached by reading the `+++ b/<path>` markers. A raw blob that is
     * not a diff is still scanned line by line, with [UNKNOWN_PATH].
     */
    fun scan(diff: String): List<Finding> {
        val findings = mutableListOf<Finding>()
        var currentPath = UNKNOWN_PATH
        var newLine = 0
        for (line in diff.lineSequence()) {
            when {
                line.startsWith("+++ ") -> {
                    currentPath = normalizePath(line.removePrefix("+++ ").trim())
                    continue
                }

                line.startsWith("@@") -> {
                    newLine = parseHunkStart(line)
                    continue
                }

                line.startsWith("+") && !line.startsWith("+++") -> {
                    newLine += 1
                    val content = line.substring(1)
                    val reason = reasonFor(content, currentPath) ?: continue
                    findings += Finding(
                        path = currentPath,
                        line = newLine,
                        reason = reason,
                        snippet = com.agentx.app.tools.SecretRedactor.redactText(content).take(200),
                    )
                }

                line.startsWith(" ") || line.startsWith("-") -> newLine += 1
            }
        }
        return findings
    }

    /** Scans a plain set of `path -> content` entries; used for staged files without a diff. */
    fun scanFiles(files: Map<String, String>): List<Finding> {
        val findings = mutableListOf<Finding>()
        for ((path, content) in files) {
            content.lineSequence().forEachIndexed { index, contentLine ->
                val reason = reasonFor(contentLine, path) ?: return@forEachIndexed
                findings += Finding(
                    path = path,
                    line = index + 1,
                    reason = reason,
                    snippet = com.agentx.app.tools.SecretRedactor.redactText(contentLine).take(200),
                )
            }
        }
        return findings
    }

    /** The reason a single content line looks secret, or null when it does not. */
    fun reasonFor(content: String, path: String = UNKNOWN_PATH): String? = when {
        privateKeyHeader.containsMatchIn(content) -> "A private key block was added"
        tokenPrefixes.any { prefix -> containsToken(content, prefix) } -> "A value with a token prefix was added"
        isSecretEnvironmentFile(path) && secretAssignment.containsMatchIn(content) ->
            "A secret-looking assignment was added to an environment file"
        secretAssignment.containsMatchIn(content) -> "A credential-like assignment was added"
        else -> null
    }

    private fun containsToken(content: String, prefix: String): Boolean {
        val index = content.indexOf(prefix)
        if (index < 0) return false
        // Require a plausible token body after the prefix so a prose word like "sk-" does not match.
        val body = content.substring(index + prefix.length)
        return body.takeWhile { !it.isWhitespace() && it != '"' && it != '\'' && it != ',' }.length >= 8
    }

    private fun isSecretEnvironmentFile(path: String): Boolean {
        val name = path.substringAfterLast('/')
        return name == ".env" || name.startsWith(".env.") || name.endsWith(".env")
    }

    private fun normalizePath(raw: String): String {
        val withoutTab = raw.substringBefore('\t')
        return when {
            withoutTab == "/dev/null" -> UNKNOWN_PATH
            withoutTab.startsWith("b/") -> withoutTab.removePrefix("b/")
            else -> withoutTab
        }
    }

    /** `@@ -a,b +c,d @@` → the first line of the new side, so findings point at the right line. */
    private fun parseHunkStart(header: String): Int {
        val match = HUNK.find(header) ?: return 0
        return (match.groupValues[1].toIntOrNull() ?: 0) - 1
    }

    private val HUNK = Regex("""@@ -\d+(?:,\d+)? \+(\d+)(?:,\d+)? @@""")
}
