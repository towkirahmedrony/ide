package com.agentx.app.ubuntu

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UbuntuEnvironmentTest {

    private fun build(
        project: String? = null,
        android: Map<String, String> = emptyMap(),
        extra: Map<String, String> = emptyMap(),
    ): List<String> = UbuntuEnvironment.build(project, android, extra).toList()

    @Test
    fun `the guest environment is a linux environment`() {
        val environment = build(project = ProotCommand.GUEST_PROJECT_ROOT)
        assertTrue(environment.contains("HOME=/root"))
        assertTrue(environment.contains("USER=root"))
        assertTrue(environment.contains("LOGNAME=root"))
        assertTrue(environment.contains("SHELL=/bin/bash"))
        assertTrue(environment.contains("TERM=${UbuntuEnvironment.TERM}"))
        assertTrue(environment.contains("TMPDIR=/tmp"))
        assertTrue(environment.contains("LANG=C.UTF-8"))
        assertTrue(environment.contains("PATH=${UbuntuEnvironment.GUEST_PATH}"))
        assertTrue(environment.contains("AGENTX_RUNTIME=ubuntu"))
        assertTrue(environment.contains("AGENTX_PROJECT=/workspace"))
    }

    @Test
    fun `only the timezone is passed through from android`() {
        val environment = build(android = mapOf("TZ" to "Europe/London", "BOOTCLASSPATH" to "/x"))
        assertTrue(environment.contains("TZ=Europe/London"))
        // Android internals are noise inside the guest.
        assertFalse(environment.any { it.startsWith("BOOTCLASSPATH") })
        assertFalse(environment.any { it.startsWith("ANDROID_ROOT") })
    }

    @Test
    fun `credentials are refused even when offered`() {
        val environment = build(extra = mapOf("MY_API_KEY" to "x", "GH_TOKEN" to "secret", "VISIBLE" to "1"))
        assertTrue(environment.contains("VISIBLE=1"))
        assertFalse(environment.any { it.startsWith("MY_API_KEY") })
        assertFalse(environment.any { it.startsWith("GH_TOKEN") })
    }

    @Test
    fun `the secret heuristic knows what matters`() {
        assertTrue(UbuntuEnvironment.looksSecret("api_key"))
        assertTrue(UbuntuEnvironment.looksSecret("GITHUB_TOKEN"))
        assertTrue(UbuntuEnvironment.looksSecret("private_key"))
        assertFalse(UbuntuEnvironment.looksSecret("file_path"))
        assertFalse(UbuntuEnvironment.looksSecret("AGENTX_PROJECT"))
    }

    @Test
    fun `the environment file quotes values that need it`() {
        val file = UbuntuEnvironment.toEnvFile(arrayOf("A=plain", "B=has space"))
        assertEquals("A=plain\nB=\"has space\"\n", file)
    }
}
