package com.agentx.app.integrations.oauth

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Small URI helpers for the OAuth layer.
 *
 * The integrations module has no Android dependency, so redirect URIs are read
 * and written here instead of through `android.net.Uri`. Values are never logged.
 */
object OAuthQueryParameters {

    /**
     * Parses the query (and fragment, which some providers use) of a redirect URI
     * into a parameter map. Later values win, which matches how providers send a
     * single set of authorization parameters.
     */
    fun parse(uri: String): Map<String, String> {
        val query = uri.substringAfter('?', missingDelimiterValue = "")
        val fragment = uri.substringAfter('#', missingDelimiterValue = "")
        val parameters = LinkedHashMap<String, String>()
        parameters.putAll(parsePairs(query))
        parameters.putAll(parsePairs(fragment))
        return parameters
    }

    private fun parsePairs(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        raw.split('&').forEach { pair ->
            if (pair.isBlank()) return@forEach
            val index = pair.indexOf('=')
            val key = if (index < 0) pair else pair.substring(0, index)
            val value = if (index < 0) "" else pair.substring(index + 1)
            val decodedKey = decode(key).trim()
            if (decodedKey.isEmpty()) return@forEach
            result[decodedKey] = decode(value)
        }
        return result
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, Charsets.UTF_8.name()) }.getOrDefault(value)
}

/** Builds URLs with correctly encoded query parameters. */
object OAuthUrl {

    fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    /** Appends [parameters] to [baseUrl], preserving any existing query string. */
    fun withQuery(baseUrl: String, parameters: Map<String, String>): String {
        if (parameters.isEmpty()) return baseUrl
        val separator = if (baseUrl.contains('?')) '&' else '?'
        val query = parameters.entries
            .joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
        return "$baseUrl$separator$query"
    }
}
