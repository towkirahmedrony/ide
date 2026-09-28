package com.agentx.app.integrations.oauth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * RFC 7636 (PKCE) helpers.
 *
 * The verifier is generated on the device, kept in memory while the user is on
 * the provider's page, and replayed once at token exchange. It is never written
 * to disk and never logged.
 */
object OAuthPkce {

    /** The only code challenge method GitHub and Supabase accept. */
    const val METHOD_S256: String = "S256"

    private const val VERIFIER_BYTES = 32
    private const val STATE_BYTES = 32

    /** A fresh, high-entropy code verifier (43 characters, base64url, unpadded). */
    fun createVerifier(random: SecureRandom = SecureRandom()): String =
        Base64Url.encode(randomBytes(random, VERIFIER_BYTES))

    /** A fresh, unguessable `state` value. */
    fun createState(random: SecureRandom = SecureRandom()): String =
        Base64Url.encode(randomBytes(random, STATE_BYTES))

    /** `BASE64URL(SHA256(ASCII(verifier)))`, per RFC 7636 section 4.2. */
    fun challenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64Url.encode(digest)
    }

    private fun randomBytes(random: SecureRandom, size: Int): ByteArray {
        val bytes = ByteArray(size)
        random.nextBytes(bytes)
        return bytes
    }
}

/** Base64url without padding, as required by RFC 7636 and the OAuth specs. */
internal object Base64Url {

    fun encode(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun decode(value: String): ByteArray? =
        runCatching {
            Base64.getUrlDecoder().decode(value.padEnd((value.length + 3) / 4 * 4, '='))
        }.getOrNull()
}
