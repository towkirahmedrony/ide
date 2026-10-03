package com.agentx.app.model.manager

import com.agentx.app.core.logging.ForgeLogger
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.discovery.ModelDiscovery
import com.agentx.app.model.http.HttpTransport
import com.agentx.app.model.http.UrlConnectionHttpTransport
import com.agentx.app.model.preset.ModelApiProtocol
import com.agentx.app.model.preset.ModelProviderIds
import com.agentx.app.model.provider.gemini.GeminiModelProvider
import com.agentx.app.model.provider.openai.OpenAiCompatibleProvider

/**
 * Chooses the provider that owns model discovery for a connected provider
 * identity.
 *
 * This is the same mapping [DefaultModelProviderFactory] uses to pick the provider
 * that serves chat, so a provider is never listed through a protocol it does not
 * speak: Gemini is a native API with its own model-list path and key header, while
 * Groq and a local OpenAI-compatible runtime both use the compatible `/models`
 * route. The connection is bound into the returned callable, so the provider
 * itself stays stateless and discovery never needs a second configuration.
 *
 * A provider identity this build does not drive returns null, which the catalog
 * factory reports as "no catalog for this provider" rather than as an empty one.
 */
class DefaultModelDiscoverySource(
    private val transport: HttpTransport = UrlConnectionHttpTransport(),
    /**
     * Optional structured Developer Log sink, so a discovery that fails is
     * visible in the same `[PROVIDER][DISCOVERY]` trail as the rest of the runtime.
     */
    private val logger: ForgeLogger? = null,
) {

    fun create(providerId: String, connection: ModelConfig): ModelDiscovery? = when (providerId) {
        ModelProviderIds.GEMINI -> ModelDiscovery {
            GeminiModelProvider(id = providerId, transport = transport, logger = logger)
                .discoverModels(connection)
        }

        ModelProviderIds.GROQ,
        ModelProviderIds.OPENAI_COMPATIBLE,
        -> ModelDiscovery {
            OpenAiCompatibleProvider(
                id = providerId,
                transport = transport,
                chatPath = ModelApiProtocol.OPENAI_COMPATIBLE.chatPath,
                logger = logger,
            ).discoverModels(connection)
        }

        else -> null
    }
}
