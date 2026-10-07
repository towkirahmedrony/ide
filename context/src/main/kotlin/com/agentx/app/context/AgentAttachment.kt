package com.agentx.app.context

/**
 * What a user attached to a chat message.
 *
 * The kind is the coarse classification the composer and the transcript render by; it never
 * decides whether the content can be interpreted. A PDF and a Markdown file are both documents,
 * and only one of them can be read as text.
 */
enum class AgentAttachmentKind {
    /** A picture. Recognized so it can be refused accurately rather than mangled. */
    IMAGE,

    /** A general file the user picked. */
    FILE,

    /** A document: a format that exists to be read rather than compiled or run. */
    DOCUMENT,
}

/**
 * One item the user attached to a chat message.
 *
 * This is deliberately metadata only — no bytes. An attachment is a *reference into the workspace*,
 * which is what the rest of AgentX already understands: the context engine loads it as a file item,
 * the agent's file tools can read it, and the editor could open it. The one thing it is not is a
 * second copy of the file: an attachment that is already in the workspace keeps the path it has.
 *
 * [path] is always workspace-relative and always inside the current workspace. It is the same
 * representation [ContextRequest.mentionedFiles] uses, so an attachment reaches the model through
 * the identical path as a file the user merely named in their prompt.
 *
 * Bytes live outside this type. A model that cannot take an image is told so by
 * [AgentAttachmentKind], never by being handed a path with the image's name on it.
 */
data class AgentAttachment(
    /** Stable for a given workspace and path, so a restored reference can be matched again. */
    val id: String,
    val displayName: String,
    /** Workspace-relative path of the attached file. */
    val path: String,
    val mimeType: String,
    val sizeBytes: Long,
    val kind: AgentAttachmentKind,
) {
    init {
        require(id.isNotBlank()) { "An attachment id must not be blank" }
        require(path.isNotBlank()) { "An attachment path must not be blank" }
    }

    companion object {
        /** One attachment per workspace path: attaching the same file twice is the same item. */
        fun idFor(path: String): String = "attachment:$path"

        /** Fallback for a file the platform could not describe. */
        const val UNKNOWN_MIME_TYPE: String = "application/octet-stream"
    }
}
