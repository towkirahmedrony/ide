package com.agentx.app.model.ratelimit

import com.agentx.app.model.ModelConfig
import com.agentx.app.model.ModelGenerationSettings
import com.agentx.app.model.ModelMessage
import com.agentx.app.model.ModelRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TokenEstimatorTest {

    private val estimator = HeuristicTokenEstimator(defaultOutputTokens = 256, messageOverheadTokens = 4)

    private fun config(maxOutputTokens: Int? = null) = ModelConfig(
        providerId = "groq",
        baseUrl = "https://api.groq.com/openai/v1",
        model = "llama-3.3-70b-versatile",
        generation = ModelGenerationSettings(maxOutputTokens = maxOutputTokens),
    )

    @Test
    fun `input tokens grow with message content`() {
        val short = estimator.estimateInputTokens(
            ModelRequest(config(), listOf(ModelMessage.user("hi"))),
        )
        val long = estimator.estimateInputTokens(
            ModelRequest(config(), listOf(ModelMessage.user("hi".repeat(100)))),
        )

        assertTrue(long > short, "expected $long > $short")
    }

    @Test
    fun `output budget uses maxOutputTokens when set`() {
        val request = ModelRequest(config(maxOutputTokens = 64), listOf(ModelMessage.user("hello")))

        assertEquals(64, estimator.estimateOutputTokens(request))
    }

    @Test
    fun `output budget falls back to the default when unset`() {
        val request = ModelRequest(config(), listOf(ModelMessage.user("hello")))

        assertEquals(256, estimator.estimateOutputTokens(request))
    }

    @Test
    fun `reservation is input plus expected output`() {
        val request = ModelRequest(config(maxOutputTokens = 100), listOf(ModelMessage.user("hello")))

        assertEquals(
            estimator.estimateInputTokens(request).toLong() + 100L,
            estimator.estimateReservation(request),
        )
    }

    @Test
    fun `an empty prompt still reserves at least one token`() {
        val request = ModelRequest(config(), listOf(ModelMessage.system("")))

        assertTrue(estimator.estimateInputTokens(request) >= 1)
    }
}
