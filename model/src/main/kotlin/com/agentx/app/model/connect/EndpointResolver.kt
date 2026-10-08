package com.agentx.app.model.connect

import com.agentx.app.model.preset.EndpointValidation
import com.agentx.app.model.preset.ModelProviderType
import com.agentx.app.model.preset.validateEndpoint
import java.net.URI
import java.net.URISyntaxException

/**
 * Forgiving URL normalizer for model endpoints.
 *
 * Users paste Colab/ngrok/Cloudflare/local URLs in many shapes (`/v1`,
 * `/v1/v1`, trailing slashes, a chat path). This type never contacts the
 * network: it only produces a safe origin plus an ordered list of API-base
 * candidates for discovery to try.
 */
object EndpointResolver {

    data class Candidate(
        /** Endpoint root without the OpenAI `/v1` suffix. */
        val rootUrl: String,
        /** Path appended for chat/health, usually `/v1` or empty. */
        val apiBasePath: String,
        val reason: String,
    ) {
        /** Base URL the OpenAI-compatible provider should use. */
        val providerBaseUrl: String get() = join(rootUrl, apiBasePath)
    }

    data class Resolved(
        val original: String,
        val origin: String,
        val normalizedPath: String,
        /** Root URL stored on the preset (no trailing slash, `/v1` stripped when it was an API suffix). */
        val normalizedUrl: String,
        val inferredProviderType: ModelProviderType,
        val candidates: List<Candidate>,
        val requireHttps: Boolean,
    ) {
        /**
         * The address a connection keeps when its endpoint *is* its API base: the
         * matched root together with the API-base path the resolver would append for
         * chat.
         *
         * [normalizedUrl] stores the bare root and leaves `/v1` to the base path,
         * which suits a provider that owns a base path (Gemini) or a local endpoint.
         * A provider whose catalogue declares no base path of its own stores this
         * instead, so a typed `/v1` stays on the endpoint rather than being dropped
         * into a path that provider never appends.
         */
        val providerBaseUrl: String get() = candidates.firstOrNull()?.providerBaseUrl ?: normalizedUrl
    }

    sealed interface Outcome {
        data class Ok(val resolved: Resolved) : Outcome
        data class Invalid(val reason: String) : Outcome
    }

    fun resolve(raw: String, preferHttpsForRemote: Boolean = true): Outcome {
        val prepared = prepare(raw)
        if (prepared is Outcome.Invalid) return prepared

        val uri = (prepared as Prepared).uri
        val inferred = inferProviderType(uri.host.orEmpty())
        val origin = originOf(maybeUpgradeScheme(uri, inferred))
        val path = normalizePath(uri.rawPath ?: uri.path.orEmpty())
        val requireHttps = preferHttpsForRemote && inferred.requiresSecureEndpoint

        when (val validation = validateEndpoint(origin, requireHttps)) {
            is EndpointValidation.Invalid -> return Outcome.Invalid(validation.reason)
            is EndpointValidation.Valid -> Unit
        }

        val candidates = candidatesFor(origin, path)
        val normalizedUrl = candidates.firstOrNull()?.rootUrl ?: origin.trimEnd('/')
        return Outcome.Ok(
            Resolved(
                original = raw.trim(),
                origin = origin,
                normalizedPath = path,
                normalizedUrl = normalizedUrl,
                inferredProviderType = inferred,
                candidates = candidates,
                requireHttps = requireHttps,
            ),
        )
    }

    fun normalizeUserInput(raw: String): String =
        when (val outcome = resolve(raw)) {
            is Outcome.Ok -> outcome.resolved.normalizedUrl
            is Outcome.Invalid -> raw.trim()
        }

    fun inferProviderType(host: String): ModelProviderType {
        val h = host.trim().lowercase().trim('[', ']')
        return if (isLoopback(h) || isPrivateLan(h)) {
            ModelProviderType.LOCAL_PHONE
        } else {
            ModelProviderType.REMOTE_OPENAI_COMPATIBLE
        }
    }

    fun isLoopback(host: String): Boolean {
        val h = host.lowercase()
        return h == "localhost" ||
            h == "127.0.0.1" ||
            h == "0.0.0.0" ||
            h == "::1" ||
            h == "0:0:0:0:0:0:0:1" ||
            h.endsWith(".localhost")
    }

    fun isPrivateLan(host: String): Boolean {
        val h = host.lowercase()
        if (h.startsWith("10.")) return true
        if (h.startsWith("192.168.")) return true
        if (h.startsWith("169.254.")) return true
        val m = PRIVATE_172.matchEntire(h)
        if (m != null) {
            val second = m.groupValues[1].toIntOrNull() ?: return false
            return second in 16..31
        }
        return false
    }

    // --- internals ---------------------------------------------------------

    private class Prepared(val uri: URI)

    private fun prepare(raw: String): Any {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Outcome.Invalid("Endpoint is empty")
        val withScheme = ensureScheme(trimmed)
        if (withScheme.any { it.isWhitespace() }) {
            return Outcome.Invalid("Endpoint must not contain spaces")
        }
        val uri = try {
            URI(withScheme)
        } catch (_: URISyntaxException) {
            return Outcome.Invalid("Endpoint is not a valid URL")
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return Outcome.Invalid("Endpoint must start with http:// or https://")
        }
        if (uri.host.isNullOrBlank()) {
            return Outcome.Invalid("Endpoint must include a host")
        }
        if (!uri.userInfo.isNullOrEmpty()) {
            return Outcome.Invalid("Endpoint must not embed credentials")
        }
        return Prepared(uri)
    }

    private fun ensureScheme(raw: String): String {
        val lower = raw.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) return raw
        if (raw.contains("://")) return raw
        val hostPart = raw.substringBefore('/').substringBefore('?')
        val host = hostPart.substringBefore(':').lowercase()
        return if (isLoopback(host) || isPrivateLan(host)) "http://$raw" else "https://$raw"
    }

    private fun maybeUpgradeScheme(uri: URI, inferred: ModelProviderType): URI {
        if (uri.scheme.equals("https", ignoreCase = true)) return uri
        if (inferred == ModelProviderType.LOCAL_PHONE) return uri
        val host = uri.host.orEmpty().lowercase()
        if (PUBLIC_TUNNEL_SUFFIXES.none { host.endsWith(it) }) return uri
        return URI("https", uri.userInfo, uri.host, uri.port, uri.path, uri.query, uri.fragment)
    }

    private fun originOf(uri: URI): String {
        val scheme = uri.scheme.lowercase()
        val host = uri.host
        val port = uri.port
        val defaultPort = if (scheme == "https") 443 else 80
        return if (port > 0 && port != defaultPort) {
            "$scheme://$host:$port"
        } else {
            "$scheme://$host"
        }
    }

    /**
     * Collapse duplicate slashes, drop a trailing chat-completions suffix, and
     * fold repeated `/v1` segments so `/v1/v1` becomes `/v1`.
     */
    internal fun normalizePath(rawPath: String): String {
        if (rawPath.isBlank() || rawPath == "/") return ""
        // Fold repeated slashes *before* the path is handed to URI(). A path that
        // starts with "//" is read back as an authority component, so the first
        // segment would be dropped ("//v1/" came back as "/"). Collapsing first
        // keeps the parser from reinterpreting the input.
        val slashCollapsed = rawPath.replace(DUPLICATE_SLASHES, "/").trim()
        val decoded = runCatching { URI(null, null, slashCollapsed, null).path }
            .getOrNull()
            ?.replace(DUPLICATE_SLASHES, "/")
            ?: slashCollapsed
        val withoutChat = stripChatSuffix(decoded)
        val segments = withoutChat.split('/').filter { it.isNotEmpty() && it != "." }
        val collapsed = ArrayList<String>(segments.size)
        for (segment in segments) {
            if (segment.equals("v1", ignoreCase = true) &&
                collapsed.lastOrNull()?.equals("v1", ignoreCase = true) == true
            ) {
                continue
            }
            collapsed += segment
        }
        if (collapsed.isEmpty()) return ""
        return "/" + collapsed.joinToString("/")
    }

    private fun stripChatSuffix(path: String): String {
        val lower = path.lowercase().trimEnd('/')
        return when {
            lower.endsWith("/chat/completions") ->
                path.trimEnd('/').dropLast("/chat/completions".length)
            lower.endsWith("/completions") && !lower.endsWith("/chat/completions") ->
                path.trimEnd('/')
            else -> path
        }
    }

    internal fun candidatesFor(origin: String, path: String): List<Candidate> {
        val rootOrigin = origin.trimEnd('/')
        val seen = LinkedHashSet<String>()
        val out = ArrayList<Candidate>(4)

        fun add(root: String, apiBase: String, reason: String) {
            val normalizedRoot = root.trimEnd('/')
            val normalizedApi = when {
                apiBase.isBlank() || apiBase == "/" -> ""
                else -> "/" + apiBase.trim('/').split('/').filter { it.isNotEmpty() }.joinToString("/")
            }
            if (normalizedApi.endsWith("/v1/v1")) return
            val key = "$normalizedRoot|$normalizedApi"
            if (!seen.add(key)) return
            out += Candidate(normalizedRoot, normalizedApi, reason)
        }

        when {
            path.isEmpty() -> {
                add(rootOrigin, "/v1", "origin plus /v1")
                add(rootOrigin, "", "origin without /v1")
            }

            path.equals("/v1", ignoreCase = true) -> {
                add(rootOrigin, "/v1", "input already ends with /v1")
                add(rootOrigin, "", "origin without /v1")
            }

            path.endsWith("/v1", ignoreCase = true) -> {
                val prefix = path.dropLast(3).trimEnd('/')
                val root = if (prefix.isEmpty()) rootOrigin else rootOrigin + prefix
                add(root, "/v1", "custom path ending in /v1")
                add(rootOrigin + path, "", "path as-is, no extra /v1")
            }

            else -> {
                add(rootOrigin + path, "", "custom path as API root")
                add(rootOrigin + path, "/v1", "custom path plus /v1")
                add(rootOrigin, "/v1", "origin plus /v1")
            }
        }
        return out
    }

    internal fun join(root: String, path: String): String {
        val base = root.trimEnd('/')
        val extra = path.trim()
        if (extra.isEmpty() || extra == "/") return base
        return base + if (extra.startsWith("/")) extra else "/$extra"
    }

    /**
     * Tunnel hosts that only ever serve TLS, so a typed `http://` is a typo
     * rather than an intent. `ngrok-free.dev` is the current free-tier domain
     * and was missing here, which left `http://<x>.ngrok-free.dev` unreachable.
     */
    private val PUBLIC_TUNNEL_SUFFIXES = listOf(
        ".ngrok-free.app",
        ".ngrok-free.dev",
        ".ngrok.app",
        ".ngrok.dev",
        ".ngrok.io",
        ".trycloudflare.com",
        ".cfargotunnel.com",
    )

    private val DUPLICATE_SLASHES = Regex("/{2,}")
    private val PRIVATE_172 = Regex("""^172\.(\d{1,3})\.\d{1,3}\.\d{1,3}$""")
}
