package com.agentx.app.context

/**
 * Derives the input-context budget from the capacity of the model that will
 * actually read it.
 *
 * The problem this exists to fix: the context engine's ceiling was one global
 * character count ([ContextBudget.DEFAULT_MAX_TOTAL_CHARS]) applied to every
 * role and every model. A local Qwen with an 8k window and a remote model with a
 * 200k window were handed the same 48,000-character allowance, so the small model
 * could be overflowed while the large one was left mostly unused.
 *
 * The budget is therefore computed *from the selected model*:
 *
 * ```text
 * window − fixed prompt overhead − reserved output − safety margin = input allowance
 * ```
 *
 * - **Fixed overhead is measured, not assumed.** The caller passes the real size of
 *   the system/role prompt, the user prompt and the tool schemas, because tool
 *   definitions are sent on every call and are not free.
 * - **Output is reserved before input.** A model that must be able to write a full
 *   patch cannot spend its whole window reading.
 * - **The safety margin absorbs estimation error.** Character counts are converted
 *   with a conservative ratio, never treated as exact tokens.
 * - **An unknown window is not unlimited.** It becomes
 *   [UNKNOWN_CONTEXT_WINDOW_TOKENS], the conservative default, and the result says
 *   so through [ModelContextBudgetReport.windowKnown] so the choice is visible in
 *   logs and tests instead of being an invisible guess.
 *
 * Nothing here knows a model id, a provider or a role: it reads descriptor metadata
 * only, so it cannot drift as models are added.
 */
object ModelContextBudget {

    /** Conservative characters-per-token used for every conversion here. */
    const val CHARS_PER_TOKEN: Int = 4

    /**
     * The window assumed when a model does not declare one.
     *
     * Deliberately modest. Treating an unknown window as unlimited is the failure
     * mode this whole class exists to prevent: the request would be built for a
     * capacity we never confirmed and would fail at the provider instead of being
     * budgeted here. 8,192 tokens is the smallest window any supported chat model is
     * likely to have, so an un-declared model is budgeted as if it were small.
     */
    const val UNKNOWN_CONTEXT_WINDOW_TOKENS: Int = 8_192

    /** A window below this is unusable for agent work; used as a floor, never a target. */
    const val MIN_CONTEXT_WINDOW_TOKENS: Int = 1_024

    /** Output tokens reserved when the model declares no output limit. */
    const val DEFAULT_OUTPUT_RESERVATION_TOKENS: Int = 4_096

    /**
     * Never reserve more than this share of the window for output.
     *
     * On a small model a flat 4,096-token reservation would eat most of the window
     * and starve the input the model needs to reason about the task at all.
     */
    const val MAX_OUTPUT_RESERVATION_RATIO: Double = 0.5

    /** Share of the remaining window held back for estimate error. */
    const val SAFETY_MARGIN_RATIO: Double = 0.10

    /** Smallest input allowance that is still worth sending. */
    const val MIN_INPUT_CHARS: Int = 2_000

    /** Floors for the per-source dimensions when they are scaled down. */
    private const val FILE_FLOOR_CHARS = 1_000
    private const val TOOL_RESULT_FLOOR_CHARS = 500
    private const val CONVERSATION_FLOOR_CHARS = 1_000
    private const val SKILL_FLOOR_CHARS = 1_000
    private const val DESIGN_FLOOR_CHARS = 800
    private const val PLATFORM_FLOOR_CHARS = 800

    /** The arithmetic behind one budget, kept so a run can be explained after the fact. */
    data class ModelContextBudgetReport(
        /** The window used, after the unknown/floor rules were applied. */
        val windowTokens: Int,
        /** False when [windowTokens] is the conservative default rather than a declared value. */
        val windowKnown: Boolean,
        /** Output tokens held back from the window. */
        val reservedOutputTokens: Int,
        /** Measured characters of the fixed prompt and tool schemas. */
        val overheadChars: Int,
        /** [overheadChars] expressed in tokens. */
        val overheadTokens: Int,
        /** Tokens held back for estimate error. */
        val safetyMarginTokens: Int,
        /** What is left for context items, in tokens. */
        val availableInputTokens: Int,
        /** The resulting character ceiling. */
        val inputCharBudget: Int,
        val charsPerToken: Int,
    )

    /** A derived budget together with the arithmetic that produced it. */
    data class Plan(
        val budget: ContextBudget,
        val report: ModelContextBudgetReport,
    )

    /**
     * The budget for a model with [windowTokens] capacity.
     *
     * [windowTokens] and [maxOutputTokens] come from the selected model's descriptor
     * (a capability profile or a catalog entry); null means "not declared" and is
     * handled explicitly, never as infinity.
     *
     * [overheadChars] is the measured size of everything that is *not* ranked
     * context: the system/role prompt, the user prompt and the tool schemas.
     * [base] supplies the non-capacity knobs (item counts, per-source ceilings) so
     * this class stays a scaling policy rather than a second context engine.
     */
    fun forModel(
        windowTokens: Int?,
        maxOutputTokens: Int? = null,
        overheadChars: Int = 0,
        base: ContextBudget = ContextBudget.DEFAULT,
        charsPerToken: Int = CHARS_PER_TOKEN,
        /**
         * The ceiling [base]'s own total is expressed against — the global default by
         * default. A base below it is a statement that this caller wants a *share* of
         * the model's capacity (a role profile does exactly that), and that share is
         * preserved rather than flattened to the model ceiling.
         */
        referenceChars: Int = ContextBudget.DEFAULT_MAX_TOTAL_CHARS,
    ): Plan {
        require(charsPerToken > 0) { "charsPerToken must be positive, was $charsPerToken" }

        val declared = windowTokens?.takeIf { it > 0 }
        val window = (declared ?: UNKNOWN_CONTEXT_WINDOW_TOKENS).coerceAtLeast(MIN_CONTEXT_WINDOW_TOKENS)

        // Output first: a model that cannot finish its answer is worse than one that
        // read less, and the reservation is capped so a small window is not consumed
        // entirely by the allowance for writing.
        val declaredOutput = maxOutputTokens?.takeIf { it > 0 }
        val reservedOutput = minOf(
            declaredOutput ?: DEFAULT_OUTPUT_RESERVATION_TOKENS,
            (window * MAX_OUTPUT_RESERVATION_RATIO).toInt().coerceAtLeast(1),
        )

        val safeOverheadChars = overheadChars.coerceAtLeast(0)
        val overheadTokens = tokensFor(safeOverheadChars, charsPerToken)
        val fixed = overheadTokens + reservedOutput
        val remaining = (window - fixed).coerceAtLeast(0)
        val safetyMargin = (remaining * SAFETY_MARGIN_RATIO).toInt()
        val availableInput = (remaining - safetyMargin).coerceAtLeast(0)

        val windowChars = window * charsPerToken
        val allowance = (availableInput * charsPerToken)
            .coerceAtLeast(MIN_INPUT_CHARS)
            .coerceAtMost(windowChars)
            .coerceAtLeast(1)

        return Plan(
            budget = scale(base, allowance, charsPerToken, referenceChars),
            report = ModelContextBudgetReport(
                windowTokens = window,
                windowKnown = declared != null,
                reservedOutputTokens = reservedOutput,
                overheadChars = safeOverheadChars,
                overheadTokens = overheadTokens,
                safetyMarginTokens = safetyMargin,
                availableInputTokens = availableInput,
                inputCharBudget = allowance,
                charsPerToken = charsPerToken,
            ),
        )
    }

    /**
     * Applies the capacity ceiling to [base].
     *
     * The per-source ceilings only ever shrink. A roomier model gets a larger total
     * ceiling, but not wider file or conversation windows: those exist to stop a
     * repository dump crowding out the task, which is a quality decision that has
     * nothing to do with how many tokens the model can hold. A tighter model shrinks
     * them proportionally so one file can never be allowed to exceed the whole
     * window.
     */
    private fun scale(base: ContextBudget, allowance: Int, charsPerToken: Int, referenceChars: Int): ContextBudget {
        // The base total is a relative statement — "a share of the ceiling" — so the
        // model's allowance is multiplied by that share. Replacing the total outright
        // would make every role identical under a roomy model, discarding the role
        // profiles entirely.
        val share = if (referenceChars <= 0) 1.0 else (base.maxTotalChars.toDouble() / referenceChars).coerceAtMost(1.0)
        val ceiling = (allowance * share).toInt().coerceIn(1, allowance)
        val shrink = if (base.maxTotalChars <= 0) 1.0 else (ceiling.toDouble() / base.maxTotalChars).coerceAtMost(1.0)
        return base.copy(
            maxTotalChars = ceiling,
            charsPerToken = charsPerToken,
            // The char ceiling now expresses the model budget, so a stale token
            // ceiling must not silently re-clamp it.
            maxTotalTokens = null,
            maxFileChars = shrink(base.maxFileChars, shrink, FILE_FLOOR_CHARS),
            maxToolResultChars = shrink(base.maxToolResultChars, shrink, TOOL_RESULT_FLOOR_CHARS),
            maxConversationChars = shrink(base.maxConversationChars, shrink, CONVERSATION_FLOOR_CHARS),
            maxSkillTotalChars = shrink(base.maxSkillTotalChars, shrink, SKILL_FLOOR_CHARS),
            // Scaled like the others so a small model still receives a usable
            // statement of design intent instead of none: a ceiling wider than the
            // whole window would not truncate the file, it would exclude it.
            maxDesignChars = shrink(base.maxDesignChars, shrink, DESIGN_FLOOR_CHARS),
            // Same reasoning as the design ceiling: a profile wider than the whole
            // window would be dropped rather than shortened, so it scales too.
            maxPlatformChars = shrink(base.maxPlatformChars, shrink, PLATFORM_FLOOR_CHARS),
        )
    }

    private fun shrink(value: Int, ratio: Double, floor: Int): Int =
        maxOf(minOf(floor, value), (value * ratio).toInt())

    private fun tokensFor(chars: Int, charsPerToken: Int): Int =
        if (chars <= 0) 0 else (chars + charsPerToken - 1) / charsPerToken
}
