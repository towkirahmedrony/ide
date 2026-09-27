package dev.forge.ide.model.runtime

import dev.forge.ide.core.logging.ForgeLogger
import dev.forge.ide.core.logging.ForgeLoggers
import dev.forge.ide.core.logging.LogLevel
import dev.forge.ide.model.preset.ModelCredentialResolver
import dev.forge.ide.model.preset.ModelPreset
import dev.forge.ide.model.preset.ModelProviderType
import kotlinx.coroutines.delay

/**
 * Runner for models the app does not own the runtime of: a phone-local server
 * started by another app, a machine on the network, or any other endpoint the
 * user already runs.
 *
 * There is no runtime to start, so the work is discovery, validation, health
 * checking and connecting. This is the shape every "future non-Colab model"
 * needs, and it shares the entire lifecycle implementation with [ColabRunner].
 */
class HostedEndpointRunner(
    discovery: ModelEndpointDiscovery,
    credentials: ModelCredentialResolver,
    policy: ModelConnectionPolicy = ModelConnectionPolicy(),
    healthChecker: ModelHealthChecker = HttpModelHealthChecker(),
    clock: () -> Long = System::currentTimeMillis,
    logger: ForgeLogger = ForgeLoggers.create(
        level = LogLevel.WARN,
        baseFields = mapOf("runner" to ID),
    ),
    sleep: suspend (Long) -> Unit = { delay(it) },
) : AbstractModelRunner(policy, healthChecker, credentials, clock, logger, sleep) {

    private val discoveryEdge = discovery

    override val id: String = ID

    override val supportedProviderTypes: Set<ModelProviderType> = setOf(
        ModelProviderType.LOCAL_PHONE,
        ModelProviderType.REMOTE_OPENAI_COMPATIBLE,
        ModelProviderType.CUSTOM,
    )

    override suspend fun discover(preset: ModelPreset): DiscoveryOutcome = discoveryEdge.discover(preset)

    override fun waitingMessage(preset: ModelPreset): String =
        "Waiting for the model endpoint at ${preset.endpoint.explicitUrl ?: "the configured address"} to answer"

    override fun notDetectedMessage(preset: ModelPreset): String =
        "The model endpoint for ${preset.displayName} is not reachable."

    override fun stoppedMessage(preset: ModelPreset): String =
        "Disconnected. The model server itself was not stopped by the app."

    companion object {
        const val ID: String = "hosted-endpoint"
    }
}
