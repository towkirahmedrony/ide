package com.agentx.app.termux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TermuxEnvironmentTest {

    private val paths = TermuxPaths.forAppDataDir("/data/data/com.agentx.app")
    private val working = "${paths.workspaces}/demo"

    private fun env(
        androidEnv: Map<String, String> = emptyMap(),
        extra: Map<String, String> = emptyMap(),
        workingDirectory: String? = working,
    ): Map<String, String> = TermuxEnvironment
        .build(paths, workingDirectory, androidEnv, extra)
        .associate { entry ->
            val separator = entry.indexOf('=')
            entry.substring(0, separator) to entry.substring(separator + 1)
        }

    @Test
    fun `termux variables are set`() {
        val environment = env()
        assertEquals(paths.home, environment["HOME"])
        assertEquals(paths.prefix, environment["PREFIX"])
        assertEquals(paths.bin, environment["PATH"])
        assertEquals(paths.tmp, environment["TMPDIR"])
        assertEquals(working, environment["PWD"])
        assertEquals("xterm-256color", environment["TERM"])
        assertEquals("en_US.UTF-8", environment["LANG"])
        assertEquals("truecolor", environment["COLORTERM"])
    }

    @Test
    fun `a non absolute working directory falls back to home`() {
        assertEquals(paths.home, env(workingDirectory = "relative/dir")["PWD"])
        assertEquals(paths.home, env(workingDirectory = null)["PWD"])
    }

    @Test
    fun `credentials are never handed to the shell`() {
        // The IDE process holds OAuth tokens and model API keys. None of them may be readable
        // from the terminal, and none of them belong in the Android pass-through list either.
        val environment = env(
            androidEnv = mapOf(
                "ANDROID_ROOT" to "/system",
                "GITHUB_OAUTH_TOKEN" to "gho_secret",
                "OPENAI_API_KEY" to "sk-secret",
                "SUPABASE_SERVICE_ROLE_KEY" to "secret",
                "AGENTX_KEYSTORE_PASSPHRASE" to "secret",
            ),
            extra = mapOf(
                "CODER_WORKSPACE" to "/data/data/com.agentx.app/files/workspaces/demo",
                "GITHUB_TOKEN" to "ghp_secret",
                "AWS_SECRET_ACCESS_KEY" to "secret",
            ),
        )

        assertEquals("/system", environment["ANDROID_ROOT"])
        assertEquals(
            "/data/data/com.agentx.app/files/workspaces/demo",
            environment["CODER_WORKSPACE"],
        )
        for (name in listOf(
            "GITHUB_OAUTH_TOKEN",
            "OPENAI_API_KEY",
            "SUPABASE_SERVICE_ROLE_KEY",
            "AGENTX_KEYSTORE_PASSPHRASE",
            "GITHUB_TOKEN",
            "AWS_SECRET_ACCESS_KEY",
        )) {
            assertFalse(environment.containsKey(name), "$name must not reach the shell")
        }
    }

    @Test
    fun `apk signing variables are not forwarded`() {
        val environment = env(
            androidEnv = mapOf(
                "AGENTX_STORE_PASSWORD" to "secret",
                "AGENTX_KEY_PASSWORD" to "secret",
                "TZ" to "Asia/Singapore",
            ),
        )
        assertFalse(environment.containsKey("AGENTX_STORE_PASSWORD"))
        assertFalse(environment.containsKey("AGENTX_KEY_PASSWORD"))
        assertEquals("Asia/Singapore", environment["TZ"])
    }

    @Test
    fun `a malformed variable name is rejected instead of corrupting the array`() {
        val environment = env(extra = mapOf("BAD NAME" to "x", "1LEADING" to "x", "GOOD_ONE" to "y"))
        assertFalse(environment.containsKey("BAD NAME"))
        assertFalse(environment.containsKey("1LEADING"))
        assertEquals("y", environment["GOOD_ONE"])
    }

    @Test
    fun `secret detection covers the usual spellings`() {
        for (name in listOf("API_KEY", "apiKey", "GH_TOKEN", "MY_SECRET", "DB_PASSWORD", "PRIVATE_KEY", "Authorization")) {
            assertTrue(TermuxEnvironment.looksSecret(name), name)
        }
        for (name in listOf("PATH", "HOME", "ANDROID_ROOT", "CODER_WORKSPACE", "LANG")) {
            assertFalse(TermuxEnvironment.looksSecret(name), name)
        }
    }

    @Test
    fun `env file quotes values that need it`() {
        val body = TermuxEnvironment.toEnvFile(
            arrayOf("HOME=/data/data/com.agentx.app/files/home", "GREETING=hello world", "EMPTY="),
        )
        assertTrue(body.contains("HOME=/data/data/com.agentx.app/files/home\n"))
        assertTrue(body.contains("GREETING=\"hello world\"\n"))
        // An empty value is written as EMPTY="", which is unambiguous when the file is sourced.
        assertTrue(body.contains("EMPTY=\"\"\n"))
        assertFalse(body.contains("EMPTY=\n"))
    }
}
