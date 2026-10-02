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

    val LOCAL: List<ModelCapabilityProfile> = listOf(
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
        GEMINI + GROQ + LOCAL + ADDITIONAL_PROVIDERS

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
