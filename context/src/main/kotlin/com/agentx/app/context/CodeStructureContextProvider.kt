package com.agentx.app.context

import com.agentx.app.codeintel.CodeIntelligence
import com.agentx.app.codeintel.FileOutline
import com.agentx.app.codeintel.LanguageDetector
import com.agentx.app.codeintel.OutlineNode
import com.agentx.app.codeintel.SourceFile
import com.agentx.app.core.ForgeResult
import com.agentx.app.core.valueOrNull
import com.agentx.app.tools.SecretRedactor
import com.agentx.app.workspace.WorkspaceFileSystem
import com.agentx.app.workspace.WorkspacePath

/**
 * Contributes the *structure* of the files a task is about.
 *
 * A file item gives the model the source; this provider gives it the shape:
 * which classes, functions and properties exist and where they start. That is
 * usually what an agent needs to decide what to change, and it costs a fraction
 * of the full text — so it stays useful even when the file itself did not fit
 * the budget.
 *
 * The provider is deliberately conservative:
 *
 * - it only looks at the files the request already points at (selected, open,
 *   mentioned), never at the repository;
 * - it skips protected paths before reading anything;
 * - it only reports a language whose parser is actually available, so a
 *   TypeScript file in a build without that grammar adds nothing instead of
 *   adding an empty outline;
 * - it reads through the Workspace Runtime and the code intelligence cache, so a
 *   file already analysed for the editor is not parsed again;
 * - its output is redacted and bounded, like every other context item.
 */
class CodeStructureContextProvider(
    private val codeIntelligence: CodeIntelligence,
    private val workspace: WorkspaceContextProvider = EmptyWorkspaceContextProvider,
    private val limits: Limits = Limits(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) : ContextProvider {

    /** How much structure one request may carry. */
    data class Limits(
        /** Files described per build; the rest of the request's files keep their text item. */
        val maxFiles: Int = 2,
        /** Symbols rendered per file before the outline is cut short. */
        val maxSymbolsPerFile: Int = 40,
        /** Characters kept from one file's outline. */
        val maxCharsPerFile: Int = 1_200,
    ) {
        companion object {
            /** Room reserved for the truncation marker appended by the truncator. */
            internal const val TRUNCATION_MARKER_RESERVE = 80

            val DEFAULT = Limits()
        }
    }

    override val id: String = "code-structure"

    override val source: ContextSource = ContextSource.CODE_STRUCTURE

    override suspend fun collect(request: ContextRequest): List<ContextItem> {
        val paths = targetPaths(request)
        if (paths.isEmpty()) return emptyList()
        val fileSystem = workspace.fileSystem() ?: return emptyList()

        return buildList {
            for (path in paths) {
                structureItem(fileSystem, path)?.let { add(it) }
            }
        }
    }

    /**
     * Files worth describing, most relevant first: the file open in the editor,
     * then files the request mentions, then the other open files.
     */
    private fun targetPaths(request: ContextRequest): List<String> {
        val ordered = LinkedHashSet<String>()
        request.selectedFile?.let { ordered += it }
        ordered += request.mentionedFiles
        ordered += request.openFiles

        return ordered.asSequence()
            .mapNotNull { path -> WorkspacePath.normalize(path).valueOrNull() }
            .filter { path -> path.isNotEmpty() }
            .filter { path -> ProtectedPaths.reason(path) == null }
            .filter { path -> LanguageDetector.detect(path).isKnown }
            .filter { path -> codeIntelligence.canParse(path) }
            .distinct()
            .take(limits.maxFiles)
            .toList()
    }

    private suspend fun structureItem(fileSystem: WorkspaceFileSystem, path: String): ContextItem? {
        val content = when (val read = fileSystem.readFile(path)) {
            is ForgeResult.Success -> read.value
            // Unreadable, binary or a directory: the file item (if any) already
            // reports that, and structure adds nothing.
            is ForgeResult.Failure -> return null
        }
        if (content.isBlank()) return null

        val outline = when (val parsed = codeIntelligence.parseFile(SourceFile(path, content))) {
            is ForgeResult.Success -> parsed.value.outline
            // Unsupported language, no parser, too large, or a parser failure:
            // the engine keeps whatever the file item already provides.
            is ForgeResult.Failure -> return null
        }
        if (outline.isEmpty) return null

        val rendered = renderOutline(outline)
        if (rendered.isBlank()) return null
        val redacted = SecretRedactor.redactText(rendered)
        // The truncator appends a marker that reports what was dropped; reserving
        // room for it keeps the finished item inside the character limit.
        val bounded = ContextTruncator.truncate(
            redacted,
            (limits.maxCharsPerFile - Limits.TRUNCATION_MARKER_RESERVE).coerceAtLeast(1),
        )
        val name = WorkspacePath.name(path).ifEmpty { path }
        val now = clock()

        return ContextItem(
            id = "structure:$path",
            source = ContextSource.CODE_STRUCTURE,
            content = bounded.text,
            priority = ContextPriority.NORMAL,
            relevance = ContextRelevance.CODE_STRUCTURE,
            path = path,
            title = name,
            metadata = ContextMetadata(
                reason = "Declarations in $name",
                selectedBecause = ContextReason.PROVIDER,
                timestampMillis = now,
                originalChars = bounded.originalChars,
                attributes = buildMap {
                    put("language", outline.language.displayName)
                    put("symbols", outline.symbolCount.toString())
                    put("outlineTruncated", outline.truncated.toString())
                    put("syntaxErrors", outline.hasSyntaxErrors.toString())
                },
            ),
            truncated = bounded.truncated,
            originalChars = bounded.originalChars,
            createdAtMillis = now,
        )
    }

    /**
     * A compact outline: one line per symbol, indented by nesting, with the line
     * it starts on. Rendering is bounded so a huge file cannot fill the prompt
     * with names alone.
     */
    private fun renderOutline(outline: FileOutline): String {
        val builder = StringBuilder()
        var emitted = 0
        var truncated = false

        fun appendNode(node: OutlineNode, depth: Int) {
            if (truncated) return
            if (emitted >= limits.maxSymbolsPerFile) {
                truncated = true
                return
            }
            val symbol = node.symbol
            repeat(depth) { builder.append("  ") }
            builder.append(symbol.kind.displayName).append(' ').append(symbol.name)
            if (symbol.kind.isCallable) builder.append("()")
            builder.append(" · line ").append(symbol.range.start.line).append('\n')
            emitted++
            node.children.forEach { child -> appendNode(child, depth + 1) }
        }

        outline.roots.forEach { root -> appendNode(root, depth = 0) }
        if (truncated) builder.append("…[more symbols not shown]\n")
        return builder.toString().trimEnd()
    }
}
