package com.agentx.app.agent.conversation

import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.domain.AgentStatus
import com.agentx.app.agent.domain.AgentTask
import com.agentx.app.agent.domain.AgentSession
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Durable, session-scoped persistence: one file per session so a delete can
 * never reach another session's transcript, and a reload restores everything.
 */
class FilesystemConversationStoreTest {

    private lateinit var root: File

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("agentx-conversations").toFile()
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun session(id: String) = AgentSession(
        id = id,
        role = AgentRole.MAIN,
        status = AgentStatus.IDLE,
        task = AgentTask(id = "task-$id", prompt = ""),
        createdAtMillis = 1L,
        updatedAtMillis = 2L,
        workspaceId = "ws",
        title = "Session $id",
    )

    private fun conversationWith(id: String, vararg texts: String): AgentConversation {
        val messages = texts.mapIndexed { index, text ->
            ConversationMessage(
                id = "$id-m$index",
                sessionId = id,
                role = if (index % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT,
                content = MessageContent(text = text),
                metadata = MessageMetadata(status = MessageStatus.COMPLETED, timestampMillis = index.toLong()),
            )
        }
        return AgentConversation(session = session(id), messages = messages)
    }

    @Test
    fun `a saved conversation is found again`() {
        val store = FilesystemConversationStore(root)
        store.save(conversationWith("sess-a", "hello", "hi there"))

        val found = store.find("sess-a")

        assertNotNull(found)
        assertEquals("sess-a", found.id)
        assertEquals(listOf("hello", "hi there"), found.messages.map { it.content.text })
        assertEquals("Session sess-a", found.session.title)
        assertEquals("ws", found.workspaceId)
    }

    @Test
    fun `history survives a new store instance, as after an app restart`() {
        FilesystemConversationStore(root).save(conversationWith("sess-a", "Find the authentication code."))

        val reopened = FilesystemConversationStore(root).find("sess-a")

        assertNotNull(reopened)
        assertEquals(listOf("Find the authentication code."), reopened.messages.map { it.content.text })
    }

    @Test
    fun `all returns every stored session`() {
        val store = FilesystemConversationStore(root)
        store.save(conversationWith("sess-a", "a"))
        store.save(conversationWith("sess-b", "b"))

        assertEquals(setOf("sess-a", "sess-b"), store.all().map { it.id }.toSet())
    }

    @Test
    fun `deleting one session leaves the other file on disk`() {
        val store = FilesystemConversationStore(root)
        store.save(conversationWith("sess-a", "terminal debugging"))
        store.save(conversationWith("sess-b", "UI redesign"))

        assertTrue(store.delete("sess-a"))

        assertNull(store.find("sess-a"))
        assertNotNull(store.find("sess-b"))
        assertEquals(listOf("sess-b"), store.all().map { it.id })
        assertFalse(File(root, "sess-a.json").exists())
    }

    @Test
    fun `deleting an unknown session reports false`() {
        assertFalse(FilesystemConversationStore(root).delete("missing"))
    }

    @Test
    fun `an unaddressable session id can never touch a file`() {
        val store = FilesystemConversationStore(root)
        store.save(conversationWith("sess-a", "keep me"))

        assertNull(store.find(""))
        assertNull(store.find("../sess-a"))
        assertNull(store.find("_active"))
        assertFalse(store.delete(""))
        assertNotNull(store.find("sess-a"))
    }

    @Test
    fun `sub-agent sessions are stored independently`() {
        val store = FilesystemConversationStore(root)
        store.save(conversationWith("sess-a", "parent"))
        store.save(
            conversationWith("sess-child", "child detail").copy(
                session = session("sess-child").copy(
                    parentSessionId = "sess-a",
                    role = AgentRole.EXPLORER,
                ),
            ),
        )

        assertEquals(listOf("child detail"), store.find("sess-child")!!.messages.map { it.content.text })
        assertEquals("sess-a", store.find("sess-child")!!.session.parentSessionId)
        assertEquals(listOf("parent"), store.find("sess-a")!!.messages.map { it.content.text })
    }

    @Test
    fun `tool and summary payloads round-trip through the file`() {
        val store = FilesystemConversationStore(root)
        val conversation = AgentConversation(
            session = session("sess-a"),
            messages = listOf(
                ConversationMessage(
                    id = "m-0",
                    sessionId = "sess-a",
                    role = MessageRole.TOOL,
                    content = MessageContent(
                        text = "SearchFiles",
                        toolName = "SearchFiles",
                        toolArguments = """{"query":"login"}""",
                        toolResult = "3 matches",
                        toolCallId = "call-1",
                    ),
                    metadata = MessageMetadata(
                        status = MessageStatus.COMPLETED,
                        timestampMillis = 7L,
                        toolSuccess = true,
                    ),
                ),
            ),
            summary = SessionSummary(currentTask = "Fix project file access.", discoveredFiles = listOf("Auth.kt")),
            taskState = SessionTaskState(activeTask = "Fix project file access.", currentStep = "Add tests"),
        )
        store.save(conversation)

        val restored = FilesystemConversationStore(root).find("sess-a")

        assertNotNull(restored)
        val tool = restored.messages.single()
        assertEquals("SearchFiles", tool.content.toolName)
        assertEquals("""{"query":"login"}""", tool.content.toolArguments)
        assertEquals("3 matches", tool.content.toolResult)
        assertEquals("call-1", tool.content.toolCallId)
        assertEquals(true, tool.metadata.toolSuccess)
        assertEquals("Fix project file access.", restored.summary.currentTask)
        assertEquals(listOf("Auth.kt"), restored.summary.discoveredFiles)
        assertEquals("Add tests", restored.taskState.currentStep)
    }

    @Test
    fun `the active session per workspace is remembered`() {
        val store = FilesystemConversationStore(root)
        store.save(conversationWith("sess-a", "a"))

        store.setActiveSessionId("ws", "sess-a")

        assertEquals("sess-a", FilesystemConversationStore(root).activeSessionId("ws"))
        assertNull(store.activeSessionId("other"))
    }

    @Test
    fun `the active-session file is not mistaken for a session`() {
        val store = FilesystemConversationStore(root)
        store.save(conversationWith("sess-a", "a"))
        store.setActiveSessionId("ws", "sess-a")

        assertEquals(listOf("sess-a"), store.all().map { it.id })
    }

    @Test
    fun `switching the active session replaces the previous one`() {
        val store = FilesystemConversationStore(root)
        store.setActiveSessionId("ws", "sess-a")
        store.setActiveSessionId("ws", "sess-b")

        assertEquals("sess-b", store.activeSessionId("ws"))

        store.setActiveSessionId("ws", null)
        assertNull(store.activeSessionId("ws"))
    }
}
