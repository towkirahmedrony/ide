package dev.forge.ide.workspace

import dev.forge.ide.core.failure
import dev.forge.ide.core.success
import kotlinx.coroutines.CompletableDeferred

/**
 * A programmable [WorkspaceFileSystem] for tests.
 *
 * It records every call, so a test can prove how many directories were actually
 * read (the whole point of lazy loading), and can be told to fail, throw, or
 * suspend on a specific path to exercise error and cancellation paths.
 */
class FakeWorkspaceFileSystem(
    internal val listings: Map<String, List<WorkspaceNode>> = emptyMap(),
    internal val contents: Map<String, String> = emptyMap(),
    private val failures: Map<String, WorkspaceError> = emptyMap(),
    private val throwers: Map<String, Throwable> = emptyMap(),
    /** Paths whose read should hang until the test releases or cancels it. */
    var gates: Map<String, CompletableDeferred<Unit>> = emptyMap(),
) : WorkspaceFileSystem {

    val listCalls = mutableListOf<String>()
    val readCalls = mutableListOf<String>()

    fun listCount(path: String): Int = listCalls.count { it == path }

    override suspend fun list(path: String): WorkspaceResult<List<WorkspaceNode>> {
        listCalls += path
        throwers[path]?.let { throw it }
        gates[path]?.await()
        failures[path]?.let { return failure(it) }
        return success(listings[path].orEmpty())
    }

    override suspend fun metadata(path: String): WorkspaceResult<WorkspaceNode> {
        listings.values.forEach { nodes ->
            nodes.firstOrNull { it.path == path }?.let { return success(it) }
        }
        return if (path in contents) {
            success(WorkspaceFile(path = path, name = WorkspacePath.name(path)))
        } else {
            notFound(path)
        }
    }

    override suspend fun exists(path: String): Boolean =
        listings.containsKey(path) || path in contents ||
            listings.values.any { nodes -> nodes.any { it.path == path } }

    override suspend fun readFile(path: String): WorkspaceResult<String> {
        readCalls += path
        throwers[path]?.let { throw it }
        gates[path]?.await()
        failures[path]?.let { return failure(it) }
        val content = contents[path] ?: return notFound(path)
        return success(content)
    }

    override suspend fun writeFile(path: String, content: String): WorkspaceResult<Unit> =
        if (path in contents) success(Unit) else notFound(path)

    override suspend fun createFile(path: String): WorkspaceResult<WorkspaceFile> = unsupported(path)

    override suspend fun createDirectory(path: String): WorkspaceResult<WorkspaceDirectory> = unsupported(path)

    override suspend fun rename(path: String, newName: String): WorkspaceResult<WorkspaceNode> = unsupported(path)

    override suspend fun move(sourcePath: String, destinationPath: String): WorkspaceResult<WorkspaceNode> =
        unsupported(sourcePath)

    override suspend fun delete(path: String): WorkspaceResult<Unit> = unsupported(path)

    private fun <T> unsupported(path: String): WorkspaceResult<T> = failure(
        WorkspaceError(WorkspaceErrorCode.UNSUPPORTED_OPERATION, "Not supported by the fake.", path),
    )

    private fun <T> notFound(path: String): WorkspaceResult<T> = failure(
        WorkspaceError(WorkspaceErrorCode.NOT_FOUND, "Not found.", path),
    )
}
