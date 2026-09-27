package com.agentx.app.workspace.memory

import com.agentx.app.core.ForgeResult
import com.agentx.app.core.errorOrNull
import com.agentx.app.core.failure
import com.agentx.app.core.success
import com.agentx.app.core.valueOrNull
import com.agentx.app.workspace.WorkspaceDirectory
import com.agentx.app.workspace.WorkspaceError
import com.agentx.app.workspace.WorkspaceErrorCode
import com.agentx.app.workspace.WorkspaceFile
import com.agentx.app.workspace.WorkspaceFileSystem
import com.agentx.app.workspace.WorkspaceNode
import com.agentx.app.workspace.WorkspacePath
import com.agentx.app.workspace.WorkspaceResult

/**
 * A small, dependency-free [WorkspaceFileSystem] used by unit tests and the
 * in-memory demo backend. It applies the same path rules as the real backend,
 * so traversal and absolute paths are rejected identically.
 */
class InMemoryWorkspaceFileSystem(
    seedFiles: Map<String, String> = emptyMap(),
) : WorkspaceFileSystem {

    private val directories = linkedSetOf<String>()
    private val files = linkedMapOf<String, String>()

    init {
        directories += WorkspacePath.ROOT
        seedFiles.forEach { (path, content) ->
            val normalized = WorkspacePath.normalize(path).valueOrNull() ?: return@forEach
            if (normalized.isEmpty()) return@forEach
            createParents(normalized)
            files[normalized] = content
        }
    }

    override suspend fun list(path: String): WorkspaceResult<List<WorkspaceNode>> {
        val rel = when (val normalized = WorkspacePath.normalize(path)) {
            is ForgeResult.Success -> normalized.value
            is ForgeResult.Failure -> return normalized
        }
        if (rel !in directories) {
            return if (rel in files) notADirectory(rel) else notFound(rel)
        }

        val children = mutableListOf<WorkspaceNode>()
        directories
            .filter { it.isNotEmpty() && WorkspacePath.parent(it) == rel }
            .forEach { children += WorkspaceDirectory(path = it, name = WorkspacePath.name(it)) }
        files.keys
            .filter { WorkspacePath.parent(it) == rel }
            .forEach {
                children += WorkspaceFile(
                    path = it,
                    name = WorkspacePath.name(it),
                    sizeBytes = files[it]?.toByteArray(Charsets.UTF_8)?.size?.toLong(),
                )
            }
        return success(
            children.sortedWith(
                compareByDescending<WorkspaceNode> { it is WorkspaceDirectory }.thenBy { it.name.lowercase() },
            ),
        )
    }

    override suspend fun metadata(path: String): WorkspaceResult<WorkspaceNode> {
        val rel = when (val normalized = WorkspacePath.normalize(path)) {
            is ForgeResult.Success -> normalized.value
            is ForgeResult.Failure -> return normalized
        }
        return when {
            rel in directories -> success(WorkspaceDirectory(rel, WorkspacePath.name(rel)))
            rel in files -> success(WorkspaceFile(rel, WorkspacePath.name(rel), files[rel]?.length?.toLong()))
            else -> notFound(rel)
        }
    }

    override suspend fun exists(path: String): Boolean {
        val rel = WorkspacePath.normalize(path).valueOrNull() ?: return false
        return rel in directories || rel in files
    }

    override suspend fun readFile(path: String): WorkspaceResult<String> {
        val rel = resolve(path) ?: return invalid(path)
        val content = files[rel] ?: return if (rel in directories) notAFile(rel) else notFound(rel)
        return success(content)
    }

    override suspend fun writeFile(path: String, content: String): WorkspaceResult<Unit> {
        val rel = resolve(path) ?: return invalid(path)
        if (rel in directories) return notAFile(rel)
        if (rel !in files) return notFound(rel)
        files[rel] = content
        return success(Unit)
    }

    override suspend fun createFile(path: String): WorkspaceResult<WorkspaceFile> {
        val rel = resolve(path) ?: return invalid(path)
        if (rel.isEmpty()) return invalid(path)
        if (rel in files || rel in directories) return alreadyExists(rel)
        val parent = WorkspacePath.parent(rel)
        if (parent !in directories) return notFound(parent)
        files[rel] = ""
        return success(WorkspaceFile(path = rel, name = WorkspacePath.name(rel), sizeBytes = 0))
    }

    override suspend fun createDirectory(path: String): WorkspaceResult<WorkspaceDirectory> {
        val rel = resolve(path) ?: return invalid(path)
        if (rel.isEmpty()) return alreadyExists(rel)
        if (rel in files || rel in directories) return alreadyExists(rel)
        val parent = WorkspacePath.parent(rel)
        if (parent !in directories) return notFound(parent)
        directories += rel
        return success(WorkspaceDirectory(path = rel, name = WorkspacePath.name(rel)))
    }

    override suspend fun rename(path: String, newName: String): WorkspaceResult<WorkspaceNode> {
        val rel = resolve(path) ?: return invalid(path)
        if (rel.isEmpty()) return invalid(path)
        val targetResult = WorkspacePath.child(WorkspacePath.parent(rel), newName)
        val target = when (targetResult) {
            is ForgeResult.Success -> targetResult.value
            is ForgeResult.Failure -> return targetResult
        }
        if (target in files || target in directories) return alreadyExists(target)
        return when {
            rel in files -> {
                val content = files.remove(rel).orEmpty()
                files[target] = content
                success(WorkspaceFile(target, WorkspacePath.name(target), content.length.toLong()))
            }
            rel in directories -> {
                moveSubtree(rel, target)
                success(WorkspaceDirectory(target, WorkspacePath.name(target)))
            }
            else -> notFound(rel)
        }
    }

    override suspend fun move(sourcePath: String, destinationPath: String): WorkspaceResult<WorkspaceNode> {
        val src = resolve(sourcePath) ?: return invalid(sourcePath)
        val dst = resolve(destinationPath) ?: return invalid(destinationPath)
        if (src.isEmpty() || dst.isEmpty()) return invalid(destinationPath)
        if (dst == src || dst.startsWith("$src/")) return invalid(destinationPath)
        if (src !in files && src !in directories) return notFound(src)
        if (dst in files || dst in directories) return alreadyExists(dst)
        val destParent = WorkspacePath.parent(dst)
        if (destParent !in directories) return notFound(destParent)

        if (src in files) {
            val content = files.remove(src).orEmpty()
            files[dst] = content
        } else {
            moveSubtree(src, dst)
        }
        return metadata(dst)
    }

    override suspend fun delete(path: String): WorkspaceResult<Unit> {
        val rel = resolve(path) ?: return invalid(path)
        if (rel.isEmpty()) return invalid(path)
        return when {
            rel in files -> {
                files.remove(rel)
                success(Unit)
            }
            rel in directories -> {
                removeSubtree(rel)
                success(Unit)
            }
            else -> notFound(rel)
        }
    }

    // --- helpers -----------------------------------------------------------

    private fun resolve(path: String): String? = WorkspacePath.normalize(path).valueOrNull()

    private fun createParents(path: String) {
        var parent = WorkspacePath.parent(path)
        while (true) {
            directories += parent
            if (parent.isEmpty()) break
            parent = WorkspacePath.parent(parent)
        }
    }

    private fun moveSubtree(from: String, to: String) {
        val movedDirectories = directories.filter { it == from || it.startsWith("$from/") }.toList()
        movedDirectories.forEach { directories.remove(it) }
        movedDirectories.forEach { directories += it.replaceFirst(from, to) }

        val movedFiles = files.keys.filter { it.startsWith("$from/") }.associateWith { files.getValue(it) }
        movedFiles.forEach { (source, _) -> files.remove(source) }
        movedFiles.forEach { (source, content) -> files[source.replaceFirst(from, to)] = content }
    }

    private fun removeSubtree(root: String) {
        directories.removeAll { it == root || it.startsWith("$root/") }
        files.keys.removeAll { it.startsWith("$root/") }
    }

    private fun invalid(path: String): WorkspaceResult<Nothing> {
        // Preserve the specific rejection (absolute, traversal, illegal chars).
        val specific = WorkspacePath.normalize(path).errorOrNull()
        return failure(specific ?: WorkspaceError(WorkspaceErrorCode.INVALID_PATH, "The path is not valid.", path))
    }

    private fun notFound(path: String): WorkspaceResult<Nothing> = failure(
        WorkspaceError(WorkspaceErrorCode.NOT_FOUND, "Not found.", path),
    )

    private fun notADirectory(path: String): WorkspaceResult<Nothing> = failure(
        WorkspaceError(WorkspaceErrorCode.NOT_A_DIRECTORY, "Not a directory.", path),
    )

    private fun notAFile(path: String): WorkspaceResult<Nothing> = failure(
        WorkspaceError(WorkspaceErrorCode.NOT_A_FILE, "Not a file.", path),
    )

    private fun alreadyExists(path: String): WorkspaceResult<Nothing> = failure(
        WorkspaceError(WorkspaceErrorCode.ALREADY_EXISTS, "Already exists.", path),
    )
}
