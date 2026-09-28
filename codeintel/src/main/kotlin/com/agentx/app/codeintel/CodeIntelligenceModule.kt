package com.agentx.app.codeintel

import com.agentx.app.core.architecture.LayerDescriptor
import com.agentx.app.core.architecture.LayerStatus
import com.agentx.app.core.foundation.ServiceKeys
import com.agentx.app.core.module.ForgeModule
import com.agentx.app.core.module.ModuleContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** The code intelligence layer, as shown by the architecture/health screens. */
val CODE_INTELLIGENCE_LAYER = LayerDescriptor(
    id = "codeintel",
    title = "Code Intelligence",
    summary = "Turns source files into syntax trees, symbols and outlines for the editor, " +
        "the Context Engine and the Tool System.",
    status = LayerStatus.ACTIVE,
)

/**
 * Registers code intelligence with the platform.
 *
 * The engine is created here and the *parser backend* is registered as a
 * bindable service, exactly like the workspace resolver and the connection
 * authorizer: the domain module is set up at boot without knowing anything about
 * Android, and the `:codeintel-android` module attaches the tree-sitter provider
 * once the native libraries are loadable. Until that happens the engine answers
 * "no parser available", so a missing backend degrades visibly instead of
 * producing empty outlines.
 */
class CodeIntelligenceModule(
    private val parsers: SyntaxParserProvider = DelegatingSyntaxParserProvider(),
    private val limits: CodeIntelligenceLimits = CodeIntelligenceLimits.DEFAULT,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ForgeModule {

    /**
     * The engine itself, so the composition root can hand it to the tools and to
     * the Context Engine without a second lookup or a duplicate instance.
     */
    val engine: CodeIntelligence = DefaultCodeIntelligence(
        parsers = parsers,
        limits = limits,
        dispatcher = dispatcher,
    )

    override val id: String = "codeintel"

    override fun initialize(context: ModuleContext) {
        context.services.register(ServiceKeys.CODE_INTELLIGENCE, engine)
        // Registered as itself so the Android layer can bind the backend later.
        context.services.register(ServiceKeys.CODE_INTELLIGENCE_PARSERS, parsers)
    }
}
