package com.agentx.app.codeintel.android

import com.agentx.app.codeintel.CodeLanguage
import com.agentx.app.codeintel.SyntaxParser
import com.agentx.app.codeintel.SyntaxParserProvider
import com.itsaky.androidide.treesitter.TSLanguage
import com.itsaky.androidide.treesitter.java.TSLanguageJava
import com.itsaky.androidide.treesitter.json.TSLanguageJson
import com.itsaky.androidide.treesitter.kotlin.TSLanguageKotlin
import com.itsaky.androidide.treesitter.python.TSLanguagePython
import com.itsaky.androidide.treesitter.xml.TSLanguageXml
import java.util.concurrent.ConcurrentHashMap

/**
 * The grammars this build actually ships.
 *
 * One entry per `tree-sitter-<language>` artifact in `build.gradle.kts`. Each
 * loader is a lambda so nothing is touched until a language is really needed:
 * a grammar's shared library is loaded by the first call, not at boot, so the
 * IDE does not pay for a language the user never opens.
 *
 * The list is deliberately short. Every additional grammar packages native
 * libraries for every Android ABI inside the APK, so a language is added here
 * only when its parser is genuinely wanted — and a language that is *detected*
 * but absent here is reported as "no parser available" instead of as an empty
 * outline.
 */
object TreeSitterGrammars {

    /** Code language to its lazily loaded native grammar handle. */
    fun loaders(): Map<CodeLanguage, () -> TSLanguage> = linkedMapOf(
        CodeLanguage.KOTLIN to { TSLanguageKotlin.getInstance() },
        CodeLanguage.JAVA to { TSLanguageJava.getInstance() },
        CodeLanguage.PYTHON to { TSLanguagePython.getInstance() },
        CodeLanguage.JSON to { TSLanguageJson.getInstance() },
        CodeLanguage.XML to { TSLanguageXml.getInstance() },
    )
}

/**
 * Tree-sitter [SyntaxParserProvider] for Android.
 *
 * Loading a grammar can fail for reasons that have nothing to do with the
 * source file (an ABI without packaged libraries, a stripped build, a device
 * where the library cannot be mapped). A failure is therefore remembered and
 * reported as "this build cannot parse that language", never as an empty file,
 * and never retried on every keystroke.
 *
 * Parsers are created per request by [parserFor] and closed by their caller:
 * a native parser instance is not safe to share between threads, and creating
 * one is cheap compared to loading the grammar.
 */
class TreeSitterParserProvider(
    private val loaders: Map<CodeLanguage, () -> TSLanguage> = TreeSitterGrammars.loaders(),
    /** Hard per-file parse budget; the native parser halts when it is exceeded. */
    private val parseTimeoutMillis: Long = TreeSitterSyntaxParser.DEFAULT_TIMEOUT_MILLIS,
) : SyntaxParserProvider {

    private val grammars = ConcurrentHashMap<CodeLanguage, TSLanguage>()
    private val unavailable = ConcurrentHashMap.newKeySet<CodeLanguage>()
    private val unavailableReasons = ConcurrentHashMap<CodeLanguage, String>()

    override fun supportedLanguages(): Set<CodeLanguage> =
        loaders.keys.filterTo(LinkedHashSet()) { language -> grammarFor(language) != null }

    override fun parserFor(language: CodeLanguage): SyntaxParser? =
        grammarFor(language)?.let { grammar -> TreeSitterSyntaxParser(language, grammar, parseTimeoutMillis) }

    /** The loaded grammar for [language], loading it on first use. */
    fun grammarFor(language: CodeLanguage): TSLanguage? {
        grammars[language]?.let { return it }
        if (language in unavailable) return null
        val loader = loaders[language] ?: return null
        return try {
            val grammar = loader()
            grammars[language] = grammar
            grammar
        } catch (error: Throwable) {
            // UnsatisfiedLinkError / NoClassDefFoundError / anything the native
            // layer throws: the language is simply not parseable on this device.
            unavailable += language
            unavailableReasons[language] = error.message ?: error::class.java.simpleName.orEmpty()
            null
        }
    }

    /** Why [language] cannot be parsed here, when it failed to load. */
    fun unavailableReason(language: CodeLanguage): String? = unavailableReasons[language]
}
