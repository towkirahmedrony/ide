package com.agentx.app.model.runtime

import com.agentx.app.model.preset.EndpointValidation
import com.agentx.app.model.preset.ModelPreset
import com.agentx.app.model.preset.TunnelType
import com.agentx.app.model.preset.validateEndpoint

/** Outcome of asking a tunnel provider for an endpoint. */
sealed interface TunnelDetection {
    data class Detected(val url: String, val providerId: String) : TunnelDetection

    data class NotFound(val reason: String) : TunnelDetection
}

/**
 * A tunnel kind the app understands.
 *
 * IMPORTANT: a tunnel process runs *inside* the model runtime (for example inside
 * the Colab notebook). An Android app cannot spawn or keep that process alive, so
 * every provider here is a *detection* mechanism: it reads the endpoint the
 * runtime already published. [createsTunnels] exists so this limitation is
 * explicit rather than implied — a provider that cannot create a tunnel must
 * never report that it did.
 */
interface TunnelProvider {
    val id: String

    val type: TunnelType

    /** Whether this provider can create a tunnel from the app. False for all today. */
    val createsTunnels: Boolean get() = false

    /**
     * Looks for an endpoint for [preset] in [output], the text the runtime
     * produced. Implementations must only report URLs they can actually see.
     */
    fun detect(preset: ModelPreset, output: List<String>): TunnelDetection

    /** Confirms that [url] belongs to this provider. */
    fun validate(url: String): TunnelDetection
}

/**
 * Cloudflare Quick Tunnel (`https://<random>.trycloudflare.com`).
 *
 * Creation is not possible from the app: `cloudflared` runs in the Colab
 * runtime, which is why the startup script the user saves is expected to print
 * the endpoint (see [com.agentx.app.model.preset.TunnelConfig.marker]). This
 * provider only detects and validates that endpoint.
 */
class CloudflareQuickTunnelProvider : TunnelProvider {

    override val id: String = "cloudflare-quick"

    override val type: TunnelType = TunnelType.CLOUDFLARE_QUICK

    override val createsTunnels: Boolean = false

    override fun detect(preset: ModelPreset, output: List<String>): TunnelDetection {
        // Newest output wins: the runtime may print several URLs over a session.
        for (line in output.asReversed()) {
            val candidate = markerValue(line, preset.tunnel.marker) ?: QUICK_TUNNEL.find(line)?.value
            if (candidate != null) return validate(candidate)
        }
        return TunnelDetection.NotFound("No Cloudflare Quick Tunnel endpoint was found in the runtime output")
    }

    override fun validate(url: String): TunnelDetection {
        val normalized = when (val result = validateEndpoint(url, requireHttps = true)) {
            is EndpointValidation.Valid -> result.url
            is EndpointValidation.Invalid -> return TunnelDetection.NotFound(result.reason)
        }
        val host = normalized.substringAfter("://").substringBefore('/')
        if (!host.endsWith(HOST_SUFFIX)) {
            return TunnelDetection.NotFound("$host is not a $HOST_SUFFIX endpoint")
        }
        return TunnelDetection.Detected(normalized, id)
    }

    companion object {
        const val HOST_SUFFIX: String = ".trycloudflare.com"

        private val QUICK_TUNNEL = Regex("https://[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.trycloudflare\\.com")
    }
}

/**
 * A tunnel the user already runs (ngrok, a self-hosted tunnel, a port forward).
 * The URL comes from the preset; the provider still validates it before use.
 */
class ManualTunnelProvider : TunnelProvider {

    override val id: String = "manual"

    override val type: TunnelType = TunnelType.MANUAL

    override fun detect(preset: ModelPreset, output: List<String>): TunnelDetection {
        // A manual tunnel is user-declared configuration: the endpoint comes from
        // the preset, never from whatever text the runtime happened to print.
        val configured = preset.endpoint.explicitUrl?.takeIf { it.isNotBlank() }
            ?: return TunnelDetection.NotFound("No tunnel URL was configured for this model")
        return validate(configured)
    }

    override fun validate(url: String): TunnelDetection =
        when (val result = validateEndpoint(url, requireHttps = true)) {
            is EndpointValidation.Valid -> TunnelDetection.Detected(result.url, id)
            is EndpointValidation.Invalid -> TunnelDetection.NotFound(result.reason)
        }
}

/** Used when a preset does not involve a tunnel at all. */
object NoTunnelProvider : TunnelProvider {

    override val id: String = "none"

    override val type: TunnelType = TunnelType.NONE

    override fun detect(preset: ModelPreset, output: List<String>): TunnelDetection =
        TunnelDetection.NotFound("No tunnel is configured for this model")

    override fun validate(url: String): TunnelDetection =
        TunnelDetection.NotFound("No tunnel is configured for this model")
}

/** Looks up tunnel providers by type and performs output scanning. */
class TunnelProviders(
    private val providers: List<TunnelProvider> = default(),
) {

    fun forType(type: TunnelType): TunnelProvider =
        providers.firstOrNull { it.type == type } ?: NoTunnelProvider

    /**
     * Detects an endpoint with the provider the preset asks for.
     *
     * Exactly one provider is used on purpose: silently accepting a URL that a
     * different kind of provider happened to print would mean trusting arbitrary
     * text from the runtime. If the tunnel type is wrong, the user sees the
     * reason and changes the setting.
     */
    fun detect(preset: ModelPreset, output: List<String>): TunnelDetection =
        forType(preset.tunnel.type).detect(preset, output)

    companion object {
        fun default(): List<TunnelProvider> = listOf(CloudflareQuickTunnelProvider(), ManualTunnelProvider())
    }
}

/** Returns the URL that follows [marker] on [line], when present. */
internal fun markerValue(line: String?, marker: String): String? {
    if (line == null || marker.isBlank()) return null
    val index = line.indexOf(marker)
    if (index < 0) return null
    return line.substring(index + marker.length).trim().substringBefore(' ').takeIf { it.isNotBlank() }
}
