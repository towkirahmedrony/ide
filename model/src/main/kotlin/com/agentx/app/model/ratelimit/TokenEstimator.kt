package com.agentx.app.model.ratelimit

import com.agentx.app.model.ModelRequest
import kotlin.math.ceil

/**
 * Estimates the tokens a request will consume so admission control can reserve
 * against a TPM limit before the provider reports exact usage.
 *
 * The estimate is never presented as actual usage: [UsageEntry.estimated] records
 * that a number is approximate, and the reservation is reconciled with the
 * provider's real usage when it arrives.
 */
interface TokenEstimator {
    /** Estimated input tokens: the full prompt the model will read. */
    fun estimateInputTokens(request: ModelRequest): Int

    /** Expected output-token budget for the request. */
    fun estimateOutputTokens(request: ModelRequest): Int

    /** Total to reserve up front. */
    fun estimateReservation(request: ModelRequest): Long =
        estimateInputTokens(request).toLong() + estimateOutputTokens(request).toLong()
}

/**
 * Dependency-free heuristic: roughly four characters per token, plus a fixed
 * overhead per message for role/formatting tokens. It is deliberately
 * conservative (over- rather than under- estimating) so a TPM limit is not
 * exceeded because of rounding.
 */
class HeuristicTokenEstimator(
    /** Output budget used when the request does not set `maxOutputTokens`. */
    private val defaultOutputTokens: Int = DEFAULT_OUTPUT_TOKENS,
    /** Per-message structural overhead. */
    private val messageOverheadTokens: Int = MESSAGE_OVERHEAD_TOKENS,
) : TokenEstimator {

    init {
        require(defaultOutputTokens > 0) { "defaultOutputTokens must be positive" }
        require(messageOverheadTokens >= 0) { "messageOverheadTokens must not be negative" }
    }

    override fun estimateInputTokens(request: ModelRequest): Int {
        var total = 0L
        request.messages.forEach { message ->
            total += messageOverheadTokens.toLong()
            total += estimateText(message.content)
            message.toolCalls.forEach { call ->
                total += estimateText(call.name)
                total += estimateText(call.arguments.keys.joinToString(" "))
            }
        }
        // Tool schemas are sent on every call and consume prompt tokens too.
        request.tools.forEach { spec ->
            total += messageOverheadTokens.toLong()
            total += estimateText(spec.name)
            total += estimateText(spec.description)
            spec.parameters.forEach { parameter ->
                total += estimateText(parameter.name)
                total += estimateText(parameter.description)
            }
        }
        return total.coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    override fun estimateOutputTokens(request: ModelRequest): Int =
        request.effectiveGeneration.maxOutputTokens?.takeIf { it > 0 } ?: defaultOutputTokens

    private fun estimateText(text: String): Long {
        if (text.isEmpty()) return 0L
        return ceil(text.length / CHARS_PER_TOKEN).toLong()
    }

    companion object {
        const val CHARS_PER_TOKEN: Double = 4.0
        const val MESSAGE_OVERHEAD_TOKENS: Int = 4
        const val DEFAULT_OUTPUT_TOKENS: Int = 512
    }
}
