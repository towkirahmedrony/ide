package com.agentx.app.git

/**
 * Pure parsers for the machine-readable Git output the service requests.
 *
 * They are the whole reason the service can be tested on the JVM: a recorded `git` output is
 * turned into models without a repository, a device or a process.
 */
object GitParsing {

    private const val NUL = '\u0000'
    private const val FIELD = '\u001f'
    private const val RECORD = '\u001e'
    private const val TAB = '\t'

    /**
     * Parses `git status --porcelain=v1 -b -z`.
     *
     * In this format records are NUL-separated: the first is the `##` branch header, and a
     * rename/copy (`R`/`C`) is followed by the original path in its own record.
     */
    fun parseStatus(raw: String): GitStatus {
        val records = raw.split(NUL)
        var branch: String? = null
        var upstream: String? = null
        var ahead = 0
        var behind = 0
        var state = GitRepositoryState.NORMAL
        val changes = mutableListOf<GitFileChange>()

        var index = 0
        while (index < records.size) {
            val record = records[index]
            if (record.isEmpty()) {
                index++
                continue
            }
            if (record.startsWith(BRANCH_HEADER)) {
                val header = parseBranchHeader(record.removePrefix(BRANCH_HEADER))
                branch = header.branch
                upstream = header.upstream
                ahead = header.ahead
                behind = header.behind
                state = header.state
                index++
                continue
            }
            if (record.length < MIN_ENTRY_LENGTH) {
                index++
                continue
            }
            val x = record[0]
            val y = record[1]
            val path = record.substring(ENTRY_PATH_OFFSET)
            if (x == 'R' || x == 'C') {
                val original = records.getOrNull(index + 1).orEmpty()
                changes += toChange(path, x, y, original.ifBlank { null })
                index += 2
                continue
            }
            changes += toChange(path, x, y, null)
            index++
        }

        return GitStatus(
            branch = branch,
            state = state,
            upstream = upstream,
            ahead = ahead,
            behind = behind,
            changes = changes,
        )
    }

    /** Parses `git for-each-ref` with the `refname / short / HEAD / upstream` format. */
    fun parseBranches(raw: String): List<GitBranch> = raw.lineSequence()
        .map { it.trim('\n', '\r') }
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            val fields = line.split(TAB)
            if (fields.size < HEAD_FIELD_INDEX + 1) return@mapNotNull null
            val refname = fields[0]
            val name = fields[1]
            if (name.isBlank()) return@mapNotNull null
            val remote = refname.startsWith(REMOTE_PREFIX)
            // A remote's symbolic `.../HEAD` is not a branch a user switches to.
            if (remote && name.endsWith("/HEAD")) return@mapNotNull null
            GitBranch(
                name = name,
                current = fields[HEAD_FIELD_INDEX] == CURRENT_BRANCH_MARKER,
                remote = remote,
                upstream = fields.getOrNull(UPSTREAM_FIELD_INDEX)?.takeIf { it.isNotBlank() },
            )
        }
        .toList()

    /** Parses the `%H%x1f%h%x1f%an%x1f%ad%x1f%s%x1e` log format. */
    fun parseLog(raw: String): List<GitLogEntry> = raw.split(RECORD)
        .map { it.trim('\n', '\r') }
        .filter { it.isNotBlank() }
        .mapNotNull { record ->
            val fields = record.split(FIELD)
            if (fields.size < LOG_FIELD_COUNT) return@mapNotNull null
            GitLogEntry(
                hash = fields[0],
                shortHash = fields[1],
                author = fields[2],
                date = fields[3],
                subject = fields[4],
            )
        }

    /** Parses `git remote -v`, keeping each remote's fetch URL. */
    fun parseRemotes(raw: String): List<GitRemote> = raw.lineSequence()
        .map { it.trim() }
        .filter { it.endsWith(FETCH_SUFFIX) }
        .mapNotNull { line ->
            val withoutSuffix = line.removeSuffix(FETCH_SUFFIX).trimEnd()
            val separator = withoutSuffix.indexOfFirst { it == ' ' || it == TAB }
            if (separator <= 0) return@mapNotNull null
            val name = withoutSuffix.substring(0, separator)
            val url = withoutSuffix.substring(separator).trim()
            if (name.isBlank() || url.isBlank()) return@mapNotNull null
            GitRemote(name = name, url = url)
        }
        // Deduplicate: `remote -v` prints fetch (and push) per remote.
        .distinctBy { it.name }
        .toList()

    // --- internals ---------------------------------------------------------

    private data class BranchHeader(
        val branch: String?,
        val upstream: String?,
        val ahead: Int,
        val behind: Int,
        val state: GitRepositoryState,
    )

    private fun parseBranchHeader(header: String): BranchHeader {
        val text = header.trim()
        if (text == DETACHED_HEADER) {
            return BranchHeader(null, null, 0, 0, GitRepositoryState.DETACHED)
        }
        for (prefix in NO_COMMITS_PREFIXES) {
            if (text.startsWith(prefix)) {
                return BranchHeader(
                    branch = text.removePrefix(prefix).trim().ifBlank { null },
                    upstream = null,
                    ahead = 0,
                    behind = 0,
                    state = GitRepositoryState.NO_COMMITS,
                )
            }
        }
        val branch = text.substringBefore(TRACKING_SEPARATOR).trim().ifBlank { null }
        val afterSeparator = text.substringAfter(TRACKING_SEPARATOR, missingDelimiterValue = "")
        val upstream = afterSeparator.substringBefore(" [").trim().ifBlank { null }
        return BranchHeader(
            branch = branch,
            upstream = upstream,
            ahead = AHEAD_PATTERN.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            behind = BEHIND_PATTERN.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            state = GitRepositoryState.NORMAL,
        )
    }

    private fun toChange(path: String, x: Char, y: Char, originalPath: String?): GitFileChange {
        val untracked = x == '?' || y == '?'
        val unmerged = x == 'U' || y == 'U' ||
            (x == 'A' && y == 'A') || (x == 'D' && y == 'D')
        val type = when {
            untracked -> GitChangeType.UNTRACKED
            unmerged -> GitChangeType.UNMERGED
            x == 'R' || y == 'R' -> GitChangeType.RENAMED
            x == 'C' || y == 'C' -> GitChangeType.COPIED
            x == 'T' || y == 'T' -> GitChangeType.TYPECHANGED
            x == 'A' -> GitChangeType.ADDED
            x == 'D' || y == 'D' -> GitChangeType.DELETED
            else -> GitChangeType.MODIFIED
        }
        return GitFileChange(
            path = path,
            type = type,
            staged = !untracked && x != ' ' && x != '!',
            unstaged = untracked || (y != ' ' && y != '!'),
            originalPath = originalPath,
        )
    }

    private const val BRANCH_HEADER = "## "
    private const val MIN_ENTRY_LENGTH = 4

    /** `XY<space>path`, so the path starts at index 3. */
    private const val ENTRY_PATH_OFFSET = 3
    private const val HEAD_FIELD_INDEX = 2
    private const val UPSTREAM_FIELD_INDEX = 3
    private const val LOG_FIELD_COUNT = 5
    private const val CURRENT_BRANCH_MARKER = "*"
    private const val REMOTE_PREFIX = "refs/remotes/"
    private const val FETCH_SUFFIX = "(fetch)"
    private const val DETACHED_HEADER = "HEAD (no branch)"
    private const val TRACKING_SEPARATOR = "..."

    private val NO_COMMITS_PREFIXES = listOf("No commits yet on ", "Initial commit on ")
    private val AHEAD_PATTERN = Regex("ahead (\\d+)")
    private val BEHIND_PATTERN = Regex("behind (\\d+)")
}
