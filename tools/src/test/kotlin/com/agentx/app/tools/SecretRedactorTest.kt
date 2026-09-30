package com.agentx.app.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecretRedactorTest {

    @Test
    fun `key equals value is redacted`() {
        assertEquals("api_key=[REDACTED]", SecretRedactor.redactText("api_key=sk-live-abc123"))
    }

    @Test
    fun `key colon value is redacted`() {
        // The replacement is normalised to `key=[REDACTED]` whichever separator was used.
        assertEquals("token=[REDACTED]", SecretRedactor.redactText("token: abc123"))
    }

    @Test
    fun `a secret inside a json body is redacted`() {
        val redacted = SecretRedactor.redactText("""{"password":"hunter2","user":"ada"}""")

        assertFalse(redacted.contains("hunter2"), redacted)
        assertTrue(redacted.contains(SecretRedactor.REDACTED), redacted)
    }

    @Test
    fun `an environment style line is redacted`() {
        val redacted = SecretRedactor.redactText("OPENAI_API_KEY=sk-live-abc123")

        assertFalse(redacted.contains("sk-live-abc123"), redacted)
        assertTrue(redacted.contains("OPENAI_API_KEY"), redacted)
    }

    @Test
    fun `a private key block is redacted`() {
        val redacted = SecretRedactor.redactText("private_key=-----BEGIN PRIVATE KEY-----")

        assertFalse(redacted.contains("BEGIN PRIVATE KEY"), redacted)
    }

    @Test
    fun `ordinary prose is untouched`() {
        val text = "the refresh token logic reads the stored credentials once"

        // "token" appears, but not followed by a separator and a value.
        assertEquals(text, SecretRedactor.redactText(text))
    }

    @Test
    fun `looksSecret matches the known credential names`() {
        assertTrue(SecretRedactor.looksSecret("api_key"))
        assertTrue(SecretRedactor.looksSecret("API-KEY"))
        assertTrue(SecretRedactor.looksSecret("refresh_token"))
        assertTrue(SecretRedactor.looksSecret("password"))
        assertTrue(SecretRedactor.looksSecret("private_key"))
        assertTrue(SecretRedactor.looksSecret("credentials"))
        assertFalse(SecretRedactor.looksSecret("file_path"))
        assertFalse(SecretRedactor.looksSecret("query"))
    }
}
