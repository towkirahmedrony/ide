package com.agentx.app.tools.verification

import com.agentx.app.tools.SecretRedactor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The detector reports *that* a secret is present and where, but never carries the
 * value — the whole point of scanning without exposing the model to a credential.
 */
class SecretScanTest {

    @Test
    fun `a github token in an added line is detected with its location`() {
        val diff = """
            diff --git a/config.kt b/config.kt
            --- a/config.kt
            +++ b/config.kt
            @@ -1,2 +1,3 @@
             val x = 1
            +val token = "ghp_1234567890abcdefghij"
        """.trimIndent()

        val findings = SecretScan.scan(diff)

        assertEquals(1, findings.size)
        val finding = findings.single()
        assertEquals("config.kt", finding.path)
        assertEquals(2, finding.line)
        assertTrue(finding.reason.contains("token", ignoreCase = true))
        // The snippet is redacted: the value never appears.
        assertFalse(finding.snippet.contains("ghp_1234567890abcdefghij"))
        assertTrue(finding.snippet.contains(SecretRedactor.REDACTED))
    }

    @Test
    fun `a private key block is detected`() {
        val diff = "+++ b/keys/id_rsa\n@@ -0,0 +1 @@\n+-----BEGIN RSA PRIVATE KEY-----"

        val finding = SecretScan.scan(diff).single()

        assertTrue(finding.reason.contains("private key", ignoreCase = true))
        assertEquals("keys/id_rsa", finding.path)
    }

    @Test
    fun `an aws access key is detected`() {
        assertNotNull(SecretScan.reasonFor("aws_access_key_id = AKIAABCDEFGHIJKLMNOP"))
    }

    @Test
    fun `a plain assignment is detected`() {
        assertNotNull(SecretScan.reasonFor("password = hunter2"))
    }

    @Test
    fun `a removed secret is not reported`() {
        val diff = """
            diff --git a/x b/x
            --- a/x
            +++ b/x
            @@ -1 +0,0 @@
            -token = "ghp_1234567890abcdefghij"
        """.trimIndent()

        assertTrue(SecretScan.scan(diff).isEmpty())
    }

    @Test
    fun `ordinary prose is not a secret`() {
        assertNull(SecretScan.reasonFor("val total = computeTotal(items)"))
        assertNull(SecretScan.reasonFor("// the sk- prefix is documented here"))
        assertNull(SecretScan.reasonFor("fun list() = emptyList()"))
    }

    @Test
    fun `version control metadata lines are ignored`() {
        val diff = "+++ b/app.kt\n--- a/app.kt\n@@ -1 +1 @@"

        assertTrue(SecretScan.scan(diff).isEmpty())
    }

    @Test
    fun `a full diff scans only added lines across files`() {
        val diff = """
            diff --git a/a.kt b/a.kt
            --- a/a.kt
            +++ b/a.kt
            @@ -1 +1,2 @@
             unchanged
            +api_key = "abcdefghijklmno"
            diff --git a/b.kt b/b.kt
            --- a/b.kt
            +++ b/b.kt
            @@ -1 +1 @@
            -secret = old
            +val clean = 1
        """.trimIndent()

        val findings = SecretScan.scan(diff)

        assertEquals(1, findings.size)
        assertEquals("a.kt", findings.single().path)
    }
}
