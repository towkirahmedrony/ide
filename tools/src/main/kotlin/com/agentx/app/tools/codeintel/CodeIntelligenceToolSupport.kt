package com.agentx.app.tools.codeintel

import com.agentx.app.codeintel.CodeIntelligence
import com.agentx.app.codeintel.CodeReference
import com.agentx.app.codeintel.CodeSymbol
import com.agentx.app.codeintel.FileOutline
import com.agentx.app.codeintel.ParsedFile
import com.agentx.app.codeintel.SourceFile
import com.agentx.app.codeintel.SymbolKind
import com.agentx.app.core.ForgeError
import com.agentx.app.core.ForgeErrorCode
import com.agentx.app.core.ForgeResult
import com.agentx.app.tools.Json
import com.agentx.app.tools.JsonValue
import com.agentx.app.tools.SecretRedactor
import com.agentx.app.tools.ToolErrorCode
import com.agentx.app.tools.ToolExecutionContext
import com.agentx.app.tools.ToolExecutionError
import com.agentx.app.tools.ToolInput
import com.agentx.app.tools.WorkspaceFileSystemResolver
import com.agentx.app.tools.missingWorkspace
import com.agentx.app.tools.orThrow
import com.agentx.app.workspace.WorkspaceFileSystem

/** Argument names shared by the code intelligence tools. */
internal const val ARG_PATH = "path"
internal const val ARG_NAME = "name"
internal const val ARG_KIND = "kind"

/** Character budget for a rendered outline in a tool's display text. */
internal const val MAX_OUTLINE_CHARS = 4_000

/** A workspace file and the analysis of the text that was just read. */
internal class AnalyzedSource(
    val path: String,
    val content: String,
    val parsed: ParsedFile,
)

/**
 * Reads [path] through the Workspace Runtime and analyses it.
 *
 * The same path rules as `read_file` apply (workspace-relative, no traversal),
 * and a language this build cannot parse is reported as a tool error rather
 * than as an empty symbol list. Re-analysing the same unchanged content (the
 * other tools, the editor, the context provider) is a cache hit in the code
 * intelligence engine.
 */
internal suspend fun analyzeSource(
    toolName: String,
    fileSystem: WorkspaceFileSystem,
    codeIntelligence: CodeIntelligence,
    path: String,
): AnalyzedSource {
    val content = fileSystem.readFile(path).orThrow(toolName, path)
    val parsed = when (val result = codeIntelligence.parseFile(SourceFile(path, content))) {
        is ForgeResult.Success -> result.value
        is ForgeResult.Failure -> throw result.error.toToolError(toolName, path)
    }
    return AnalyzedSource(path = path, content = content, parsed = parsed)
}

/** The [SourceFile] an analysis request is made for. */
internal fun AnalyzedSource.asSourceFile(): SourceFile = SourceFile(path = path, content = content)

/** Maps a code-intelligence failure onto the tool error vocabulary. */
internal fun ForgeError.toToolError(toolName: String, path: String): ToolExecutionError = ToolExecutionError(
    code = when (code) {
        // The request asked for structure this build cannot produce: that is an
        // argument problem, not a crash, and it is fixable by the caller.
        ForgeErrorCode.CODE_INTEL_UNSUPPORTED_LANGUAGE,
        ForgeErrorCode.CODE_INTEL_PARSER_UNAVAILABLE,
        ForgeErrorCode.CODE_INTEL_FILE_TOO_LARGE,
        -> ToolErrorCode.INVALID_ARGUMENTS

        else -> ToolErrorCode.EXECUTION_FAILED
    },
    message = message ?: "Code intelligence failed for '$path'",
    toolName = toolName,
    details = mapOf(
        "path" to Json.of(path),
        "code" to Json.of(code.name),
    ),
    cause = cause,
)

/** Resolves the workspace a tool call may read, or fails with a clear error. */
internal fun workspaceFor(toolName: String, workspaces: WorkspaceFileSystemResolver, context: ToolExecutionContext) =
    workspaces.resolve(context) ?: missingWorkspace(toolName)

/** The `name` argument of a lookup tool; blank input is a caller mistake. */
internal fun ToolInput.requireName(toolName: String): String {
    val name = string(ARG_NAME).orEmpty().trim()
    if (name.isEmpty()) {
        throw ToolExecutionError(
            code = ToolErrorCode.INVALID_ARGUMENTS,
            message = "'$ARG_NAME' must not be blank",
            toolName = toolName,
        )
    }
    return name
}

internal fun parseSymbolKind(raw: String): SymbolKind? {
    val value = raw.trim().lowercase()
    return SymbolKind.entries.firstOrNull { kind ->
        kind.displayName.lowercase() == value || kind.name.lowercase() == value
    }
}

internal fun symbolKindNames(): List<JsonValue> = SymbolKind.entries.map { Json.of(it.displayName) }

/** Symbol as structured data. Signatures are redacted; they are source text. */
internal fun CodeSymbol.toJsonValue(): JsonValue = Json.obj(
    buildMap<String, JsonValue?> {
        put("name", Json.of(name))
        put("kind", Json.of(kind.displayName))
        put("qualifiedName", Json.of(qualifiedName))
        put("path", Json.of(path))
        put("startLine", Json.of(range.start.line))
        put("startColumn", Json.of(range.start.column))
        put("endLine", Json.of(range.end.line))
        put("endColumn", Json.of(range.end.column))
        put("parent", parent?.let { Json.of(it) })
        put("signature", signature?.let { Json.of(SecretRedactor.redactText(it)) })
    }.filterValues { value -> value != null }.mapValues { (_, value) -> value!! },
)

internal fun CodeReference.toJsonValue(): JsonValue = Json.obj(
    "name" to Json.of(name),
    "line" to Json.of(range.start.line),
    "column" to Json.of(range.start.column),
    "endLine" to Json.of(range.end.line),
    "endColumn" to Json.of(range.end.column),
    "definition" to Json.of(isDefinition),
)

/** Compact text form for the agent; names and line numbers only, never bodies. */
internal fun renderSymbols(parsed: ParsedFile, symbols: List<CodeSymbol>, filtered: Boolean): String {
    val header = buildString {
        append(parsed.path).append(" · ").append(parsed.language.displayName)
        append(" · ").append(symbols.size)
        append(if (filtered) " matching symbols" else " symbols")
        if (parsed.hasSyntaxErrors) append(" · syntax errors present")
        if (parsed.outline.truncated) append(" · outline truncated")
    }
    if (symbols.isEmpty()) return "$header\n(no symbols found)"
    val body = symbols.joinToString("\n") { symbol ->
        val container = symbol.parent?.let { parent -> "$parent." }.orEmpty()
        val callable = if (symbol.kind.isCallable) "()" else ""
        "${symbol.kind.displayName} $container${symbol.name}$callable · line ${symbol.range.start.line}"
    }
    return "$header\n$body"
}

/** Renders an outline, bounded by characters. */
internal fun renderOutlineText(outline: FileOutline, maxChars: Int = MAX_OUTLINE_CHARS): String {
    val rendered = outline.render()
    if (rendered.length <= maxChars) return rendered
    return rendered.take(maxChars) + "\n…[outline cut after $maxChars characters]"
}
