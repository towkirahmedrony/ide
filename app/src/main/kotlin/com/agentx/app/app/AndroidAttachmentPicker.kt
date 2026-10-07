package com.agentx.app.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.agentx.app.context.AgentAttachmentKind
import com.agentx.app.context.AttachmentMaterializer
import com.agentx.app.context.ExternalContent
import com.agentx.app.context.ExternalContentReader
import com.agentx.app.context.ExternalReadFailure
import com.agentx.app.core.ForgeResult
import com.agentx.app.core.failure
import com.agentx.app.core.success
import com.agentx.app.ui.ide.data.AttachmentPickOutcome
import com.agentx.app.ui.ide.data.AttachmentPicker
import com.agentx.app.workspace.WorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Reads a file the user picked from outside the workspace, through Android's content resolver.
 *
 * This is the only part of attaching a file that needs a device. It is deliberately a plain
 * [ExternalContentReader] so the rules about what may enter the workspace — text only, nothing
 * protected, nothing outside the project — stay in [AttachmentMaterializer], where they are testable
 * without Android.
 *
 * Reading is bounded: at most `maxBytes + 1` bytes are ever pulled into memory, so a file that
 * turns out to be far larger than the limit is refused rather than loaded and then rejected.
 */
class AndroidExternalContentReader(private val context: Context) : ExternalContentReader {

    override suspend fun read(
        uri: String,
        maxBytes: Long,
    ): ForgeResult<ExternalContent, ExternalReadFailure> = withContext(Dispatchers.IO) {
        val parsed = runCatching { Uri.parse(uri) }.getOrNull()
            ?: return@withContext failure(ExternalReadFailure.UNREADABLE)

        val resolver = context.contentResolver
        val mimeType = runCatching { resolver.getType(parsed) }.getOrNull()
            ?: AgentAttachmentMime.UNKNOWN

        runCatching {
            declaredSize(parsed)?.let { declared ->
                if (declared > maxBytes) return@runCatching Read.DeclaredTooLarge
            }

            val stream = resolver.openInputStream(parsed) ?: return@runCatching Read.Unreadable
            val bytes = stream.use { input ->
                val limit = maxBytes + 1
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                while (out.size() < limit) {
                    val remaining = (limit - out.size()).toInt().coerceAtMost(buffer.size)
                    val read = input.read(buffer, 0, remaining)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }

            when {
                bytes.isEmpty() -> Read.Empty
                bytes.size > maxBytes -> Read.ReadTooLarge
                else -> Read.Content(
                    ExternalContent(
                        displayName = displayNameOf(context, parsed)
                            ?: parsed.lastPathSegment?.substringAfterLast('/')
                            ?: "attachment",
                        mimeType = mimeType,
                        sizeBytes = bytes.size.toLong(),
                        text = String(bytes, Charsets.UTF_8),
                    ),
                )
            }
        }.fold(
            onSuccess = { read ->
                when (read) {
                    is Read.Content -> success(read.content)
                    Read.Empty -> failure(ExternalReadFailure.EMPTY)
                    Read.Unreadable -> failure(ExternalReadFailure.UNREADABLE)
                    Read.DeclaredTooLarge, Read.ReadTooLarge -> failure(ExternalReadFailure.TOO_LARGE)
                }
            },
            onFailure = { error ->
                // A revoked or absent grant surfaces here; anything else is genuinely unreadable.
                if (error is SecurityException) {
                    failure(ExternalReadFailure.PERMISSION_DENIED)
                } else {
                    failure(ExternalReadFailure.UNREADABLE)
                }
            },
        )
    }

    /** What a bounded read produced, before it is mapped onto a result. */
    private sealed interface Read {
        data class Content(val content: ExternalContent) : Read
        data object Empty : Read
        data object Unreadable : Read
        data object DeclaredTooLarge : Read
        data object ReadTooLarge : Read
    }

    private fun declaredSize(uri: Uri): Long? = query(uri) { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (index < 0 || cursor.isNull(index)) null else cursor.getLong(index)
    }

    private fun <T> query(uri: Uri, read: (android.database.Cursor) -> T?): T? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) read(cursor) else null
        }
    }.getOrNull()
}

/** The name of a picked file, for the attachment's display name. */
internal fun displayNameOf(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (cursor.moveToFirst() && index >= 0 && !cursor.isNull(index)) cursor.getString(index) else null
    }
}.getOrNull()

internal object AgentAttachmentMime {
    const val UNKNOWN = "application/octet-stream"

    /**
     * What the Document action offers.
     *
     * Only formats AgentX can actually read as text, so the picker never invites a file that would
     * be refused afterwards. A provider that ignores the filter can still hand over something else,
     * and the materialiser then says so plainly instead of decoding it as text.
     */
    val DOCUMENT_TYPES = arrayOf(
        "text/*",
        "application/json",
        "application/xml",
        "application/x-yaml",
        "application/toml",
    )
}

/**
 * The Agent Chat composer's attachment action, backed by the platform pickers.
 *
 * Documents and files go through `ACTION_OPEN_DOCUMENT`, which grants read access to the picked URI
 * without any storage permission. Images use the photo picker, which needs no media permission
 * either — AgentX asks for one broad storage permission and this is deliberately not a second one.
 *
 * Whatever is picked is read once and materialised into the open workspace, so the chat only ever
 * holds a workspace reference and the agent's own file tools can already read it.
 */
@Composable
fun rememberAndroidAttachmentPicker(
    workspaces: WorkspaceManager,
    materializer: AttachmentMaterializer,
): AttachmentPicker {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingKind by remember { mutableStateOf(AgentAttachmentKind.FILE) }
    var pendingResult by remember { mutableStateOf<((AttachmentPickOutcome) -> Unit)?>(null) }

    fun deliver(outcome: AttachmentPickOutcome) {
        val callback = pendingResult
        pendingResult = null
        callback?.invoke(outcome)
    }

    fun attach(uri: Uri?) {
        if (uri == null) {
            deliver(AttachmentPickOutcome.Cancelled)
            return
        }
        scope.launch {
            val fileSystem = workspaces.current?.fileSystem
            if (fileSystem == null) {
                deliver(AttachmentPickOutcome.Failed("Open a project before attaching a file."))
                return@launch
            }
            val name = displayNameOf(context, uri)
                ?: uri.lastPathSegment?.substringAfterLast('/')
                ?: "attachment"

            deliver(
                when (val result = materializer.materialize(uri.toString(), name, fileSystem)) {
                    is ForgeResult.Success -> AttachmentPickOutcome.Attached(result.value)
                    // The domain already says what went wrong and what to do about it, and that
                    // text is more specific than anything the picker could invent here.
                    is ForgeResult.Failure -> AttachmentPickOutcome.Failed(result.error.message)
                },
            )
        }
    }

    val documents = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        attach(uri)
    }
    val images = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        attach(uri)
    }

    return remember(documents, images, workspaces, materializer, context) {
        AttachmentPicker { kind, onResult ->
            pendingResult = onResult
            pendingKind = kind
            when (kind) {
                AgentAttachmentKind.IMAGE ->
                    images.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )

                AgentAttachmentKind.DOCUMENT -> documents.launch(AgentAttachmentMime.DOCUMENT_TYPES)

                AgentAttachmentKind.FILE -> documents.launch(arrayOf("*/*"))
            }
        }
    }
}
