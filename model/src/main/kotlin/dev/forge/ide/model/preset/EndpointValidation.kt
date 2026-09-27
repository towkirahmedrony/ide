package dev.forge.ide.model.preset

import java.net.URI
import java.net.URISyntaxException

/** Outcome of checking an endpoint URL supplied by the user or by a runtime. */
sealed interface EndpointValidation {
    /** [url] is normalized (no trailing slash) and safe to use. */
    data class Valid(val url: String) : EndpointValidation

    /** [reason] is user-facing and contains no secrets. */
    data class Invalid(val reason: String) : EndpointValidation
}

/**
 * Validates an endpoint URL before it is ever contacted.
 *
 * Rules, in order: non-blank, syntactically a URL, `http`/`https` only, a real
 * host, no embedded credentials, and — for remote providers — TLS. Anything that
 * fails returns a reason instead of a URL, so a malformed endpoint can never
 * reach the model gateway.
 */
fun validateEndpoint(raw: String, requireHttps: Boolean): EndpointValidation {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return EndpointValidation.Invalid("Endpoint is empty")
    if (trimmed.any { it.isWhitespace() }) return EndpointValidation.Invalid("Endpoint must not contain spaces")

    val uri = try {
        URI(trimmed)
    } catch (_: URISyntaxException) {
        return EndpointValidation.Invalid("Endpoint is not a valid URL")
    }

    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") {
        return EndpointValidation.Invalid("Endpoint must start with http:// or https://")
    }
    if (uri.host.isNullOrBlank()) {
        return EndpointValidation.Invalid("Endpoint must include a host")
    }
    if (!uri.userInfo.isNullOrEmpty()) {
        return EndpointValidation.Invalid("Endpoint must not embed credentials")
    }
    if (requireHttps && scheme != "https") {
        return EndpointValidation.Invalid("Remote endpoints must use https://")
    }

    return EndpointValidation.Valid(trimmed.trimEnd('/'))
}

/** Convenience wrapper returning the normalized URL, or `null` when invalid. */
fun validateEndpointUrl(raw: String, requireHttps: Boolean): String? =
    (validateEndpoint(raw, requireHttps) as? EndpointValidation.Valid)?.url
