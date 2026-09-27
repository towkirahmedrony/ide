package dev.forge.ide.model.runtime

import dev.forge.ide.core.logging.ForgeLogger
import dev.forge.ide.core.logging.ForgeLoggers
import dev.forge.ide.core.logging.LogLevel
import dev.forge.ide.model.preset.ModelCredentialResolver
import dev.forge.ide.model.preset.ModelPreset
import dev.forge.ide.model.preset.ModelProviderType
import kotlinx.coroutines.delay

/**
 * Model runner for Google Colab runtimes.
 *
 * What this runner genuinely can and cannot do — this is the honest contract:
 *
 * - It **cannot** start, restart, or keep a Colab runtime alive. A Colab runtime
 *   lives in Google's infrastructure and is driven by the notebook; an Android
 *   app has no supported way to run or hold it. The Model Runner browser is how
 *   the user starts it.
 * - It **can** detect the endpoint the runtime publishes (a tunnel URL printed by
 *   the notebook), validate it, health-check the model API behind it, and then
 *   hand that endpoint to the Model Gateway.
 * - It **can** therefore connect instantly when the user returns and the runtime
 *   is still up, and it reports clearly when it is not.
 *
 * All of the retry, timeout, health-gating and "never restart a healthy runtime"
 * behaviour comes from [AbstractModelRunner]; this class only supplies Colab
 * discovery and the user-facing wording.
 */
class ColabRunner(
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

    override val supportedProviderTypes: Set<ModelProviderType> = setOf(ModelProviderType.GOOGLE_COLAB)

    override suspend fun discover(preset: ModelPreset): DiscoveryOutcome = discoveryEdge.discover(preset)

    override fun waitingMessage(preset: ModelPreset): String =
        "Waiting for the Colab runtime to publish its endpoint. Open the Model Runner and run your notebook."

    override fun notDetectedMessage(preset: ModelPreset): String =
        "Model runtime stopped. The Colab runtime for ${preset.displayName} is no longer available."

    override fun stoppedMessage(preset: ModelPreset): String =
        "Disconnected. The Colab runtime itself was not stopped — stop it in Colab when you are done."

    companion object {
        const val ID: String = "colab"
    }
}
