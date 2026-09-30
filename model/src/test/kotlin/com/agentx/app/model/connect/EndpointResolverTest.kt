package com.agentx.app.model.connect

import com.agentx.app.model.preset.ModelProviderType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EndpointResolverTest {

    private fun ok(raw: String): EndpointResolver.Resolved =
        assertIs<EndpointResolver.Outcome.Ok>(EndpointResolver.resolve(raw)).resolved

    private fun invalid(raw: String): String =
        assertIs<EndpointResolver.Outcome.Invalid>(EndpointResolver.resolve(raw)).reason

    @Test
    fun `url without path prefers origin plus v1`() {
        val resolved = ok("https://example.com")
        assertEquals("https://example.com", resolved.origin)
        assertEquals("https://example.com", resolved.normalizedUrl)
        assertEquals("/v1", resolved.candidates.first().apiBasePath)
        assertEquals("https://example.com/v1", resolved.candidates.first().providerBaseUrl)
        assertTrue(resolved.candidates.any { it.apiBasePath.isEmpty() })
    }

    @Test
    fun `trailing slash is stripped`() {
        val resolved = ok("https://example.com/")
        assertEquals("https://example.com", resolved.origin)
        assertEquals("", resolved.normalizedPath)
    }

    @Test
    fun `v1 path is not duplicated`() {
        val resolved = ok("https://example.com/v1")
        assertEquals("/v1", resolved.normalizedPath)
        assertEquals("https://example.com", resolved.normalizedUrl)
        assertEquals("/v1", resolved.candidates.first().apiBasePath)
        assertTrue(resolved.candidates.none { it.providerBaseUrl.endsWith("/v1/v1") })
    }

    @Test
    fun `v1 with trailing slash normalizes to v1`() {
        val resolved = ok("https://example.com/v1/")
        assertEquals("/v1", resolved.normalizedPath)
        assertEquals("https://example.com", resolved.normalizedUrl)
    }

    @Test
    fun `duplicate v1 collapses to a single v1`() {
        val resolved = ok("https://example.com/v1/v1")
        assertEquals("/v1", resolved.normalizedPath)
        assertTrue(resolved.candidates.none { it.providerBaseUrl.contains("/v1/v1") })
    }

    @Test
    fun `duplicate slashes collapse`() {
        val resolved = ok("https://example.com//v1/")
        assertEquals("/v1", resolved.normalizedPath)
        assertEquals("https://example.com", resolved.normalizedUrl)
    }

    @Test
    fun `whitespace around the url is ignored`() {
        val resolved = ok("  https://example.com/v1  ")
        assertEquals("https://example.com", resolved.origin)
        assertEquals("/v1", resolved.normalizedPath)
    }

    @Test
    fun `chat completions suffix is stripped`() {
        val resolved = ok("https://example.com/v1/chat/completions")
        assertEquals("/v1", resolved.normalizedPath)
        assertEquals("https://example.com", resolved.normalizedUrl)
        assertEquals("/v1", resolved.candidates.first().apiBasePath)
    }

    @Test
    fun `custom non-v1 paths are preserved`() {
        val resolved = ok("https://example.com/openai")
        assertEquals("/openai", resolved.normalizedPath)
        assertEquals("https://example.com/openai", resolved.candidates.first().rootUrl)
        assertEquals("", resolved.candidates.first().apiBasePath)
        assertTrue(resolved.candidates.any { it.rootUrl.endsWith("/openai") && it.apiBasePath == "/v1" })
    }

    @Test
    fun `ngrok urls stay https remote endpoints`() {
        val resolved = ok("https://xxxx.ngrok-free.app")
        assertEquals(ModelProviderType.REMOTE_OPENAI_COMPATIBLE, resolved.inferredProviderType)
        assertEquals("https://xxxx.ngrok-free.app", resolved.origin)
    }

    @Test
    fun `plain ngrok host is upgraded to https`() {
        val resolved = ok("xxxx.ngrok-free.app")
        assertEquals("https://xxxx.ngrok-free.app", resolved.origin)
    }

    @Test
    fun `localhost is a device-local http endpoint`() {
        val resolved = ok("http://127.0.0.1:11434")
        assertEquals(ModelProviderType.LOCAL_PHONE, resolved.inferredProviderType)
        assertEquals("http://127.0.0.1:11434", resolved.origin)
    }

    @Test
    fun `private lan stays http`() {
        val resolved = ok("http://192.168.1.20:8000/v1")
        assertEquals(ModelProviderType.LOCAL_PHONE, resolved.inferredProviderType)
        assertEquals("http://192.168.1.20:8000", resolved.origin)
    }

    @Test
    fun `embedded credentials are refused`() {
        val reason = invalid("https://user:secret@example.com/v1")
        assertTrue(reason.contains("credential"))
    }

    @Test
    fun `empty input is refused`() {
        assertTrue(invalid("").contains("empty"))
    }

    @Test
    fun `no candidate ever contains triple v1`() {
        val inputs = listOf(
            "https://example.com",
            "https://example.com/v1",
            "https://example.com/v1/",
            "https://example.com/v1/v1",
            "https://example.com/v1/v1/v1",
            "https://example.com/chat/completions",
        )
        for (input in inputs) {
            val resolved = ok(input)
            assertTrue(
                resolved.candidates.none { it.providerBaseUrl.contains("/v1/v1/v1") },
                input,
            )
        }
    }
}
