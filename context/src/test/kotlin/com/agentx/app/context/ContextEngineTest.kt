package com.agentx.app.context

import com.agentx.app.model.ModelMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContextEngineTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `an explicitly mentioned file outranks a recently used one`() = runTest {
        val files = TestWorkspaceFileSystem(
            mapOf("mentioned.kt" to "class Mentioned", "recent.kt" to "class Recent"),
        )
        val engine = testEngine(
            fileSystem = files,
            snapshot = WorkspaceSnapshot(name = "demo", recentFiles = listOf("recent.kt")),
            now = now,
        )

        val result = engine.buildContext(
            ContextRequest(task = "please update it", mentionedFiles = listOf("mentioned.kt")),
        )

        val ids = result.items.map { it.id }
        assertTrue(ids.indexOf("file:mentioned.kt") < ids.indexOf("file:recent.kt"), "ids=$ids")
        assertTrue(ids.first() == "user:request")
    }

    @Test
    fun `recent files are ordered by recency`() = runTest {
        val files = TestWorkspaceFileSystem(mapOf("a.kt" to "a", "b.kt" to "b", "c.kt" to "c"))
        val engine = testEngine(
            fileSystem = files,
            snapshot = WorkspaceSnapshot(name = "demo", recentFiles = listOf("a.kt", "b.kt", "c.kt")),
            now = now,
        )

        val result = engine.buildContext(ContextRequest(task = "continue"))

        assertEquals(
            listOf("file:a.kt", "file:b.kt", "file:c.kt"),
            result.itemsOf(ContextSource.FILE).map { it.id },
        )
    }

    @Test
    fun `a file named in the request is detected and loaded`() = runTest {
        val files = TestWorkspaceFileSystem(mapOf("src/auth/Login.kt" to "class Login"))
        val engine = testEngine(files, WorkspaceSnapshot(name = "demo"), now)

        val result = engine.buildContext(ContextRequest(task = "please fix src/auth/Login.kt before review"))

        val item = result.items.single { it.source == ContextSource.FILE }
        assertEquals("file:src/auth/Login.kt", item.id)
        assertEquals(ContextReason.MENTIONED_FILE, item.metadata.selectedBecause)
        assertEquals(listOf("src/auth/Login.kt"), files.readPaths)
    }

    @Test
    fun `a path-like word that does not exist is not read`() = runTest {
        val files = TestWorkspaceFileSystem(mapOf("real.kt" to "class Real"))
        val engine = testEngine(files, WorkspaceSnapshot(name = "demo"), now)

        val result = engine.buildContext(ContextRequest(task = "look at missing/NotThere.kt"))

        assertTrue(result.itemsOf(ContextSource.FILE).isEmpty())
        assertTrue(files.readPaths.isEmpty())
        assertTrue(result.excluded.isEmpty())
    }

    @Test
    fun `protected files are excluded before they are read`() = runTest {
        val secretValue = "sk-live-should-never-appear"
        val files = TestWorkspaceFileSystem(
            mapOf(
                ".env" to "OPENAI_API_KEY=$secretValue",
                "secrets.json" to "{\"token\":\"$secretValue\"}",
                "id_rsa" to secretValue,
                "certs/server.pem" to secretValue,
                "src/App.kt" to "class App",
            ),
        )
        val engine = testEngine(files, WorkspaceSnapshot(name = "demo"), now)

        val result = engine.buildContext(
            ContextRequest(
                task = "read them",
                mentionedFiles = listOf(".env", "secrets.json", "id_rsa", "certs/server.pem", "src/App.kt"),
            ),
        )

        val protectedPaths = result.excluded
            .filter { it.reason == ContextExclusionReason.PROTECTED_PATH }
            .mapNotNull { it.path }
        assertTrue(protectedPaths.containsAll(listOf(".env", "secrets.json", "id_rsa", "certs/server.pem")))
        assertTrue(files.readPaths.none { it in setOf(".env", "secrets.json", "id_rsa", "certs/server.pem") })
        assertFalse(result.text.contains(secretValue))
        assertTrue(result.items.any { it.id == "file:src/App.kt" })
    }

    @Test
    fun `an empty workspace still produces the request`() = runTest {
        val files = TestWorkspaceFileSystem(emptyMap())
        val engine = testEngine(files, WorkspaceSnapshot(name = "empty"), now)

        val result = engine.buildContext(ContextRequest(task = "anything"))

        assertEquals("user:request", result.items.first().id)
        assertTrue(result.itemsOf(ContextSource.FILE).isEmpty())
        assertEquals("(empty directory)", result.itemsOf(ContextSource.DIRECTORY).single().content)
        assertTrue(files.readPaths.isEmpty())
        assertTrue(result.excluded.isEmpty())
    }

    @Test
    fun `no workspace means no file or directory context`() = runTest {
        val engine = testEngine(fileSystem = null, snapshot = null, now = now)

        val result = engine.buildContext(ContextRequest(task = "anything"))

        assertEquals(listOf("user:request"), result.items.map { it.id })
        assertTrue(result.text.contains("anything"))
    }

    @Test
    fun `a large file is truncated and reported`() = runTest {
        val content = "line\n".repeat(4_000)
        val files = TestWorkspaceFileSystem(mapOf("big.kt" to content))
        val engine = testEngine(files, WorkspaceSnapshot(name = "demo"), now, ContextBudget(maxFileChars = 200))

        val result = engine.buildContext(ContextRequest(task = "look at big.kt", mentionedFiles = listOf("big.kt")))

        val item = result.items.single { it.source == ContextSource.FILE }
        assertTrue(item.truncated)
        assertEquals(content.length, item.originalChars)
        assertTrue(item.chars < content.length)
        assertTrue(item.content.contains("truncated"))
        assertTrue(result.truncated.any { it.id == "file:big.kt" })
        assertTrue(result.text.contains("(truncated)"))
        assertTrue(result.usedChars <= result.limitChars)
    }

    @Test
    fun `context generation is deterministic`() = runTest {
        val files = TestWorkspaceFileSystem(mapOf("a.kt" to "class A", "b.kt" to "class B"))
        val engine = testEngine(
            fileSystem = files,
            snapshot = WorkspaceSnapshot(name = "demo", recentFiles = listOf("b.kt")),
            now = now,
        )
        val request = ContextRequest(task = "fix a.kt", mentionedFiles = listOf("a.kt"))

        val first = engine.buildContext(request)
        val second = engine.buildContext(request)

        assertEquals(first.selectedIds, second.selectedIds)
        assertEquals(first.text, second.text)
        assertEquals(first.usedChars, second.usedChars)
        assertEquals(first.excluded, second.excluded)
        assertEquals(first.truncated, second.truncated)
    }

    @Test
    fun `one agent task reads each file once`() = runTest {
        val files = TestWorkspaceFileSystem(mapOf("a.kt" to "class A"))
        val engine = testEngine(files, WorkspaceSnapshot(name = "demo"), now)
        val request = ContextRequest(task = "fix a.kt", mentionedFiles = listOf("a.kt"))

        engine.buildContext(request)
        engine.buildContext(request)
        engine.buildContext(request)

        assertEquals(listOf("a.kt"), files.readPaths)
    }

    @Test
    fun `loaded files are reported back as recently used`() = runTest {
        val files = TestWorkspaceFileSystem(mapOf("a.kt" to "class A"))
        val workspace = TestWorkspaceContextProvider(WorkspaceSnapshot(name = "demo"), files)
        val engine = testEngine(now = now, workspace = workspace)

        engine.buildContext(ContextRequest(task = "fix a.kt", mentionedFiles = listOf("a.kt")))

        assertEquals(listOf("a.kt"), workspace.accessed)
    }

    @Test
    fun `conversation context is ranked last and bounded`() = runTest {
        val engine = testEngine(now = now)

        val result = engine.buildContext(
            ContextRequest(
                task = "carry on",
                conversation = listOf(
                    ModelMessage.user("first"),
                    ModelMessage.assistant("second"),
                    ModelMessage.user("third"),
                ),
                budget = ContextBudget(maxConversationMessages = 2),
            ),
        )

        val conversation = result.itemsOf(ContextSource.CONVERSATION)
        assertEquals(2, conversation.size)
        assertEquals("third", conversation.first().content)
        assertTrue(result.excluded.any { it.reason == ContextExclusionReason.OVER_CONVERSATION_LIMIT })
        assertEquals(ContextSource.CONVERSATION, result.items.last().source)
    }

    @Test
    fun `session items can be added replaced and removed`() {
        val engine = DefaultContextEngine()

        val added = engine.addItems("session", listOf(contextItem("file:a.kt", ContextSource.FILE, ContextPriority.HIGH, 100.0)))
        assertEquals(listOf("file:a.kt"), added.map { it.id })
        assertEquals(listOf("file:a.kt"), engine.items("session").map { it.id })

        val replaced = engine.addItems(
            "session",
            listOf(contextItem("file:a.kt", ContextSource.FILE, ContextPriority.HIGH, 42.0)),
        )
        assertEquals(1, replaced.size)
        assertEquals(42.0, replaced.single().relevance)

        assertEquals(emptyList(), engine.removeItems("session", listOf("file:a.kt")))
        assertEquals(emptyList(), engine.items("session"))

        engine.addItems("session", listOf(contextItem("file:b.kt", ContextSource.FILE)))
        engine.clearSession("session")
        assertEquals(emptyList(), engine.items("session"))
    }

    @Test
    fun `the debug report explains the selection without exposing content`() = runTest {
        val files = TestWorkspaceFileSystem(mapOf("a.kt" to "class Interesting"))
        val engine = testEngine(files, WorkspaceSnapshot(name = "demo"), now)

        val result = engine.buildContext(
            ContextRequest(task = "look at a.kt", mentionedFiles = listOf("a.kt", ".env")),
        )
        val report = engine.debug(result)

        assertTrue(report.lines.any { it.contains("file:a.kt") && it.contains("MENTIONED_FILE") })
        assertTrue(report.lines.any { it.contains(ContextExclusionReason.PROTECTED_PATH.name) })
        assertTrue(report.summary.contains("items"))
        assertFalse(report.toString().contains("class Interesting"))

        val truncated = engine.debug(
            engine.buildContext(
                ContextRequest(
                    task = "look at a.kt",
                    mentionedFiles = listOf("a.kt"),
                    budget = ContextBudget(maxFileChars = 5),
                ),
            ),
        )
        assertTrue(truncated.lines.any { it.contains("truncated") })
    }

    @Test
    fun `context construction is cancellable`() = runBlocking {
        val files = TestWorkspaceFileSystem(
            mapOf("a.kt" to "class A"),
            beforeRead = { delay(10_000) },
        )
        val engine = testEngine(files, WorkspaceSnapshot(name = "demo"), 1L)

        val pending = async {
            engine.buildContext(ContextRequest(task = "fix a.kt", mentionedFiles = listOf("a.kt")))
        }
        delay(50)
        pending.cancel()

        val thrown = runCatching { pending.await() }.exceptionOrNull()
        assertTrue(thrown is CancellationException, "expected cancellation, got $thrown")
    }
}
