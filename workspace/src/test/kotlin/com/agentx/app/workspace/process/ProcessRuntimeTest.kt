package com.agentx.app.workspace.process

import com.agentx.app.workspace.ProcessEnvironment
import com.agentx.app.workspace.ProcessRequest
import com.agentx.app.workspace.ProcessState
import com.agentx.app.workspace.WorkspaceError
import com.agentx.app.workspace.WorkspaceErrorCode
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerminalBufferTest {

    @Test
    fun `buffer drops oldest lines when over the line limit`() {
        val buffer = TerminalBuffer(maxLines = 3, maxChars = 10_000)
        repeat(5) { index ->
            buffer.append(TerminalBufferLine(id = "$index", text = "line-$index", kind = ProcessStreamKind.STDOUT))
        }
        val snapshot = buffer.snapshot()
        assertEquals(3, snapshot.size)
        assertEquals(listOf("line-2", "line-3", "line-4"), snapshot.map { it.text })
        assertEquals(2, buffer.droppedCount)
    }

    @Test
    fun `buffer drops oldest lines when over the character limit`() {
        val buffer = TerminalBuffer(maxLines = 100, maxChars = 10)
        buffer.append(TerminalBufferLine("1", "abcd", ProcessStreamKind.STDOUT))
        buffer.append(TerminalBufferLine("2", "efgh", ProcessStreamKind.STDOUT))
        buffer.append(TerminalBufferLine("3", "ijkl", ProcessStreamKind.STDOUT))
        val snapshot = buffer.snapshot()
        assertTrue(snapshot.sumOf { it.text.length } <= 10)
        assertTrue(buffer.droppedCount >= 1)
        assertEquals("ijkl", snapshot.last().text)
    }

    @Test
    fun `clear resets dropped count`() {
        val buffer = TerminalBuffer(maxLines = 1, maxChars = 1_000)
        buffer.append(TerminalBufferLine("1", "a", ProcessStreamKind.STDOUT))
        buffer.append(TerminalBufferLine("2", "b", ProcessStreamKind.STDOUT))
        assertEquals(1, buffer.droppedCount)
        buffer.clear()
        assertEquals(0, buffer.size)
        assertEquals(0, buffer.droppedCount)
    }
}

class ProcessEnvironmentBuilderTest {

    @Test
    fun `secret names are never copied into the process environment`() {
        val env = ProcessEnvironment(
            variables = mapOf(
                "PATH" to "/system/bin",
                "API_KEY" to "secret-value",
                "GITHUB_TOKEN" to "tok",
                "FOO" to "bar",
            ),
            inheritParent = false,
        )
        val built = ProcessEnvironmentBuilder.build(env, workingDirectory = "/tmp")
        assertEquals("/system/bin", built["PATH"])
        assertEquals("bar", built["FOO"])
        assertEquals("/tmp", built["PWD"])
        assertNull(built["API_KEY"])
        assertNull(built["GITHUB_TOKEN"])
        assertFalse(built.values.any { it == "secret-value" || it == "tok" })
    }

    @Test
    fun `inheritParent still strips credential-like names`() {
        val built = ProcessEnvironmentBuilder.build(
            environment = ProcessEnvironment(inheritParent = true),
            workingDirectory = "/tmp",
        )
        assertTrue(built.keys.none { ProcessEnvironmentBuilder.looksSecret(it) })
        assertTrue(built.containsKey("PATH"))
        assertEquals("/tmp", built["PWD"])
    }
}

class WorkspaceShellLocationsTest {

    @Test
    fun `real filesystem paths are accepted`() {
        val location = WorkspaceShellLocations.resolve("/storage/emulated/0/MyProject", null)
        val filesystem = location as WorkspaceShellLocation.Filesystem
        assertEquals("/storage/emulated/0/MyProject", filesystem.path)
    }

    @Test
    fun `file URIs unwrap to a filesystem path`() {
        val location = WorkspaceShellLocations.resolve("file:///tmp/project", null)
        assertEquals("/tmp/project", (location as WorkspaceShellLocation.Filesystem).path)
    }

    @Test
    fun `SAF content URIs are not converted into fake paths`() {
        val handle = "content://com.android.externalstorage.documents/tree/primary%3AMyProject"
        val location = WorkspaceShellLocations.resolve(handle, "MyProject")
        val unavailable = location as WorkspaceShellLocation.Unavailable
        assertTrue(unavailable.reason.contains("Storage Access Framework"))
        assertFalse(unavailable.reason.contains("/storage/emulated/0/MyProject"))
    }
}

class InteractiveShellSessionTest {

    private fun session(runtime: FakeProcessRuntime) = DefaultInteractiveShellSession(
        runtime = runtime,
        shells = ShellFinder { "/system/bin/sh" },
    )

    private fun request(path: String = "/tmp"): ShellLaunchRequest = ShellLaunchRequest(
        workingDirectory = path,
        workspaceLocation = WorkspaceShellLocation.Filesystem(path),
    )

    @Test
    fun `session starts running and records the working directory`() = runBlocking {
        val runtime = FakeProcessRuntime()
        val session = session(runtime)
        assertNull(session.start(request("/tmp")))
        assertEquals(TerminalSessionState.RUNNING, session.state.value)
        assertEquals("/tmp", session.workingDirectory.value)
        assertEquals("/system/bin/sh", runtime.lastSpec?.executable)
        assertEquals("/tmp", runtime.lastSpec?.workingDirectory)
        session.close()
    }

    @Test
    fun `stdout and stderr stream as events while the session is alive`() = runBlocking {
        val runtime = FakeProcessRuntime()
        val session = session(runtime)
        session.start(request())
        val process = runtime.started.single()
        process.emitStdout("hello-out\n")
        process.emitStderr("hello-err\n")
        withTimeout(2_000) {
            session.events.first { it.text == "hello-out" && it.kind == ProcessStreamKind.STDOUT }
            session.events.first { it.text == "hello-err" && it.kind == ProcessStreamKind.STDERR }
        }
        session.close()
    }

    @Test
    fun `submitted commands stay in the same process stdin`() = runBlocking {
        val runtime = FakeProcessRuntime()
        val session = session(runtime)
        session.start(request())
        session.submit("cd /tmp")
        session.submit("pwd")
        val process = runtime.started.single()
        eventually { process.stdinText.contains("cd /tmp") && process.stdinText.contains("pwd") }
        assertEquals(1, runtime.started.size)
        session.close()
    }

    @Test
    fun `cancellation destroys the process`() = runBlocking {
        val runtime = FakeProcessRuntime()
        val session = session(runtime)
        session.start(request())
        session.close()
        assertEquals(TerminalSessionState.CANCELLED, session.state.value)
        assertFalse(runtime.started.single().alive)
    }

    @Test
    fun `process exit is recorded without crashing`() = runBlocking {
        val runtime = FakeProcessRuntime()
        val session = session(runtime)
        session.start(request())
        runtime.started.single().complete(7)
        eventually { session.state.value == TerminalSessionState.EXITED }
        assertEquals(7, session.exitCode)
        session.close()
    }

    @Test
    fun `shell unavailable is a failed session`() = runBlocking {
        val session = DefaultInteractiveShellSession(
            runtime = FakeProcessRuntime(),
            shells = ShellFinder { null },
        )
        val error = session.start(request())
        assertEquals(WorkspaceErrorCode.SHELL_UNAVAILABLE, error?.code)
        assertEquals(TerminalSessionState.FAILED, session.state.value)
        session.close()
    }

    @Test
    fun `start failure becomes process failed`() = runBlocking {
        val runtime = FakeProcessRuntime()
        runtime.failStart = WorkspaceError(WorkspaceErrorCode.PROCESS_FAILED, "broken pipe")
        val session = session(runtime)
        val error = session.start(request())
        assertEquals(WorkspaceErrorCode.PROCESS_FAILED, error?.code)
        assertEquals(TerminalSessionState.FAILED, session.state.value)
        session.close()
    }

    @Test
    fun `restart replaces the underlying process`() = runBlocking {
        val runtime = FakeProcessRuntime()
        val session = session(runtime)
        session.start(request("/tmp"))
        val first = runtime.started.single()
        session.restart(request("/tmp"))
        assertEquals(2, runtime.started.size)
        assertFalse(first.alive)
        assertTrue(runtime.started.last().alive)
        assertEquals(TerminalSessionState.RUNNING, session.state.value)
        session.close()
    }

    @Test
    fun `SAF workspaces start with an explicit limitation`() = runBlocking {
        val runtime = FakeProcessRuntime()
        val session = session(runtime)
        val location = WorkspaceShellLocations.resolve(
            "content://com.android.externalstorage.documents/tree/primary%3AProj",
            "Proj",
        )
        session.start(ShellLaunchRequest(workingDirectory = null, workspaceLocation = location))
        assertNotNull(session.workspaceLimitation)
        assertTrue(session.workspaceLimitation!!.contains("Storage Access Framework"))
        session.close()
    }

    @Test
    fun `session manager reuses one live session per workspace`() = runBlocking {
        val runtime = FakeProcessRuntime()
        val manager = DefaultTerminalSessionManager(runtime, shells = ShellFinder { "/system/bin/sh" })
        val first = manager.sessionForWorkspace("ws-1", request())
        val second = manager.sessionForWorkspace("ws-1", request())
        assertEquals(first.id, second.id)
        assertEquals(1, runtime.started.size)
        manager.closeAll()
    }

    @Test
    fun `bounded buffer is visible on the session snapshot`() = runBlocking {
        val runtime = FakeProcessRuntime()
        val buffer = TerminalBuffer(maxLines = 2, maxChars = 10_000)
        val session = DefaultInteractiveShellSession(
            runtime = runtime,
            shells = ShellFinder { "/system/bin/sh" },
            buffer = buffer,
        )
        session.start(request())
        val process = runtime.started.single()
        process.emitStdout("one\n")
        process.emitStdout("two\n")
        process.emitStdout("three\n")
        eventually { session.snapshot().droppedCount >= 1 }
        val lines = session.snapshot().lines.filter { it.kind == ProcessStreamKind.STDOUT }
        assertEquals(listOf("two", "three"), lines.map { it.text })
        session.close()
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
    fun `working directory and environment persist inside one shell session`() = runBlocking {
        val executable = shell ?: return@runBlocking
        val dir = File(System.getProperty("java.io.tmpdir") ?: "/tmp")
        val runtime = JvmProcessRuntime()
        val session = DefaultInteractiveShellSession(
            runtime = runtime,
            shells = ShellFinder { executable },
        )
        val error = session.start(
            ShellLaunchRequest(
                workingDirectory = dir.absolutePath,
                workspaceLocation = WorkspaceShellLocation.Filesystem(dir.absolutePath),
            ),
        )
        assertNull(error)
        session.submit("pwd")
        withTimeout(4_000) {
            session.events.first { it.kind == ProcessStreamKind.STDOUT && it.text.contains(dir.absolutePath) }
        }
        session.submit("export CODER_SESSION_FLAG=persisted")
        session.submit("echo \$CODER_SESSION_FLAG")
        withTimeout(4_000) {
            session.events.first { it.kind == ProcessStreamKind.STDOUT && it.text.contains("persisted") }
        }
        session.close()
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

private suspend fun eventually(timeoutMs: Long = 2_000, condition: () -> Boolean) {
    val ok = withTimeoutOrNull(timeoutMs) {
        while (!condition()) {
            delay(10)
        }
        true
    }
    assertTrue(ok == true, "condition was not met in time")
}
