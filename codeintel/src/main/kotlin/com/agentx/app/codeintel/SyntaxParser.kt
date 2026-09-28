package com.agentx.app.codeintel

/**
 * One parse request.
 *
 * [isCancelled] is polled by backends that can stop early or hand the signal to
 * a native parser. It is a plain lambda so a parser never sees a coroutine.
 */
data class ParseRequest(
    val path: String,
    val language: CodeLanguage,
    val content: String,
    val isCancelled: () -> Boolean = { false },
)

/** What a parser produced. */
sealed interface SyntaxParseResult {

    data class Success(val tree: SyntaxTree) : SyntaxParseResult

    /** No grammar is available for this language in this build. */
    data class Unsupported(val language: CodeLanguage) : SyntaxParseResult

    /**
     * The parser itself failed (missing native library, internal error). A
     * *syntax* error in the source is not a failure: it is a [Success] whose
     * tree contains error nodes.
     */
    data class Failed(val reason: String, val cause: Throwable? = null) : SyntaxParseResult
}

/**
 * A parser backend.
 *
 * Implementations must:
 * - be safe to call from a background thread and never touch the main thread;
 * - return [SyntaxParseResult.Success] for malformed source (an error node is a
 *   valid tree) and only fail when the parser itself cannot run;
 * - never throw for bad input — the engine treats a throwable as [Failed];
 * - reuse whatever the backend allows reusing between calls.
 */
interface SyntaxParser {
    val language: CodeLanguage

    fun parse(request: ParseRequest): SyntaxParseResult
}

/**
 * Supplies parsers and outlives a single parse, so a backend can keep its
 * initialized grammars.
 *
 * [supportedLanguages] is the honest answer to "what can this build parse", and
 * it is what the UI and the tools report; a language that is merely detected
 * must not appear here.
 */
interface SyntaxParserProvider {
    fun supportedLanguages(): Set<CodeLanguage>

    fun parserFor(language: CodeLanguage): SyntaxParser?
}

/** No backend: every request reports unsupported instead of faking a tree. */
object NoSyntaxParsers : SyntaxParserProvider {
    override fun supportedLanguages(): Set<CodeLanguage> = emptySet()

    override fun parserFor(language: CodeLanguage): SyntaxParser? = null
}

/**
 * Provider whose delegate is bound after boot.
 *
 * This is the same pattern the platform already uses for the workspace resolver
 * and the connection authorizer: the domain module is registered at boot, and
 * the Android layer attaches the real implementation once the platform objects
 * exist. Until then, nothing is parseable — which the engine reports as
 * "no parser available" rather than as an empty file.
 */
class DelegatingSyntaxParserProvider(
    initial: SyntaxParserProvider? = null,
) : SyntaxParserProvider {

    @Volatile
    private var delegate: SyntaxParserProvider? = initial

    fun bind(provider: SyntaxParserProvider) {
        delegate = provider
    }

    /** True once a real backend has been attached. */
    val isBound: Boolean get() = delegate != null

    override fun supportedLanguages(): Set<CodeLanguage> = delegate?.supportedLanguages().orEmpty()

    override fun parserFor(language: CodeLanguage): SyntaxParser? = delegate?.parserFor(language)
}
