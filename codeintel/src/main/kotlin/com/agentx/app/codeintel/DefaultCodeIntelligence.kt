package com.agentx.app.codeintel

import com.agentx.app.core.ForgeError
import com.agentx.app.core.ForgeErrorCode
import com.agentx.app.core.ForgeResult
import com.agentx.app.core.failure
import com.agentx.app.core.success
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * The code-intelligence engine: detection, parsing, symbols, outline, lookups.
 *
 * Design notes that matter for callers:
 *
 * - **Off the main thread.** Every parse runs on [dispatcher] (never the main
 *   thread) and the coroutine's cancellation reaches the parser through
 *   [ParseRequest.isCancelled], so navigating away or typing again abandons work
 *   instead of queuing it.
 * - **Caching, not indexing.** A file is analysed once per content version and
 *   reused for every later request (editor re-render, symbol-at-cursor, agent
 *   context) until its text changes. Nothing here scans the repository.
 * - **Partial results are normal.** Malformed source parses into a tree with
 *   error nodes; that is a success with `hasSyntaxErrors = true`, not a failure.
 *   Failures are reserved for "this build cannot do this at all": unknown
 *   language, no grammar, oversized file, parser crash.
 * - **Honest about capability.** A detected language with no parser is reported
 *   as [ForgeErrorCode.CODE_INTEL_PARSER_UNAVAILABLE] rather than as an empty
 *   outline, so the UI can say "plain text" instead of "no symbols".
 */
class DefaultCodeIntelligence(
    private val parsers: SyntaxParserProvider = NoSyntaxParsers,
    private val limits: CodeIntelligenceLimits = CodeIntelligenceLimits.DEFAULT,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : CodeIntelligence {

    private val extractor = SymbolExtractor(limits)
    private val cache = ParseCache(limits.cacheEntries)

    override fun detectLanguage(path: String): CodeLanguage = LanguageDetector.detect(path)

    override fun parsedLanguages(): Set<CodeLanguage> = parsers.supportedLanguages()

    override suspend fun parseFile(file: SourceFile): ForgeResult<ParsedFile, ForgeError> {
        val language = file.language ?: LanguageDetector.detect(file.path)

        if (language == CodeLanguage.UNKNOWN) {
            return failure(
                codeIntelFailure(
                    code = ForgeErrorCode.CODE_INTEL_UNSUPPORTED_LANGUAGE,
                    message = "${LanguageDetector.fileNameOf(file.path) ?: file.path} has no recognised language.",
                    details = mapOf("path" to file.path, "reason" to "unknown-language"),
                ),
            )
        }

        if (file.content.length > limits.maxFileChars) {
            return failure(
                codeIntelFailure(
                    code = ForgeErrorCode.CODE_INTEL_FILE_TOO_LARGE,
                    message = "${LanguageDetector.fileNameOf(file.path) ?: file.path} is too large to analyse " +
                        "(${file.content.length} characters, limit ${limits.maxFileChars}).",
                    details = mapOf(
                        "path" to file.path,
                        "language" to language.displayName,
                        "chars" to file.content.length,
                        "limit" to limits.maxFileChars,
                    ),
                ),
            )
        }

        val key = ParseCache.key(file.path, language, ParseCache.hashOf(file.content))
        cache.get(key)?.let { return success(it) }

        // An empty file has no structure and needs no parser.
        if (file.content.isBlank()) {
            val parsed = emptyResult(file.path, language)
            cache.put(key, parsed)
            return success(parsed)
        }

        val parser = parsers.parserFor(language)
            ?: return failure(
                codeIntelFailure(
                    code = ForgeErrorCode.CODE_INTEL_PARSER_UNAVAILABLE,
                    message = "No ${language.displayName} parser is available in this build.",
                    details = mapOf(
                        "path" to file.path,
                        "language" to language.displayName,
                        "reason" to "no-parser",
                    ),
                ),
            )

        return withContext(dispatcher) {
            val job = coroutineContext[Job]
            val request = ParseRequest(
                path = file.path,
                language = language,
                content = file.content,
                isCancelled = { job?.isActive == false },
            )

            val outcome = try {
                parser.parse(request)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                // A parser must not throw, but the engine never trusts one to
                // keep that promise: a broken native library becomes a typed
                // failure instead of taking the app down.
                SyntaxParseResult.Failed(error.message ?: error::class.simpleName.orEmpty(), error)
            }

            // Cancellation is only honoured once the (possibly native) call
            // returns; re-checking here keeps a cancelled request from being
            // cached or handed back as a result.
            coroutineContext.ensureActive()

            when (outcome) {
                is SyntaxParseResult.Unsupported -> failure(
                    codeIntelFailure(
                        code = ForgeErrorCode.CODE_INTEL_PARSER_UNAVAILABLE,
                        message = "No ${language.displayName} parser is available in this build.",
                        details = mapOf(
                            "path" to file.path,
                            "language" to language.displayName,
                            "reason" to "unsupported-by-parser",
                        ),
                    ),
                )

                is SyntaxParseResult.Failed -> failure(
                    codeIntelFailure(
                        code = ForgeErrorCode.CODE_INTEL_PARSE_FAILED,
                        message = "${LanguageDetector.fileNameOf(file.path) ?: file.path} could not be parsed: ${outcome.reason}",
                        details = mapOf("path" to file.path, "language" to language.displayName),
                        cause = outcome.cause,
                    ),
                )

                is SyntaxParseResult.Success -> {
                    val parsed = buildResult(file.path, language, outcome.tree)
                    cache.put(key, parsed)
                    success(parsed)
                }
            }
        }
    }

    override suspend fun getSyntaxTree(file: SourceFile): ForgeResult<SyntaxTree, ForgeError> =
        parseFile(file).mapSuccess { parsed -> parsed.tree ?: SyntaxTrees.empty(parsed.language, parsed.path) }

    override suspend fun getSymbols(file: SourceFile): ForgeResult<List<CodeSymbol>, ForgeError> =
        parseFile(file).mapSuccess { parsed -> parsed.symbols }

    override suspend fun getOutline(file: SourceFile): ForgeResult<FileOutline, ForgeError> =
        parseFile(file).mapSuccess { parsed -> parsed.outline }

    override suspend fun findDefinitions(
        file: SourceFile,
        name: String,
    ): ForgeResult<List<CodeSymbol>, ForgeError> {
        val needle = name.trim()
        if (needle.isEmpty()) return success(emptyList())
        return parseFile(file).mapSuccess { parsed ->
            parsed.symbols.filter { symbol -> symbol.name == needle || symbol.qualifiedName == needle }
        }
    }

    override suspend fun findReferences(
        file: SourceFile,
        name: String,
    ): ForgeResult<List<CodeReference>, ForgeError> {
        val needle = name.trim()
        if (needle.isEmpty()) return success(emptyList())

        return parseFile(file).mapSuccess { parsed ->
            val tree = parsed.tree ?: return@mapSuccess emptyList<CodeReference>()
            val definitionStarts = parsed.symbols
                .filter { symbol -> symbol.name == needle || symbol.qualifiedName == needle }
                .mapNotNull { symbol -> symbol.nameRange?.start }
                .toSet()

            // Leaf nodes only, so a declaration node and its identifier child
            // are not reported twice for the same occurrence. Scope is this
            // file: cross-file references need a workspace index (later task).
            tree.root.descendants()
                .filter { node -> node.childCount == 0 && node.isNamed && node.text == needle }
                .map { node ->
                    CodeReference(
                        name = needle,
                        range = node.span(),
                        isDefinition = node.start in definitionStarts,
                    )
                }
                .take(limits.maxReferences)
                .toList()
                .sortedBy { reference -> reference.range.start }
        }
    }

    override suspend fun symbolAt(
        file: SourceFile,
        line: Int,
        column: Int,
    ): ForgeResult<CodeSymbol?, ForgeError> {
        if (line < 1 || column < 1) return success(null)
        val position = SourcePosition(line, column)
        return parseFile(file).mapSuccess { parsed -> parsed.outline.symbolAt(position) }
    }

    /** Drops the working set. Called when the workspace changes under the editor. */
    fun clearCache() = cache.clear()

    private fun buildResult(path: String, language: CodeLanguage, tree: SyntaxTree): ParsedFile {
        val structure = extractor.extract(tree, path)
        return ParsedFile(
            path = path,
            language = language,
            tree = tree,
            symbols = structure.symbols,
            outline = FileOutline(
                path = path,
                language = language,
                roots = structure.roots,
                symbolCount = structure.symbols.size,
                truncated = structure.truncated,
                hasSyntaxErrors = tree.hasErrors,
            ),
            hasSyntaxErrors = tree.hasErrors,
            isEmpty = false,
        )
    }

    private fun emptyResult(path: String, language: CodeLanguage): ParsedFile = ParsedFile(
        path = path,
        language = language,
        tree = null,
        symbols = emptyList(),
        outline = FileOutline(
            path = path,
            language = language,
            roots = emptyList(),
            symbolCount = 0,
            hasSyntaxErrors = false,
        ),
        hasSyntaxErrors = false,
        isEmpty = true,
    )
}

/** Maps the value of a success, passes a failure through untouched. */
private inline fun <T, R> ForgeResult<T, ForgeError>.mapSuccess(transform: (T) -> R): ForgeResult<R, ForgeError> =
    when (this) {
        is ForgeResult.Success -> success(transform(value))
        is ForgeResult.Failure -> failure(error)
    }
