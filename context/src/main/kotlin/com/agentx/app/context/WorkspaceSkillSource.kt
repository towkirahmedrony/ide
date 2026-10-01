package com.agentx.app.context

import com.agentx.app.core.ForgeResult
import com.agentx.app.core.valueOrNull
import com.agentx.app.skills.MarkdownSkillSource
import com.agentx.app.skills.SkillDiscoverySource
import com.agentx.app.skills.SkillDocument
import com.agentx.app.skills.SkillDocumentSource
import com.agentx.app.skills.SkillMarkdown
import com.agentx.app.workspace.WorkspaceDirectory
import com.agentx.app.workspace.WorkspaceFileSystem
import com.agentx.app.workspace.WorkspacePath

/**
 * Reads `skills/<id>/SKILL.md` documents from the workspace the user has open.
 *
 * This is the workspace half of skill discovery. It is a [SkillDocumentSource],
 * so it plugs into the existing `MarkdownSkillSource` → `SkillDiscoverySource`
 * → `DefaultSkillManager.refresh()` chain instead of introducing a second skill
 * system: parsing, id/role validation, enablement, role assignment and the
 * built-in/imported precedence all stay exactly where they already are.
 *
 * Guarantees:
 * - reads go through the Context Engine's [WorkspaceContextProvider], so the
 *   active workspace is the only thing that can be read and its root can never
 *   be escaped;
 * - protected paths are rejected before any read, exactly like file context;
 * - each skill is one direct child directory holding one `SKILL.md`; nothing is
 *   walked recursively;
 * - a missing, unreadable or malformed document is skipped, never fatal;
 * - with no workspace open the source simply yields nothing.
 *
 * Instruction text is passed through unmodified: a skill body is user-authored
 * instruction data everywhere else in the Skills layer, so it is not rewritten
 * here. Only the protected-path rule is enforced before reading.
 */
class WorkspaceSkillSource(
    private val workspace: WorkspaceContextProvider,
    private val directory: String = DEFAULT_DIRECTORY,
) : SkillDocumentSource {

    override suspend fun documents(): List<SkillDocument> = runCatching {
        val fileSystem = workspace.fileSystem() ?: return@runCatching emptyList()
        val root = skillRoot() ?: return@runCatching emptyList()
        val entries = when (val listing = fileSystem.list(root)) {
            is ForgeResult.Success -> listing.value
            is ForgeResult.Failure -> return@runCatching emptyList()
        }
        // Sorted by name so discovery is deterministic regardless of the order a
        // platform backend happens to return.
        entries
            .filterIsInstance<WorkspaceDirectory>()
            .sortedBy { it.name }
            .mapNotNull { load(fileSystem, root, it) }
    }.getOrDefault(emptyList())

    private suspend fun load(
        fileSystem: WorkspaceFileSystem,
        root: String,
        directory: WorkspaceDirectory,
    ): SkillDocument? {
        // Built from the root plus the entry's own segment, so only direct
        // children can ever be addressed and a platform path is never trusted.
        val directoryPath = WorkspacePath.child(root, directory.name).valueOrNull() ?: return null
        if (ProtectedPaths.reason(directoryPath) != null) return null

        val filePath = WorkspacePath.child(directoryPath, SkillMarkdown.FILE_NAME).valueOrNull()
            ?: return null
        if (!fileSystem.exists(filePath)) return null
        val content = when (val read = fileSystem.readFile(filePath)) {
            is ForgeResult.Success -> read.value
            is ForgeResult.Failure -> return null
        }
        if (content.isBlank()) return null
        return SkillDocument(path = filePath, content = content)
    }

    /** The workspace-relative skill directory, or null when it cannot be used. */
    private fun skillRoot(): String? {
        val normalized = WorkspacePath.normalize(directory).valueOrNull() ?: return null
        if (normalized.isEmpty()) return null
        if (ProtectedPaths.reason(normalized) != null) return null
        return normalized
    }

    companion object {
        /** Directory inside the workspace that holds one folder per skill. */
        const val DEFAULT_DIRECTORY: String = "skills"

        /**
         * The id a `SKILL.md` without an `id:` falls back to: the containing folder
         * name, which is how the project's `skills/<id>/SKILL.md` layout already
         * names a skill.
         */
        val FOLDER_ID: (SkillDocument) -> String? = { document ->
            document.path.substringBeforeLast('/', "")
                .takeIf { it.isNotEmpty() }
                ?.substringAfterLast('/')
                ?.takeIf { it.isNotEmpty() }
        }

        /**
         * Workspace discovery as a [SkillDiscoverySource], ready for
         * `Foundation.boot(skillSources = …)`. Validation stays in [SkillMarkdown],
         * so an invalid document is rejected by the existing rules.
         */
        fun discovery(
            workspace: WorkspaceContextProvider,
            directory: String = DEFAULT_DIRECTORY,
        ): SkillDiscoverySource = MarkdownSkillSource(
            documents = WorkspaceSkillSource(workspace, directory),
            fallbackId = FOLDER_ID,
        )
    }
}
