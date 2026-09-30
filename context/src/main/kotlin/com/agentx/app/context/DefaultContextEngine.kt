package com.agentx.app.context

import com.agentx.app.core.logging.ForgeLogger
import com.agentx.app.core.logging.ForgeLoggers
import com.agentx.app.core.logging.LogLevel
import com.agentx.app.core.valueOrNull
import com.agentx.app.model.ModelMessage
import com.agentx.app.model.ModelRole
import com.agentx.app.workspace.WorkspacePath
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Default [ContextEngine].
 *
 * Build order is fixed and deterministic:
 * 1. the current user request (unless the caller supplies it itself),
 * 2. agent state,
 * 3. workspace information,
 * 4. tool results,
 * 5. recent conversation,
 * 6. files (mentioned → selected/open → recent → search-discovered),
 * 7. requested directory listings and the workspace root,
 * 8. context contributed by providers and already added to the session.
 *
 * Everything is then ranked, cut down to the budget and rendered. The engine
 * only ever reads the workspace through [WorkspaceContextProvider]; it never
 * walks a project on its own and never reads the whole repository.
 */
class DefaultContextEngine(
    private val workspace: WorkspaceContextProvider = EmptyWorkspaceContextProvider,
    private val providers: List<ContextProvider> = emptyList(),
    val budget: ContextBudget = ContextBudget.DEFAULT,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val fileCache: WorkspaceFileCache = WorkspaceFileCache(clock = clock),
    private val logger: ForgeLogger = ForgeLoggers.create(LogLevel.INFO, baseFields = mapOf("layer" to "context")),
) : ContextEngine {

    private val loader = WorkspaceFileContextLoader(fileCache, clock)

    private val sessions = ConcurrentHashMap<String, MutableList<ContextItem>>()

    override suspend fun buildContext(request: ContextRequest): ContextResult {
        val effectiveBudget = request.budget ?: budget
        val now = clock()

        val (snapshot, fileSystem) = withContext(dispatcher) {
            workspace.snapshot() to workspace.fileSystem()
        }

        val candidates = mutableListOf<ContextItem>()
        val exclusions = mutableListOf<ContextExclusion>()
        val truncations = mutableListOf<ContextTruncation>()
        val truncationIds = mutableSetOf<String>()

        fun noteTruncation(item: ContextItem, reason: String) {
            if (item.truncated && truncationIds.add(item.id)) {
                truncations += ContextTruncation(
                    id = item.id,
                    source = item.source,
                    path = item.path,
                    originalChars = item.originalChars,
                    keptChars = item.chars,
                    reason = reason,
                )
            }
        }

        fun consume(load: ContextLoad, reason: String) {
            when (load) {
                is ContextLoad.Loaded -> {
                    candidates += load.item
                    noteTruncation(load.item, reason)
                }

                is ContextLoad.Rejected -> exclusions += load.exclusion
            }
        }

        if (request.includeTask && request.task.isNotBlank()) {
            candidates += userRequestItem(request.task, now)
        }
        request.agentState?.let { candidates += agentStateItem(it, now) }
        request.taskState?.let { candidates += agentStateItem(it, now, id = "agent:task-state") }
        request.sessionSummary?.takeIf { it.isNotBlank() }?.let { summary ->
            candidates += sessionSummaryItem(summary, now)
        }
        // Workspace facts are only volunteered for a turn that is about the
        // project; a greeting must not turn into a project description.
        val workspaceContext = request.includeWorkspace
        if (workspaceContext && request.includeWorkspaceInfo) {
            snapshot?.let { candidates += workspaceInfoItem(it, now) }
        }

        request.toolResults.forEachIndexed { index, result ->
            val bounded = ContextTruncator.truncate(result.content, effectiveBudget.maxToolResultChars)
            val item = toolResultItem(result, index, bounded, now)
            candidates += item
            noteTruncation(item, "tool result budget")
        }

        val conversationLimit = effectiveBudget.maxConversationMessages.coerceAtLeast(0)
        if (request.conversation.size > conversationLimit) {
            exclusions += ContextExclusion(
                id = "conversation:older",
                source = ContextSource.CONVERSATION,
                reason = ContextExclusionReason.OVER_CONVERSATION_LIMIT,
                detail = "${request.conversation.size - conversationLimit} older messages left out",
            )
        }
        val conversation = request.conversation.takeLast(conversationLimit)
        val newestRank = conversation.size - 1
        conversation.forEachIndexed { index, message ->
            val item = conversationItem(message, newestRank - index, effectiveBudget, now)
            candidates += item
            noteTruncation(item, "conversation budget")
        }

        withContext(dispatcher) {
            providers.flatMap { provider -> provider.collect(request) }.forEach { item ->
                candidates += if (item.metadata.selectedBecause == null) {
                    item.copy(metadata = item.metadata.copy(selectedBecause = ContextReason.PROVIDER))
                } else {
                    item
                }
            }

            val selectedFiles = buildList {
                request.selectedFile?.let { add(it) }
                snapshot?.selectedFile?.let { add(it) }
            }
            val openFiles = (request.openFiles + (snapshot?.openFiles ?: emptyList())).distinct()
            val recentFiles = (request.recentFiles + (snapshot?.recentFiles ?: emptyList())).distinct()

            val intents = linkedMapOf<String, FileIntent>()
            fun noteFile(path: String, reason: ContextReason, priority: ContextPriority, relevance: Double) {
                val normalized = WorkspacePath.normalize(path).valueOrNull()?.takeIf { it.isNotEmpty() } ?: return
                val intent = FileIntent(reason, priority, relevance)
                val existing = intents[normalized]
                if (existing == null || intent.strength > existing.strength) intents[normalized] = intent
            }

            recentFiles.forEachIndexed { index, path ->
                noteFile(path, ContextReason.RECENT_FILE, ContextPriority.HIGH, ContextRelevance.recentFile(index))
            }
            openFiles.forEachIndexed { index, path ->
                noteFile(
                    path = path,
                    reason = ContextReason.SELECTED_FILE,
                    priority = ContextPriority.HIGH,
                    relevance = (ContextRelevance.SELECTED_FILE - index).coerceAtLeast(ContextRelevance.RECENT_FILE_MIN),
                )
            }
            selectedFiles.forEach { path ->
                noteFile(path, ContextReason.SELECTED_FILE, ContextPriority.HIGH, ContextRelevance.SELECTED_FILE)
            }
            request.searchResults.forEach { path ->
                noteFile(path, ContextReason.SEARCH_RESULT, ContextPriority.NORMAL, ContextRelevance.SEARCH_RESULT)
            }
            request.mentionedFiles.forEach { path ->
                noteFile(path, ContextReason.MENTIONED_FILE, ContextPriority.HIGH, ContextRelevance.MENTIONED_FILE)
            }
            // Only a path that actually exists in the open workspace becomes a
            // candidate, so a word that merely looks like a path never turns
            // into a failed read.
            MentionedFiles.detect(request.task)
                .filter { candidate -> fileSystem?.exists(candidate) == true }
                .forEach { path ->
                    noteFile(path, ContextReason.MENTIONED_FILE, ContextPriority.HIGH, ContextRelevance.MENTIONED_FILE)
                }

            val workspaceId = request.workspaceId ?: snapshot?.workspaceId
            for ((path, intent) in intents) {
                val load = loader.loadFile(
                    fileSystem = fileSystem,
                    workspaceId = workspaceId,
                    path = path,
                    reason = intent.reason,
                    priority = intent.priority,
                    relevance = intent.relevance,
                    maxChars = effectiveBudget.maxFileChars,
                )
                consume(load, "file budget")
                if (load is ContextLoad.Loaded) workspace.markAccessed(path)
            }

            val directoryPaths = LinkedHashSet<String>()
            if (workspaceContext && request.includeWorkspaceRootSummary && fileSystem != null) {
                directoryPaths += WorkspacePath.ROOT
            }
            request.directories.forEach { path ->
                WorkspacePath.normalize(path).valueOrNull()?.let { directoryPaths += it }
            }
            directoryPaths.forEach { path ->
                consume(
                    loader.loadDirectory(
                        fileSystem = fileSystem,
                        path = path,
                        maxEntries = effectiveBudget.maxDirectoryEntries,
                    ),
                    "directory budget",
                )
            }
        }

        request.sessionId?.let { sessionId -> candidates += sessionItems(sessionId) }

        val selection = enforceBudget(candidates, effectiveBudget)
        val text = render(selection.items)
        val counts = selection.items.groupingBy { it.source }.eachCount()
        val metadata = ContextMetadata(
            reason = "Context assembled for the current task",
            timestampMillis = now,
            attributes = buildMap<String, String> {
                snapshot?.name?.let { put("workspace", it) }
                put("items", selection.items.size.toString())
                put("files", (counts[ContextSource.FILE] ?: 0).toString())
                put("truncated", (truncations.size + selection.truncated.size).toString())
                put("excluded", (exclusions.size + selection.excluded.size).toString())
            },
        )
        logger.info(
            "Context assembled",
            mapOf(
                "workspaceId" to (request.workspaceId ?: snapshot?.workspaceId),
                "workspaceName" to snapshot?.name,
                "selectedFile" to (request.selectedFile ?: snapshot?.selectedFile),
                "hasFileSystem" to (fileSystem != null),
                "workspaceContext" to workspaceContext,
                "items" to selection.items.size,
                "files" to (counts[ContextSource.FILE] ?: 0),
                "directories" to (counts[ContextSource.DIRECTORY] ?: 0),
                "workspaceInfo" to (counts[ContextSource.WORKSPACE_INFO] ?: 0),
                "excluded" to (exclusions.size + selection.excluded.size),
            ),
        )
        return ContextResult(
            items = selection.items,
            text = text,
            excluded = exclusions + selection.excluded,
            truncated = (truncations + selection.truncated).distinctBy { it.id },
            usedChars = selection.usedChars,
            budget = effectiveBudget,
            metadata = metadata,
        )
    }

    override fun addItems(sessionId: String, items: List<ContextItem>): List<ContextItem> {
        if (items.isEmpty()) return sessionItems(sessionId)
        val session = sessions.computeIfAbsent(sessionId) { mutableListOf() }
        val merged = synchronized(session) {
            items.forEach { item ->
                val index = session.indexOfFirst { it.id == item.id }
                if (index >= 0) session[index] = item else session += item
            }
            session.toList()
        }
        return rank(merged)
    }

    override fun removeItems(sessionId: String, ids: Collection<String>): List<ContextItem> {
        val session = sessions[sessionId] ?: return emptyList()
        val remaining = synchronized(session) {
            session.removeAll { it.id in ids }
            session.toList()
        }
        return rank(remaining)
    }

    override fun items(sessionId: String): List<ContextItem> = rank(sessionItems(sessionId))

    override fun clearSession(sessionId: String) {
        sessions.remove(sessionId)
    }

    override fun rank(items: List<ContextItem>): List<ContextItem> = ContextRanker.rank(items)

    override fun enforceBudget(items: List<ContextItem>, budget: ContextBudget): ContextSelection {
        val selected = mutableListOf<ContextItem>()
        val truncations = mutableListOf<ContextTruncation>()
        val excluded = mutableListOf<ContextExclusion>()
        val limit = budget.charLimit
        var used = 0
        var fileCount = 0
        var directoryCount = 0
        var toolResultCount = 0
        var skillCount = 0
        var conversationChars = 0

        for (item in rank(items)) {
            val overCount = when (item.source) {
                ContextSource.FILE -> fileCount >= budget.maxFileCount
                ContextSource.DIRECTORY -> directoryCount >= budget.maxDirectoryCount
                ContextSource.TOOL_RESULT -> toolResultCount >= budget.maxToolResultCount
                ContextSource.SKILL -> skillCount >= budget.maxSkillItems
                else -> false
            }
            if (overCount) {
                excluded += exclusion(item, countLimitReason(item.source))
                continue
            }
            if (selected.size >= budget.maxItems) {
                excluded += exclusion(item, ContextExclusionReason.OVER_ITEM_LIMIT)
                continue
            }

            var candidate = item
            val perItemLimit = budget.itemCharLimit(item.source)
            // An item that was already shortened when it was created (a file the
            // loader cut down, or a tool result) keeps its true original size;
            // re-truncating it would report the wrong number.
            if (!item.truncated && candidate.content.length > perItemLimit) {
                val bounded = ContextTruncator.truncate(candidate.content, perItemLimit)
                candidate = candidate.copy(
                    content = bounded.text,
                    truncated = true,
                    originalChars = bounded.originalChars,
                )
                truncations += ContextTruncation(
                    id = candidate.id,
                    source = candidate.source,
                    path = candidate.path,
                    originalChars = bounded.originalChars,
                    keptChars = candidate.chars,
                    reason = "item budget",
                )
            }

            if (candidate.source == ContextSource.CONVERSATION) {
                if (conversationChars + candidate.chars > budget.maxConversationChars) {
                    excluded += exclusion(candidate, ContextExclusionReason.OVER_CONVERSATION_LIMIT)
                    continue
                }
                conversationChars += candidate.chars
            }

            val criticalSurvivor = selected.isEmpty() && candidate.priority == ContextPriority.CRITICAL
            if (used + candidate.chars > limit && !criticalSurvivor) {
                excluded += exclusion(candidate, ContextExclusionReason.OVER_CHAR_BUDGET)
                continue
            }

            when (candidate.source) {
                ContextSource.FILE -> fileCount++
                ContextSource.DIRECTORY -> directoryCount++
                ContextSource.TOOL_RESULT -> toolResultCount++
                ContextSource.SKILL -> skillCount++
                else -> Unit
            }
            selected += candidate
            used += candidate.chars
        }

        return ContextSelection(
            items = selected,
            truncated = truncations,
            excluded = excluded,
            usedChars = used,
            budget = budget,
        )
    }

    override fun render(items: List<ContextItem>): String =
        items.filter { it.content.isNotBlank() }.joinToString("\n\n") { item ->
            buildString {
                append('[').append(item.source.name)
                item.path?.takeIf { it.isNotBlank() }?.let { append(' ').append(it) }
                append(']')
                if (item.truncated) append(" (truncated)")
                append('\n')
                append(item.content.trim())
            }
        }

    override fun newRunContext(sessionId: String, budget: ContextBudget): RunContext =
        DefaultRunContext(
            engine = this,
            sessionId = sessionId,
            budget = budget,
            clock = clock,
        )

    override fun debug(result: ContextResult): ContextDebugReport {
        val bySource = result.items.groupingBy { it.source }.eachCount()
        val lines = mutableListOf<String>()
        lines += "context: ${result.items.size} items · ${result.usedChars}/${result.limitChars} chars " +
            "(~${result.estimatedTokens} tokens)"
        lines += "sources: " + ContextSource.entries.joinToString(", ") { "$it=${bySource[it] ?: 0}" }
        result.items.forEachIndexed { index, item ->
            val where = item.path?.takeIf { it.isNotBlank() }?.let { " $it" }
                ?: item.title?.takeIf { it.isNotBlank() }?.let { " ($it)" }
                ?: ""
            val shortening = if (item.truncated) " truncated=${item.chars}/${item.originalChars}" else ""
            val reason = item.metadata.selectedBecause?.name ?: ContextReason.MANUAL.name
            lines += "  ${index + 1}. ${item.id} · ${item.source.name}$where · $reason" +
                " · ${item.priority.name.lowercase()} · relevance=${item.relevance}$shortening"
        }
        if (result.truncated.isNotEmpty()) {
            lines += "truncated:"
            result.truncated.forEach { truncation ->
                lines += "  - ${truncation.id} ${truncation.keptChars}/${truncation.originalChars} chars" +
                    " (${truncation.reason})"
            }
        }
        if (result.excluded.isNotEmpty()) {
            lines += "excluded:"
            result.excluded.forEach { exclusion ->
                val detail = exclusion.detail?.let { " — $it" }.orEmpty()
                lines += "  - ${exclusion.id} · ${exclusion.reason.name}$detail"
            }
        }
        if (result.items.isEmpty()) lines += "  (nothing was selected)"
        return ContextDebugReport(
            summary = "${result.items.size} items · ${result.usedChars}/${result.limitChars} chars",
            lines = lines,
        )
    }

    private fun sessionItems(sessionId: String): List<ContextItem> {
        val session = sessions[sessionId] ?: return emptyList()
        return synchronized(session) { session.toList() }
    }

    private fun exclusion(item: ContextItem, reason: ContextExclusionReason): ContextExclusion =
        ContextExclusion(id = item.id, source = item.source, path = item.path, reason = reason)

    private fun countLimitReason(source: ContextSource): ContextExclusionReason = when (source) {
        ContextSource.FILE -> ContextExclusionReason.OVER_FILE_LIMIT
        ContextSource.DIRECTORY -> ContextExclusionReason.OVER_DIRECTORY_LIMIT
        ContextSource.TOOL_RESULT -> ContextExclusionReason.OVER_TOOL_RESULT_LIMIT
        ContextSource.SKILL -> ContextExclusionReason.OVER_SKILL_LIMIT
        else -> ContextExclusionReason.OVER_ITEM_LIMIT
    }

    private fun userRequestItem(task: String, now: Long): ContextItem = ContextItem(
        id = "user:request",
        source = ContextSource.USER_MESSAGE,
        content = task.trim(),
        priority = ContextPriority.CRITICAL,
        relevance = ContextRelevance.CURRENT_REQUEST,
        title = "Current request",
        metadata = ContextMetadata(
            reason = "Current user request",
            selectedBecause = ContextReason.CURRENT_REQUEST,
            timestampMillis = now,
        ),
        createdAtMillis = now,
    )

    private fun sessionSummaryItem(summary: String, now: Long): ContextItem = ContextItem(
        id = "session:summary",
        source = ContextSource.AGENT_STATE,
        content = summary.trim(),
        priority = ContextPriority.NORMAL,
        relevance = ContextRelevance.AGENT_STATE,
        title = "Session memory",
        metadata = ContextMetadata(
            reason = "Compact memory of this agent session",
            selectedBecause = ContextReason.AGENT_STATE,
            timestampMillis = now,
        ),
        createdAtMillis = now,
    )

    private fun agentStateItem(
        state: ContextAgentState,
        now: Long,
        id: String = "agent:state",
    ): ContextItem = ContextItem(
        id = id,
        source = ContextSource.AGENT_STATE,
        content = buildString {
            append("role=").append(state.role ?: "unknown")
            append("\nstatus=").append(state.status ?: "unknown")
            if (state.maxSteps > 0) append("\nstep=").append(state.step).append('/').append(state.maxSteps)
            state.progress?.takeIf { it.isNotBlank() }?.let { append("\nprogress=").append(it.trim()) }
        },
        priority = ContextPriority.NORMAL,
        relevance = ContextRelevance.AGENT_STATE,
        title = "Agent state",
        metadata = ContextMetadata(
            reason = "Progress of the running task",
            selectedBecause = ContextReason.AGENT_STATE,
            timestampMillis = now,
        ),
        createdAtMillis = now,
    )

    private fun workspaceInfoItem(snapshot: WorkspaceSnapshot, now: Long): ContextItem = ContextItem(
        id = "workspace:info",
        source = ContextSource.WORKSPACE_INFO,
        content = buildString {
            append("name=").append(snapshot.name ?: "(unnamed)")
            append("\nroot=").append(snapshot.rootPath.ifEmpty { "/" })
            snapshot.workspaceId?.takeIf { it.isNotBlank() }?.let { append("\nid=").append(it) }
            snapshot.selectedFile?.takeIf { it.isNotBlank() }?.let { append("\nselectedFile=").append(it) }
            if (snapshot.openFiles.isNotEmpty()) {
                append("\nopenFiles=").append(snapshot.openFiles.joinToString(", "))
            }
            if (snapshot.recentFiles.isNotEmpty()) {
                append("\nrecentFiles=").append(snapshot.recentFiles.joinToString(", "))
            }
        },
        priority = ContextPriority.LOW,
        relevance = ContextRelevance.WORKSPACE_INFO,
        title = "Workspace",
        metadata = ContextMetadata(
            reason = "Workspace the agent is working in",
            selectedBecause = ContextReason.WORKSPACE_INFO,
            timestampMillis = now,
        ),
        createdAtMillis = now,
    )

    private fun toolResultItem(
        result: ToolContextResult,
        index: Int,
        bounded: TruncatedContent,
        now: Long,
    ): ContextItem {
        val callId = result.callId?.takeIf { it.isNotBlank() }
        val id = if (callId != null) "tool:$callId" else "tool:result:$index"
        return ContextItem(
            id = id,
            source = ContextSource.TOOL_RESULT,
            content = bounded.text,
            priority = ContextPriority.NORMAL,
            relevance = result.relevance,
            path = result.path,
            title = result.toolId,
            metadata = ContextMetadata(
                reason = "Result of ${result.toolId} (${result.status.name.lowercase()})",
                selectedBecause = ContextReason.TOOL_RESULT,
                toolId = result.toolId,
                toolStatus = result.status,
                timestampMillis = result.timestampMillis,
                originalChars = bounded.originalChars,
            ),
            truncated = bounded.truncated,
            originalChars = bounded.originalChars,
            createdAtMillis = now,
        )
    }

    private fun conversationItem(
        message: ModelMessage,
        recencyRank: Int,
        budget: ContextBudget,
        now: Long,
    ): ContextItem {
        val bounded = ContextTruncator.truncate(message.content, budget.maxConversationChars)
        val body = buildString {
            if (message.role == ModelRole.TOOL && !message.name.isNullOrBlank()) {
                append("tool ").append(message.name).append(": ")
            }
            append(bounded.text)
            if (message.toolCalls.isNotEmpty()) {
                append("\n[requested tools: ")
                append(message.toolCalls.joinToString(", ") { it.name })
                append(']')
            }
        }
        return ContextItem(
            id = "conversation:$recencyRank",
            source = ContextSource.CONVERSATION,
            content = body,
            priority = ContextPriority.LOW,
            relevance = ContextRelevance.conversation(recencyRank),
            title = message.role.name.lowercase(),
            metadata = ContextMetadata(
                reason = "Earlier conversation (${message.role.name.lowercase()})",
                selectedBecause = ContextReason.CONVERSATION,
                recencyRank = recencyRank,
                timestampMillis = now,
            ),
            truncated = bounded.truncated,
            originalChars = bounded.originalChars,
            createdAtMillis = now,
        )
    }

    /** One file's strongest selection intent; the engine loads each file once. */
    private data class FileIntent(
        val reason: ContextReason,
        val priority: ContextPriority,
        val relevance: Double,
    ) {
        val strength: Double get() = priority.rank * 1_000.0 + relevance
    }
}
