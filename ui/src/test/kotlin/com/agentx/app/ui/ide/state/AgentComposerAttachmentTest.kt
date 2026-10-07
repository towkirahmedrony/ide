package com.agentx.app.ui.ide.state

import com.agentx.app.context.AgentAttachment
import com.agentx.app.context.AgentAttachmentKind
import com.agentx.app.skills.DefaultSkillManager
import com.agentx.app.skills.InMemorySkillStore
import com.agentx.app.skills.SkillDefinition
import com.agentx.app.skills.SkillSource
import com.agentx.app.ui.ide.data.AgentSession
import com.agentx.app.ui.ide.data.AgentStreamEvent
import com.agentx.app.ui.ide.data.AttachmentPickOutcome
import com.agentx.app.ui.ide.data.AttachmentPicker
import com.agentx.app.ui.ide.model.AttachmentUiModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the composer can send, and what it sends it with.
 *
 * These cover the UI half of the attachment feature: Send has to work for an attachment with no
 * text (and not for a skill selection with neither), a pick that fails must not become an
 * attachment, and whatever is showing as chips has to reach the request the agent runs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentComposerAttachmentTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun attachment(
        path: String = ".agentx/attachments/notes.md",
        kind: AgentAttachmentKind = AgentAttachmentKind.DOCUMENT,
        sizeBytes: Long = 1_200L,
    ) = AgentAttachment(
        id = AgentAttachment.idFor(path),
        displayName = path.substringAfterLast('/'),
        path = path,
        mimeType = "text/markdown",
        sizeBytes = sizeBytes,
        kind = kind,
    )

    /** Records exactly what the composer handed the session. */
    private class RecordingSession : AgentSession {
        var lastPrompt: String? = null
        var lastAttachments: List<AgentAttachment> = emptyList()
        var lastSkillIds: Set<String>? = null
        var ran = false

        override suspend fun run(
            input: String,
            onEvent: (AgentStreamEvent) -> Unit,
            workspaceId: String?,
            selectedFile: String?,
            attachments: List<AgentAttachment>,
            skillIds: Set<String>?,
        ) {
            ran = true
            lastPrompt = input
            lastAttachments = attachments
            lastSkillIds = skillIds
            onEvent(AgentStreamEvent.Completed(text = "done"))
        }
    }

    private class FakePicker(
        private val outcome: AttachmentPickOutcome,
    ) : AttachmentPicker {
        var lastKind: AgentAttachmentKind? = null
            private set

        override fun pick(kind: AgentAttachmentKind, onResult: (AttachmentPickOutcome) -> Unit) {
            lastKind = kind
            onResult(outcome)
        }
    }

    private fun skills(vararg definitions: SkillDefinition) =
        DefaultSkillManager(builtins = definitions.toList(), store = InMemorySkillStore())

    private fun skill(id: String, roles: Set<String> = setOf("MAIN")) = SkillDefinition(
        id = id,
        name = "Skill $id",
        description = "Description for $id",
        instructions = "follow the $id instructions",
        source = SkillSource.BUILTIN,
        roles = roles,
    )

    private fun viewModel(
        session: AgentSession = RecordingSession(),
        picker: AttachmentPicker? = null,
        skillManager: DefaultSkillManager? = null,
    ) = AgentViewModel(
        session = session,
        projectId = "p1",
        now = { 0L },
        ioDispatcher = Dispatchers.Unconfined,
        attachmentPicker = picker,
        skills = skillManager,
    )

    // --- send conditions -----------------------------------------------------

    @Test
    fun `an attachment alone can be sent`() = runBlocking {
        val session = RecordingSession()
        val model = viewModel(session, FakePicker(AttachmentPickOutcome.Attached(attachment())))

        model.pickAttachment(AgentAttachmentKind.DOCUMENT)
        assertEquals(1, model.uiState.attachments.size)
        // No text at all, and Send is still available.
        assertTrue(model.uiState.canSend)
        assertTrue(model.uiState.hasContent)

        model.send()

        assertEquals("", model.uiState.input)
        assertTrue(session.ran)
        assertEquals(listOf(".agentx/attachments/notes.md"), session.lastAttachments.map { it.path })
    }

    @Test
    fun `text and an attachment can be sent together`() = runBlocking {
        val session = RecordingSession()
        val model = viewModel(session, FakePicker(AttachmentPickOutcome.Attached(attachment())))

        model.pickAttachment(AgentAttachmentKind.DOCUMENT)
        model.onInputChange("summarise this")
        model.send()

        assertEquals("summarise this", session.lastPrompt)
        assertEquals(1, session.lastAttachments.size)
    }

    @Test
    fun `a skill selection on its own is not a request`() = runBlocking {
        val session = RecordingSession()
        val manager = skills(skill("code-review"))
        manager.setEnabled("code-review", true)
        val model = viewModel(session, skillManager = manager)

        model.refreshSkills()
        model.toggleSkill("code-review")

        assertEquals(setOf("code-review"), model.uiState.selectedSkillIds)
        // Choosing how to work is not a task: with no text and no attachment there is nothing to send.
        assertFalse(model.uiState.canSend)
        model.send()
        assertFalse(session.ran)
    }

    @Test
    fun `sending consumes the attachments and clears them from the composer`() = runBlocking {
        val model = viewModel(RecordingSession(), FakePicker(AttachmentPickOutcome.Attached(attachment())))

        model.pickAttachment(AgentAttachmentKind.DOCUMENT)
        model.send()

        assertTrue(model.uiState.attachments.isEmpty())
        assertEquals(null, model.uiState.selectedSkillIds)
    }

    // --- picking -------------------------------------------------------------

    @Test
    fun `a pick that fails is reported and adds nothing`() = runBlocking {
        val model = viewModel(
            RecordingSession(),
            FakePicker(AttachmentPickOutcome.Failed("AgentX cannot read \"report.pdf\" as text.")),
        )

        model.pickAttachment(AgentAttachmentKind.DOCUMENT)

        assertTrue(model.uiState.attachments.isEmpty())
        assertEquals("AgentX cannot read \"report.pdf\" as text.", model.uiState.attachmentMessage)
        assertFalse(model.uiState.canSend)
    }

    @Test
    fun `cancelling a pick changes nothing`() = runBlocking {
        val model = viewModel(RecordingSession(), FakePicker(AttachmentPickOutcome.Cancelled))

        model.pickAttachment(AgentAttachmentKind.FILE)

        assertTrue(model.uiState.attachments.isEmpty())
        assertNull(model.uiState.attachmentMessage)
        assertFalse(model.uiState.pickingAttachment)
    }

    @Test
    fun `the picked kind reaches the picker`() = runBlocking {
        val picker = FakePicker(AttachmentPickOutcome.Cancelled)
        val model = viewModel(RecordingSession(), picker)

        model.pickAttachment(AgentAttachmentKind.IMAGE)

        assertEquals(AgentAttachmentKind.IMAGE, picker.lastKind)
    }

    @Test
    fun `picking the same file twice keeps one chip`() = runBlocking {
        val same = attachment()
        val model = viewModel(RecordingSession(), FakePicker(AttachmentPickOutcome.Attached(same)))

        model.pickAttachment(AgentAttachmentKind.DOCUMENT)
        model.pickAttachment(AgentAttachmentKind.DOCUMENT)

        assertEquals(1, model.uiState.attachments.size)
    }

    @Test
    fun `attaching without a picker says so instead of failing silently`() = runBlocking {
        val model = viewModel(RecordingSession(), picker = null)

        model.pickAttachment(AgentAttachmentKind.FILE)

        assertNotNull(model.uiState.attachmentMessage)
        assertTrue(model.uiState.attachments.isEmpty())
    }

    // --- removing ------------------------------------------------------------

    @Test
    fun `an attachment can be removed before sending`() = runBlocking {
        val model = viewModel(RecordingSession(), FakePicker(AttachmentPickOutcome.Attached(attachment())))

        model.pickAttachment(AgentAttachmentKind.DOCUMENT)
        val id = model.uiState.attachments.single().id
        model.removeAttachment(id)

        assertTrue(model.uiState.attachments.isEmpty())
        assertFalse(model.uiState.canSend)
    }

    @Test
    fun `a skill can be turned off again`() = runBlocking {
        val manager = skills(skill("code-review"), skill("testing"))
        manager.setEnabled("code-review", true)
        manager.setEnabled("testing", true)
        val model = viewModel(skillManager = manager)

        model.refreshSkills()
        model.toggleSkill("code-review")
        model.toggleSkill("testing")
        assertEquals(setOf("code-review", "testing"), model.uiState.selectedSkillIds)

        model.toggleSkill("testing")

        assertEquals(setOf("code-review"), model.uiState.selectedSkillIds)
    }

    // --- skills offered ------------------------------------------------------

    @Test
    fun `only skills the agent could use are offered`() = runBlocking {
        val manager = skills(
            skill("usable"),
            skill("other-role", roles = setOf("REVIEWER")),
            skill("broken").copy(problems = listOf("front matter is missing a 'name'")),
        )
        manager.setEnabled("usable", true)
        manager.setEnabled("other-role", true)
        manager.setEnabled("broken", true)
        val model = viewModel(skillManager = manager)

        model.refreshSkills()

        // A disabled, invalid or unassigned skill is not a control that does nothing: it is absent.
        assertEquals(listOf("usable"), model.uiState.skills.map { it.id })
    }

    @Test
    fun `selected skills reach the request as their ids`() = runBlocking {
        val session = RecordingSession()
        val manager = skills(skill("code-review"))
        manager.setEnabled("code-review", true)
        val model = viewModel(session, skillManager = manager)

        model.refreshSkills()
        model.toggleSkill("code-review")
        model.onInputChange("review my change")
        model.send()

        assertEquals(setOf("code-review"), session.lastSkillIds)
    }

    @Test
    fun `a turn with no skills selected passes null, keeping the role's own set`() = runBlocking {
        val session = RecordingSession()
        val model = viewModel(session, skillManager = skills(skill("code-review")))

        model.onInputChange("do the thing")
        model.send()

        assertNull(session.lastSkillIds)
    }

    // --- transcript ----------------------------------------------------------

    @Test
    fun `the sent message shows what it carried`() = runBlocking {
        val model = viewModel(RecordingSession(), FakePicker(AttachmentPickOutcome.Attached(attachment())))

        model.pickAttachment(AgentAttachmentKind.DOCUMENT)
        model.send()

        val userMessage = model.uiState.messages.first { it.isUser }
        assertEquals(listOf("notes.md"), userMessage.attachments.map { it.displayName })
        assertEquals("Document", userMessage.attachments.single().typeLabel)
    }

    @Test
    fun `compact sizes are shown only when they say something`() = runBlocking {
        val model = viewModel(
            RecordingSession(),
            FakePicker(AttachmentPickOutcome.Attached(attachment(sizeBytes = 2_048L))),
        )
        model.pickAttachment(AgentAttachmentKind.DOCUMENT)

        assertEquals("2 KB", model.uiState.attachments.single().sizeLabel)
        // An unknown size is nothing to say, so the chip shows only the name.
        assertEquals(null, AttachmentUiModel(attachment(sizeBytes = 0L)).sizeLabel)
    }
}

