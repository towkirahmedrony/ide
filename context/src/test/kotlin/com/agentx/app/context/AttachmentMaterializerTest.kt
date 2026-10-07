package com.agentx.app.context

import com.agentx.app.core.ForgeResult
import com.agentx.app.core.errorOrNull
import com.agentx.app.core.failure
import com.agentx.app.core.success
import com.agentx.app.core.valueOrNull
import com.agentx.app.workspace.WorkspaceErrorCode
import com.agentx.app.workspace.memory.InMemoryWorkspaceFileSystem
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Attaching a file that came from outside the workspace.
 *
 * The reader is faked — no test here touches a device — but everything downstream is real: the
 * workspace filesystem, the path rules and [ProtectedPaths]. So these cover the claims that
 * matter: an attachment lands inside the workspace under a safe name, a format AgentX cannot read
 * is refused rather than mangled, and nothing protected becomes readable by attaching it.
 */
class AttachmentMaterializerTest {

    private fun run(block: suspend () -> Unit) = runBlocking { block() }

    private class FakeReader(
        private val content: ExternalContent? = null,
        private val refusal: ExternalReadFailure? = null,
    ) : ExternalContentReader {
        var requestedMaxBytes: Long? = null
            private set

        override suspend fun read(uri: String, maxBytes: Long): ForgeResult<ExternalContent, ExternalReadFailure> {
            requestedMaxBytes = maxBytes
            refusal?.let { return failure(it) }
            val value = content ?: return failure(ExternalReadFailure.EMPTY)
            return success(value)
        }
    }

    private fun content(
        name: String,
        text: String = "hello from $name\n",
        mime: String = "text/plain",
    ) = ExternalContent(
        displayName = name,
        mimeType = mime,
        sizeBytes = text.toByteArray(Charsets.UTF_8).size.toLong(),
        text = text,
    )

    private fun workspace(vararg seed: Pair<String, String>) =
        InMemoryWorkspaceFileSystem(seed.toMap())

    // --- materialisation ----------------------------------------------------

    @Test
    fun `a picked text file is copied into the attachment folder`() = run {
        val files = workspace()
        val materializer = AttachmentMaterializer(FakeReader(content("notes.md")))

        val attachment = assertNotNull(materializer.materialize("content://picked", "notes.md", files).valueOrNull())

        assertEquals(".agentx/attachments/notes.md", attachment.path)
        assertEquals("notes.md", attachment.displayName)
        assertEquals(AgentAttachmentKind.DOCUMENT, attachment.kind)
        assertEquals("text/plain", attachment.mimeType)
        assertEquals(AgentAttachment.idFor(attachment.path), attachment.id)
        assertEquals("hello from notes.md\n", files.readFile(attachment.path).valueOrNull())
    }

    @Test
    fun `a name that is not a document is a plain file`() = run {
        val materializer = AttachmentMaterializer(FakeReader(content("app.log")))

        val attachment = assertNotNull(materializer.materialize("content://picked", "app.log", workspace()).valueOrNull())

        assertEquals(AgentAttachmentKind.FILE, attachment.kind)
    }

    @Test
    fun `the reader is asked for at most the attachment size limit`() = run {
        val reader = FakeReader(content("notes.md"))
        AttachmentMaterializer(reader).materialize("content://picked", "notes.md", workspace())

        assertEquals(AttachmentMaterializer.DEFAULT_MAX_BYTES, reader.requestedMaxBytes)
    }

    @Test
    fun `an existing attachment is never overwritten`() = run {
        val files = workspace(".agentx/attachments/notes.md" to "the original")
        val materializer = AttachmentMaterializer(FakeReader(content("notes.md", "the second one")))

        val attachment = assertNotNull(materializer.materialize("content://picked", "notes.md", files).valueOrNull())

        assertEquals(".agentx/attachments/notes-1.md", attachment.path)
        assertEquals("the original", files.readFile(".agentx/attachments/notes.md").valueOrNull())
        assertEquals("the second one", files.readFile(attachment.path).valueOrNull())
    }

    // --- what may not enter -------------------------------------------------

    @Test
    fun `an image is refused instead of being read as text`() = run {
        val files = workspace()
        val materializer = AttachmentMaterializer(FakeReader(content("photo.png", "not really a png")))

        val error = assertNotNull(materializer.materialize("content://picked", "photo.png", files).errorOrNull())

        assertEquals(WorkspaceErrorCode.UNSUPPORTED_FILE_TYPE, error.code)
        assertTrue(error.message.contains("image"), error.message)
        assertFalse(files.exists(".agentx/attachments/photo.png"))
    }

    @Test
    fun `a binary document is refused and the limitation is stated`() = run {
        val files = workspace()
        val materializer = AttachmentMaterializer(FakeReader(content("report.pdf")))

        val error = assertNotNull(materializer.materialize("content://picked", "report.pdf", files).errorOrNull())

        assertEquals(WorkspaceErrorCode.UNSUPPORTED_FILE_TYPE, error.code)
        assertTrue(error.message.contains("report.pdf"), error.message)
        assertEquals(0, files.list(".agentx/attachments").valueOrNull()?.size ?: 0)
    }

    @Test
    fun `binary bytes behind a text name are refused`() = run {
        val files = workspace()
        val binary = "\u0000".repeat(64) + "\u0001\u0002\u0003"
        val materializer = AttachmentMaterializer(FakeReader(content("notes.txt", binary)))

        val error = assertNotNull(materializer.materialize("content://picked", "notes.txt", files).errorOrNull())

        assertEquals(WorkspaceErrorCode.UNSUPPORTED_FILE_TYPE, error.code)
        assertFalse(files.exists(".agentx/attachments/notes.txt"))
    }

    @Test
    fun `a protected file name is refused`() = run {
        val files = workspace()
        val materializer = AttachmentMaterializer(FakeReader(content("id_rsa")))

        val error = assertNotNull(materializer.materialize("content://picked", "id_rsa", files).errorOrNull())

        assertEquals(WorkspaceErrorCode.PERMISSION_DENIED, error.code)
        assertFalse(files.exists(".agentx/attachments/id_rsa"))
    }

    @Test
    fun `a protected extension is refused`() = run {
        val materializer = AttachmentMaterializer(FakeReader(content("server.pem")))

        val error = assertNotNull(materializer.materialize("content://picked", "server.pem", workspace()).errorOrNull())

        assertEquals(WorkspaceErrorCode.PERMISSION_DENIED, error.code)
    }

    @Test
    fun `a display name cannot choose where the file lands`() = run {
        val files = workspace()
        val materializer = AttachmentMaterializer(FakeReader(content("escape.md")))

        val attachment = assertNotNull(
            materializer.materialize("content://picked", "../../escape.md", files).valueOrNull(),
        )

        assertEquals(".agentx/attachments/escape.md", attachment.path)
        assertFalse(attachment.path.contains(".."))
        assertFalse(files.exists("escape.md"))
    }

    @Test
    fun `a name full of separators falls back instead of escaping`() = run {
        val files = workspace()
        val materializer = AttachmentMaterializer(FakeReader(content("evil.md")))

        val attachment = assertNotNull(
            materializer.materialize("content://picked", "a/b/c/evil.md", files).valueOrNull(),
        )

        assertTrue(attachment.path.startsWith(".agentx/attachments/"), attachment.path)
        assertFalse(attachment.path.contains("a/b"))
    }

    @Test
    fun `an oversized file is refused with a size limit`() = run {
        val materializer = AttachmentMaterializer(FakeReader(refusal = ExternalReadFailure.TOO_LARGE))

        val error = assertNotNull(materializer.materialize("content://picked", "huge.txt", workspace()).errorOrNull())

        assertEquals(WorkspaceErrorCode.FILE_TOO_LARGE, error.code)
    }

    @Test
    fun `an unreadable uri is reported, not thrown`() = run {
        val materializer = AttachmentMaterializer(FakeReader(refusal = ExternalReadFailure.UNREADABLE))

        val error = assertNotNull(materializer.materialize("content://gone", "gone.txt", workspace()).errorOrNull())

        assertEquals(WorkspaceErrorCode.IO_FAILED, error.code)
    }

    @Test
    fun `a revoked permission is reported as a permission problem`() = run {
        val materializer = AttachmentMaterializer(FakeReader(refusal = ExternalReadFailure.PERMISSION_DENIED))

        val error = assertNotNull(materializer.materialize("content://denied", "x.txt", workspace()).errorOrNull())

        assertEquals(WorkspaceErrorCode.PERMISSION_DENIED, error.code)
        assertTrue(error.message.contains("allow access"), error.message)
    }

    // --- files that are already in the workspace ---------------------------

    @Test
    fun `a file already in the workspace is referenced, not copied`() = run {
        val files = workspace("src/Main.kt" to "fun main() = Unit\n")
        val materializer = AttachmentMaterializer(FakeReader(refusal = ExternalReadFailure.UNREADABLE))

        val attachment = assertNotNull(
            materializer.attachExisting("src/Main.kt", files, mimeType = "text/x-kotlin").valueOrNull(),
        )

        assertEquals("src/Main.kt", attachment.path)
        assertEquals(AgentAttachmentKind.FILE, attachment.kind)
        assertEquals("text/x-kotlin", attachment.mimeType)
        // The file itself is untouched and nothing was duplicated into the attachment folder.
        assertEquals("fun main() = Unit\n", files.readFile("src/Main.kt").valueOrNull())
        assertFalse(files.exists(AttachmentMaterializer.ATTACHMENT_DIRECTORY))
    }

    @Test
    fun `attaching a protected workspace file is refused`() = run {
        val files = workspace("config/server.pem" to "key material")
        val materializer = AttachmentMaterializer(FakeReader())

        val error = assertNotNull(materializer.attachExisting("config/server.pem", files).errorOrNull())

        assertEquals(WorkspaceErrorCode.PERMISSION_DENIED, error.code)
    }

    @Test
    fun `attaching a protected directory is refused`() = run {
        val files = workspace(".ssh/id_rsa" to "key material")
        val materializer = AttachmentMaterializer(FakeReader())

        val error = assertNotNull(materializer.attachExisting(".ssh/id_rsa", files).errorOrNull())

        assertEquals(WorkspaceErrorCode.PERMISSION_DENIED, error.code)
    }

    @Test
    fun `attaching a folder is refused`() = run {
        val files = workspace("src/Main.kt" to "fun main() = Unit\n")
        val materializer = AttachmentMaterializer(FakeReader())

        val error = assertNotNull(materializer.attachExisting("src", files).errorOrNull())

        assertEquals(WorkspaceErrorCode.NOT_A_FILE, error.code)
    }

    @Test
    fun `attaching a binary workspace file is refused`() = run {
        val files = workspace("app/icon.png" to "not really a png")
        val materializer = AttachmentMaterializer(FakeReader())

        val error = assertNotNull(materializer.attachExisting("app/icon.png", files).errorOrNull())

        assertEquals(WorkspaceErrorCode.UNSUPPORTED_FILE_TYPE, error.code)
    }

    @Test
    fun `attaching a missing file is reported`() = run {
        val materializer = AttachmentMaterializer(FakeReader())

        val error = assertNotNull(materializer.attachExisting("src/Ghost.kt", workspace()).errorOrNull())

        assertEquals(WorkspaceErrorCode.NOT_FOUND, error.code)
    }

    @Test
    fun `an attached path is always workspace-relative`() = run {
        val files = workspace()
        val materializer = AttachmentMaterializer(FakeReader(content("notes.md")))

        val attachment = assertNotNull(materializer.materialize("content://picked", "notes.md", files).valueOrNull())

        assertFalse(attachment.path.startsWith("/"))
        assertFalse(attachment.path.contains(":"))
    }

    @Test
    fun `the attachment is readable through the workspace filesystem`() = run {
        val files = workspace()
        val materializer = AttachmentMaterializer(FakeReader(content("notes.md", "# Notes\nbody\n")))

        val attachment = assertNotNull(materializer.materialize("content://picked", "notes.md", files).valueOrNull())

        // This is the whole point of materialising through the workspace: the agent reads the
        // attachment with the same tool it reads any other file with.
        val node = assertIs<com.agentx.app.workspace.WorkspaceFile>(files.metadata(attachment.path).valueOrNull())
        assertEquals("notes.md", node.name)
        assertEquals("# Notes\nbody\n", files.readFile(attachment.path).valueOrNull())
    }
}
