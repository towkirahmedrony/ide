package com.agentx.app.model.capability

import com.agentx.app.model.preset.ModelProviderIds

/**
 * Hardcoded, authoritative capability definitions for models AgentX is
 * expected to use.
 *
 * Only capabilities that are already known from this project's configuration
 * or from the model's identity in the existing runtime are recorded as
 * [CapabilitySupport.SUPPORTED] or [UNSUPPORTED]. Additional providers
 * (Cerebras, Mistral, OpenRouter, Cloudflare Workers AI, NVIDIA NIM) are
 * registered as identities so they stay distinct; their models are not
 * invented here.
 */
object KnownModelCapabilities {

    val GEMINI: List<ModelCapabilityProfile> = listOf(
        gemini("gemini-2.5-flash", "Gemini 2.5 Flash"),
        gemini("gemini-2.5-flash-lite", "Gemini 2.5 Flash Lite"),
        gemini("gemini-3-flash-preview", "Gemini 3 Flash Preview"),
        gemini("gemini-3.1-flash-lite", "Gemini 3.1 Flash Lite"),
        gemini("gemini-3.5-flash", "Gemini 3.5 Flash"),
        gemini("gemini-3.5-flash-lite", "Gemini 3.5 Flash Lite"),
    )

    val GROQ: List<ModelCapabilityProfile> = listOf(
        groq("openai/gpt-oss-120b", "GPT OSS 120B"),
        groq("openai/gpt-oss-20b", "GPT OSS 20B"),
        groq("qwen/qwen3.8-27b", "Qwen 3.8 27B"),
        groq("llama-3.3-70b-versatile", "Llama 3.3 70B Versatile"),
        groq("llama-3.1-8b-instant", "Llama 3.1 8B Instant"),
    )

    /**
     * Models a FreeLLMAPI gateway serves, under its own provider identity
     * ([ModelProviderIds.FREELMAPI]). A FreeLLMAPI connection is never assumed
     * tool-capable, and neither is any model routed through it: the gateway speaks
     * OpenAI-compatible but can route a request to an upstream that differs in real
     * capability, so tool calling is left [CapabilitySupport.UNKNOWN] per model.
     *
     * A gateway model is therefore listed here for its identity and display name
     * only, and the Reviewer/Explorer assignments stay resolvable because those
     * roles require only text generation, not because a gateway model was assumed
     * tool-capable. Nothing is claimed merely because an id appeared in a
     * `/models` list.
     */
    val FREELMAPI: List<ModelCapabilityProfile> = listOf(
        remote("gemini-3.5-flash", "Gemini 3.5 Flash"),
        remote("gemini-2.5-flash", "Gemini 2.5 Flash"),
        remote("openai/gpt-oss-20b", "GPT OSS 20B"),
        remote("gemini-3-flash-preview", "Gemini 3 Flash Preview"),
        remote("openai/gpt-oss-120b", "GPT OSS 120B"),
        remote("openai/gpt-oss-20b", "GPT OSS 20B"),
        remote("llama-3.3-70b-versatile", "Llama 3.3 70B Versatile"),
        remote("llama-3.1-8b-instant", "Llama 3.1 8B Instant"),
        remote("qwen/qwen3.8-27b", "Qwen 3.8 27B"),
    )

    val LOCAL: List<ModelCapabilityProfile> = listOf(
        // The canonical local coding model MAIN/CODER/DEBUGGER target. Devstral
        // Small 2 is an agentic coding model whose card documents tool calling, so
        // the capability is declared per model id rather than inherited from the
        // OpenAI-compatible protocol. `local = true` marks it as an on-device/local
        // runtime for rate-limit and endpoint classification, independent of where
        // the serving endpoint happens to be reached from.
        ModelCapabilityProfile(
            providerId = ModelProviderIds.OPENAI_COMPATIBLE,
            modelId = "devstral-24b",
            displayName = "Devstral 24B",
            toolCalling = CapabilitySupport.SUPPORTED,
            streaming = CapabilitySupport.SUPPORTED,
            vision = CapabilitySupport.UNKNOWN,
            structuredOutput = CapabilitySupport.UNKNOWN,
            reasoning = CapabilitySupport.UNKNOWN,
            local = true,
            enabled = true,
        ),
        ModelCapabilityProfile(
            providerId = ModelProviderIds.OPENAI_COMPATIBLE,
            modelId = "qwen2.5-coder:14b",
            displayName = "Qwen 2.5 Coder 14B",
            toolCalling = CapabilitySupport.SUPPORTED,
            streaming = CapabilitySupport.SUPPORTED,
            vision = CapabilitySupport.UNSUPPORTED,
            structuredOutput = CapabilitySupport.SUPPORTED,
            reasoning = CapabilitySupport.UNKNOWN,
            local = true,
            enabled = true,
        ),
        ModelCapabilityProfile(
            providerId = ModelProviderIds.OPENAI_COMPATIBLE,
            modelId = "qwen2.5-coder-14b",
            displayName = "Qwen 2.5 Coder 14B",
            toolCalling = CapabilitySupport.SUPPORTED,
            streaming = CapabilitySupport.SUPPORTED,
            vision = CapabilitySupport.UNSUPPORTED,
            structuredOutput = CapabilitySupport.SUPPORTED,
            reasoning = CapabilitySupport.UNKNOWN,
            local = true,
            enabled = true,
        ),
        // Devstral Small 2 is an agentic coding model, and its own model card states
        // that it supports tool calling (mistralai/Devstral-Small-2-24B-Instruct-2512).
        // That is a *model* capability, defined per model id rather than per provider:
        // a custom OpenAI-compatible endpoint serving some other model is untouched by
        // this entry. The serving runtime still has to expose it — the model card's own
        // instructions require it (vLLM needs `--tool-call-parser mistral
        // --enable-auto-tool-choice`, and a llama.cpp build needs the tool-call template
        // changes) — so a backend that does not is corrected per configuration with the
        // explicit declaration in Settings rather than by this definition.
        ModelCapabilityProfile(
            providerId = ModelProviderIds.OPENAI_COMPATIBLE,
            modelId = "hf.co/unsloth/Devstral-Small-2-24B-Instruct-2512-GGUF:Q3_K_M",
            displayName = "Devstral Small 2 24B Instruct (Q3_K_M)",
            toolCalling = CapabilitySupport.SUPPORTED,
            streaming = CapabilitySupport.SUPPORTED,
            // Nothing is claimed beyond what the role requires and the model card
            // documents. Local GGUF serving of a vision projector is configuration
            // dependent, so it stays unknown rather than being guessed either way.
            vision = CapabilitySupport.UNKNOWN,
            structuredOutput = CapabilitySupport.UNKNOWN,
            reasoning = CapabilitySupport.UNKNOWN,
            // The endpoint a custom model is served from decides locality, not this
            // definition: `local = false` keeps the existing rate-limit and endpoint
            // classification untouched.
            local = false,
            enabled = true,
        ),
    )

    /**
     * Provider identities that must remain distinct from Gemini/Groq/local.
     * No model capabilities are invented for them in this phase.
     */
    val ADDITIONAL_PROVIDERS: List<ModelCapabilityProfile> = listOf(
        placeholder(ModelProviderIds.CEREBRAS, "cerebras"),
        placeholder(ModelProviderIds.MISTRAL, "mistral"),
        placeholder(ModelProviderIds.OPENROUTER, "openrouter"),
        placeholder(ModelProviderIds.CLOUDFLARE, "cloudflare"),
        placeholder(ModelProviderIds.NVIDIA_NIM, "nvidia-nim"),
    )

    val ALL: List<ModelCapabilityProfile> =
        GEMINI + GROQ + FREELMAPI + LOCAL + ADDITIONAL_PROVIDERS

    private fun gemini(modelId: String, displayName: String): ModelCapabilityProfile = ModelCapabilityProfile(
        providerId = ModelProviderIds.GEMINI,
        modelId = modelId,
        displayName = displayName,
        toolCalling = CapabilitySupport.SUPPORTED,
        streaming = CapabilitySupport.SUPPORTED,
        vision = CapabilitySupport.SUPPORTED,
        structuredOutput = CapabilitySupport.SUPPORTED,
        reasoning = CapabilitySupport.UNKNOWN,
        local = false,
        enabled = true,
    )

    private fun groq(modelId: String, displayName: String): ModelCapabilityProfile = ModelCapabilityProfile(
        providerId = ModelProviderIds.GROQ,
        modelId = modelId,
        displayName = displayName,
        toolCalling = CapabilitySupport.SUPPORTED,
        streaming = CapabilitySupport.SUPPORTED,
        vision = CapabilitySupport.UNSUPPORTED,
        structuredOutput = CapabilitySupport.SUPPORTED,
        reasoning = CapabilitySupport.UNKNOWN,
        local = false,
        enabled = true,
    )

    /**
     * A model reached through FreeLLMAPI.
     *
     * Tool calling is deliberately *not* declared. The gateway speaks the
     * OpenAI-compatible protocol and supports OpenAI-style tool calling, but it
     * routes a request to whatever upstream provider serves the model, and those
     * providers differ in real capability. The OpenAI-compatible wire protocol is
     * therefore not proof, and marking every gateway model tool-capable would be
     * exactly the provider-family assumption this layer forbids. Tool calling
     * stays [CapabilitySupport.UNKNOWN] until a definition or an explicit
     * per-model declaration proves it, and a role that genuinely needs tools
     * consequently will not run on an unproven gateway model.
     *
     * Streaming is the one attested property: the connection is the same SSE
     * OpenAI-compatible endpoint the runtime chats through. Vision and structured
     * output relayed through the gateway are not assumed and stay UNKNOWN.
     */
    private fun remote(modelId: String, displayName: String): ModelCapabilityProfile = ModelCapabilityProfile(
        providerId = ModelProviderIds.FREELMAPI,
        modelId = modelId,
        displayName = displayName,
        toolCalling = CapabilitySupport.UNKNOWN,
        streaming = CapabilitySupport.SUPPORTED,
        vision = CapabilitySupport.UNKNOWN,
        structuredOutput = CapabilitySupport.UNKNOWN,
        reasoning = CapabilitySupport.UNKNOWN,
        local = false,
        enabled = true,
    )

    /**
     * A provider-identity marker with no advertised model capabilities.
     * The model id is the provider id itself so [ModelCapabilityRegistry.providers]
     * reports the identity without implying any concrete model is tool-capable.
     */
    private fun placeholder(providerId: String, modelId: String): ModelCapabilityProfile = ModelCapabilityProfile(
        providerId = providerId,
        modelId = modelId,
        displayName = providerId,
        toolCalling = CapabilitySupport.UNKNOWN,
        streaming = CapabilitySupport.UNKNOWN,
        vision = CapabilitySupport.UNKNOWN,
        structuredOutput = CapabilitySupport.UNKNOWN,
        reasoning = CapabilitySupport.UNKNOWN,
        local = false,
        enabled = false,
        known = true,
    )
}
