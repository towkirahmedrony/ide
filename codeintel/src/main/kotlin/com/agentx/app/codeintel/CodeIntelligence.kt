package com.agentx.app.codeintel

import com.agentx.app.core.ForgeError
import com.agentx.app.core.ForgeErrorCode
import com.agentx.app.core.ForgeResult

/**
 * Bounds that keep analysis predictable on a phone.
 *
 * They exist so a generated 5 MB file, a pathological nesting depth or a
 * runaway symbol count cannot turn an editor keystroke into a stall. Every bound
 * is reported rather than silently swallowed: results say they were truncated.
 */
data class CodeIntelligenceLimits(
    /** Files above this size are refused with a clear message. */
    val maxFileChars: Int = 512 * 1024,
    /** Upper bound on symbols extracted from one file. */
    val maxSymbols: Int = 2_000,
    /** Upper bound on references returned for one name. */
    val maxReferences: Int = 500,
    /** How deep symbol nesting is followed before the walk stops. */
    val maxDepth: Int = 64,
    /** Number of parsed files kept for unchanged-content reuse. */
    val cacheEntries: Int = 16,
    /** Longest signature kept on a symbol. */
    val maxSignatureChars: Int = 160,
) {
    companion object {
        val DEFAULT = CodeIntelligenceLimits()
    }
}

/** Builds a structured code-intelligence failure with non-secret details. */
fun codeIntelFailure(
    code: ForgeErrorCode,
    message: String,
    details: Map<String, Any?> = emptyMap(),
    cause: Throwable? = null,
): ForgeError = ForgeError(
    code = code,
    message = message,
    cause = cause,
    details = details.filterValues { it != null },
)

/**
 * Structural understanding of source files, independent of any parser.
 *
 * The editor, the agent and the tool system talk to this port only. Tree-sitter
 * sits *behind* it ([SyntaxParserProvider]): a different parser, or an LSP
 * document-symbol provider later, is a new backend plus a registration — not a
 * change to any caller.
 *
 * Everything that can be slow is `suspend` and runs on a background dispatcher;
 * nothing here is ever allowed to run on the Android main thread.
 */
interface CodeIntelligence {

    /** Detects the language of [path]. Unknown extensions return UNKNOWN. */
    fun detectLanguage(path: String): CodeLanguage

    /**
     * Languages a parser is available for right now. Callers must use this
     * before offering structure for a file: a detected language with no parser
     * is shown as plain text, not as an empty outline.
     */
    fun parsedLanguages(): Set<CodeLanguage>

    /** True when this build can parse [path] right now. */
    fun canParse(path: String): Boolean = detectLanguage(path) in parsedLanguages()

    /** Parses [file]: syntax tree, symbols and outline in one pass. */
    suspend fun parseFile(file: SourceFile): ForgeResult<ParsedFile, ForgeError>

    /** The syntax tree alone. Empty files yield no tree and no error. */
    suspend fun getSyntaxTree(file: SourceFile): ForgeResult<SyntaxTree, ForgeError>

    /** Structured symbols of [file], in source order. */
    suspend fun getSymbols(file: SourceFile): ForgeResult<List<CodeSymbol>, ForgeError>

    /** The outline the editor renders for [file]. */
    suspend fun getOutline(file: SourceFile): ForgeResult<FileOutline, ForgeError>

    /** Declarations of [name] in [file]. */
    suspend fun findDefinitions(file: SourceFile, name: String): ForgeResult<List<CodeSymbol>, ForgeError>

    /**
     * Occurrences of [name] in [file], the declaration included.
     *
     * Scope: the analysed file. Cross-file resolution needs an index of the
     * whole workspace, which is a later task.
     */
    suspend fun findReferences(file: SourceFile, name: String): ForgeResult<List<CodeReference>, ForgeError>

    /** Innermost symbol containing [line]/[column], for "symbol at cursor". */
    suspend fun symbolAt(file: SourceFile, line: Int, column: Int): ForgeResult<CodeSymbol?, ForgeError>
}
