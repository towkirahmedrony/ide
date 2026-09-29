package com.agentx.app.workspace.process

import com.agentx.app.workspace.ProcessEnvironment
import com.agentx.app.workspace.ProcessRequest
import com.agentx.app.workspace.ProcessState
import com.agentx.app.workspace.WorkspaceErrorCode
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the one-shot process path the Agent Tool System and the build/test runners use.
 *
 * The interactive terminal no longer lives here: it is the embedded Termux runtime in
 * `:termux-runtime`, which owns its own pty sessions. What remains in this module is the
 * non-interactive executor, which the Tool Router authorises separately.
 */
class ProcessEnvironmentBuilderTest {

    @Test
    fun `secret names are never copied into the process environment`() {
        val env = ProcessEnvironment(
            inheritParent = false,
            variables = mapOf(
                "SAFE_VALUE" to "ok",
                "OPENAI_API_KEY" to "sk-should-not-appear",
                "GITHUB_TOKEN" to "ghp-should-not-appear",
            ),
        )

        val built = ProcessEnvironmentBuilder.build(env, workingDirectory = "/tmp")

        assertEquals("ok", built["SAFE_VALUE"])
        assertFalse(built.containsKey("OPENAI_API_KEY"))
        assertFalse(built.containsKey("GITHUB_TOKEN"))
    }

    @Test
    fun `inheritParent still strips credential-like names`() {
        val built = ProcessEnvironmentBuilder.build(
            environment = ProcessEnvironment(inheritParent = true),
            workingDirectory = "/tmp",
        )

        assertTrue(built.keys.none { ProcessEnvironmentBuilder.looksSecret(it) })
    }
}

class JvmProcessRuntimeTest {

    private val shell: String? = ShellLocator.find()

    @Test
    fun `one-shot echo streams stdout and completes`() = runBlocking {
        val executable = shell ?: return@runBlocking
        val executor = RuntimeProcessExecutor(JvmProcessRuntime())
        val chunks = mutableListOf<String>()
        val result = executor.execute(
            ProcessRequest(command = executable, arguments = listOf("-c", "echo hello-coder")),
        ) { output ->
            if (output.stdout.isNotEmpty()) chunks += output.stdout
        }
        assertEquals(ProcessState.COMPLETED, result.state)
        assertEquals(0, result.exitCode)
        assertTrue(result.output.stdout.contains("hello-coder"))
        assertTrue(chunks.joinToString("").contains("hello-coder"))
    }

    @Test
    fun `command failure is a completed process with a non-zero exit code`() = runBlocking {
        val executable = shell ?: return@runBlocking
        val result = RuntimeProcessExecutor(JvmProcessRuntime()).execute(
            ProcessRequest(command = executable, arguments = listOf("-c", "echo missing 1>&2; exit 42")),
        )
        assertEquals(ProcessState.COMPLETED, result.state)
        assertEquals(42, result.exitCode)
        assertFalse(result.isSuccess)
        assertTrue(result.output.stderr.contains("missing") || result.output.stdout.contains("missing"))
    }

    @Test
    fun `cancelling a long-running command marks the process cancelled`() = runBlocking {
        val executable = shell ?: return@runBlocking
        val executor = RuntimeProcessExecutor(JvmProcessRuntime())
        val deferred = async {
            executor.execute(
                ProcessRequest(
                    command = executable,
                    arguments = listOf("-c", "sleep 30"),
                    timeoutMillis = 400,
                ),
            )
        }
        val result = deferred.await()
        assertEquals(ProcessState.CANCELLED, result.state)
    }

    @Test
    fun `unknown executable fails without crashing`() = runBlocking {
        val result = RuntimeProcessExecutor(JvmProcessRuntime()).execute(
            ProcessRequest(command = "/this/binary/does/not/exist-coder"),
        )
        assertEquals(ProcessState.FAILED, result.state)
        assertEquals(WorkspaceErrorCode.PROCESS_FAILED, result.error?.code)
    }
}
