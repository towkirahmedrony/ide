package com.agentx.app.model.preset

/**
 * The provider identities the model runtime distinguishes.
 *
 * A provider identity is only a key: it says *which* connection a
 * [com.agentx.app.model.ModelConfig] targets so the Model Gateway can route a
 * request to the right provider instance. Multiple connections may be registered
 * at the same time, one per identity.
 *
 * The transport is deliberately not part of the identity. Gemini and Groq are
 * distinct services with separate endpoints and credentials that must be able to
 * coexist, so each gets its own identity even though their requests differ:
 * Gemini speaks its own API, Groq the OpenAI-compatible one.
 */
object ModelProviderIds {

    /** Google Gemini, reached through its own API (not the compatible surface). */
    const val GEMINI: String = "gemini"

    /** Groq (OpenAI-compatible surface). */
    const val GROQ: String = "groq"

    /**
     * Any other OpenAI-compatible endpoint: a local runtime (llama.cpp, vLLM,
     * Ollama's OpenAI surface), a Colab/tunnel endpoint, or a custom server.
     */
    const val OPENAI_COMPATIBLE: String = "openai-compatible"

    /**
     * The identity a preset connects as. Gemini and Groq presets are separate
     * identities; everything else follows its wire protocol, which keeps a plain
     * OpenAI-compatible or Ollama connection on [OPENAI_COMPATIBLE].
     */
    fun forPreset(setupKind: String, protocol: ModelApiProtocol): String =
        when (setupKind.trim().lowercase()) {
            GEMINI -> GEMINI
            GROQ -> GROQ
            else -> protocol.providerId
        }
}
