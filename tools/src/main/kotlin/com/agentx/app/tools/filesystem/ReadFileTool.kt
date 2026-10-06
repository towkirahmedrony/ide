package com.agentx.app.tools.filesystem

import com.agentx.app.tools.Json
import com.agentx.app.tools.SecretRedactor
import com.agentx.app.tools.Tool
import com.agentx.app.tools.ToolCapability
import com.agentx.app.tools.ToolCategory
import com.agentx.app.tools.ToolDefinition
import com.agentx.app.tools.ToolErrorCode
import com.agentx.app.tools.ToolExecutionContext
import com.agentx.app.tools.ToolExecutionError
import com.agentx.app.tools.ToolInput
import com.agentx.app.tools.ToolInputSchema
import com.agentx.app.tools.ToolOutput
import com.agentx.app.tools.ToolOutputSpec
import com.agentx.app.tools.ToolParameter
import com.agentx.app.tools.ToolParameterType
import com.agentx.app.tools.ToolPermissionDecision
import com.agentx.app.tools.ToolPermissionLevel
import com.agentx.app.tools.WorkspaceFileSystemResolver
import com.agentx.app.tools.missingWorkspace
import com.agentx.app.tools.orThrow
import com.agentx.app.workspace.WorkspaceFile

/**
 * Reads a single workspace file for the agent.
 *
 * The path is untrusted model input and never reaches the operating system
 * directly: it is handed to the workspace filesystem, which resolves it against
 * the managed workspace root, rejects absolute paths and `..`, and refuses a
 * canonical target outside the workspace (so a symlink cannot escape). This tool
 * adds the file-level contract on top — a directory is refused rather than read,
 * secrets are redacted, and the content handed to the model is bounded even when
 * the file itself is not.
 */
class ReadFileTool(
    private val workspaces: WorkspaceFileSystemResolver,
) : Tool {

    override val definition = ToolDefinition(
        name = NAME,
        title = "Read file",
        description = "Reads a UTF-8 text file from the opened workspace. Paths are workspace-relative.",
        inputSchema = ToolInputSchema(
            parameters = listOf(
                ToolParameter(
                    name = ARG_PATH,
                    type = ToolParameterType.STRING,
                    description = "Workspace-relative file path.",
                    required = true,
                ),
            ),
        ),
        output = ToolOutputSpec(
            description = "File path, size, whether the content was truncated, and the contents.",
        ),
        permission = ToolPermissionDecision.ALLOW,
        capabilities = setOf(ToolCapability.READ_ONLY, ToolCapability.FILESYSTEM),
        category = ToolCategory.FILESYSTEM,
        requiredPermissions = setOf(ToolPermissionLevel.READ_ONLY),
        metadata = mapOf("sideEffects" to "none"),
    )

    override suspend fun execute(input: ToolInput, context: ToolExecutionContext): ToolOutput {
        val fs = workspaces.resolve(context) ?: missingWorkspace(NAME)
        val path = input.string(ARG_PATH).orEmpty()

        // Resolve metadata first so a directory is refused as an argument problem
        // instead of being read (or silently treated as an empty file).
        val node = fs.metadata(path).orThrow(NAME, path)
        if (node !is WorkspaceFile) {
            throw ToolExecutionError(
                code = ToolErrorCode.INVALID_ARGUMENTS,
                message = "'$path' is a directory, not a file",
                toolName = NAME,
                details = mapOf("path" to Json.of(path)),
            )
        }

        val raw = fs.readFile(path).orThrow(NAME, path)
        val content = redact(path, raw)
        val bounded = content.take(MAX_CONTENT_CHARS)
        val truncated = bounded.length < content.length
        return ToolOutput(
            content = mapOf(
                "path" to Json.of(path),
                "content" to Json.of(bounded),
                "bytes" to Json.of(bounded.length),
                "sizeBytes" to Json.of(node.sizeBytes ?: raw.length.toLong()),
                "truncated" to Json.of(truncated),
            ),
            displayText = bounded,
        )
    }

    /** Masks credential material the same way every other tool result is masked. */
    private fun redact(path: String, raw: String): String {
        val fileName = path.substringAfterLast('/')
        val secretName = fileName.equals(".env", ignoreCase = true) ||
            fileName.endsWith(".env", ignoreCase = true) ||
            SecretRedactor.looksSecret(fileName)
        return if (secretName) SecretRedactor.REDACTED else SecretRedactor.redactText(raw)
    }

    companion object {
        const val NAME = "read_file"
        const val ARG_PATH = "path"

        /**
         * Upper bound on the characters returned to the model. The workspace
         * filesystem already refuses files above its own read limit; this keeps a
         * large-but-legal file from flooding the context window. The file itself
         * is never modified.
         */
        const val MAX_CONTENT_CHARS: Int = 100_000
    }
}
