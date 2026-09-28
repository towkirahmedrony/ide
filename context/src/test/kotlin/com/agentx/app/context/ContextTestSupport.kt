package com.agentx.app.context

import com.agentx.app.workspace.WorkspaceFileSystem
import com.agentx.app.workspace.WorkspaceNode
import com.agentx.app.workspace.WorkspaceResult
import com.agentx.app.workspace.memory.InMemoryWorkspaceFileSystem
import kotlinx.coroutines.Dispatchers

/**
 * A workspace filesystem that wraps the in-memory implementation, records which
 * files were read, and can block inside a read (used by the cancellation test).
 */
internal class TestWorkspaceFileSystem(
    seed: Map<String, String> = emptyMap(),
    private val beforeRead: suspend (String) -> Unit = {},
) : WorkspaceFileSystem {

    private val delegate = InMemoryWorkspaceFileSystem(seed)
    private val reads = mutableListOf<String>()

    val readPaths: List<String> get() = synchronized(reads) { reads.toList() }

    override suspend fun list(path: String): WorkspaceResult<List<WorkspaceNode>> = delegate.list(path)

    override suspend fun metadata(path: String): WorkspaceResult<WorkspaceNode> = delegate.metadata(path)

    override suspend fun exists(path: String): Boolean = delegate.exists(path)

    override suspend fun readFile(path: String): WorkspaceResult<String> {
        beforeRead(path)
        synchronized(reads) { reads += path }
        return delegate.readFile(path)
    }

    override suspend fun writeFile(path: String, content: String): WorkspaceResult<Unit> =
        delegate.writeFile(path, content)

    override suspend fun createFile(path: String): WorkspaceResult<com.agentx.app.workspace.WorkspaceFile> =
        delegate.createFile(path)

    override suspend fun createDirectory(path: String): WorkspaceResult<com.agentx.app.workspace.WorkspaceDirectory> =
        delegate.createDirectory(path)

    override suspend fun rename(path: String, newName: String): WorkspaceResult<WorkspaceNode> =
        delegate.rename(path, newName)

    override suspend fun move(sourcePath: String, destinationPath: String): WorkspaceResult<WorkspaceNode> =
        delegate.move(sourcePath, destinationPath)

    override suspend fun delete(path: String): WorkspaceResult<Unit> = delegate.delete(path)
}

/** A workspace port with a fixed snapshot that records context access. */
internal class TestWorkspaceContextProvider(
    private val snapshot: WorkspaceSnapshot? = null,
    private val fileSystem: WorkspaceFileSystem? = null,
) : WorkspaceContextProvider {

    private val accessedPaths = mutableListOf<String>()

    val accessed: List<String> get() = synchronized(accessedPaths) { accessedPaths.toList() }

    override suspend fun snapshot(): WorkspaceSnapshot? = snapshot

    override suspend fun fileSystem(): WorkspaceFileSystem? = fileSystem

    override fun markAccessed(path: String) {
        synchronized(accessedPaths) { accessedPaths += path }
    }
}

/**
 * Engine under test: same code path as production, but with an unconfined
 * dispatcher and a frozen clock so results are reproducible.
 */
internal fun testEngine(
    fileSystem: WorkspaceFileSystem? = null,
    snapshot: WorkspaceSnapshot? = null,
    now: Long = FIXED_NOW,
    budget: ContextBudget = ContextBudget.DEFAULT,
    workspace: WorkspaceContextProvider = TestWorkspaceContextProvider(snapshot, fileSystem),
): DefaultContextEngine = DefaultContextEngine(
    workspace = workspace,
    budget = budget,
    dispatcher = Dispatchers.Unconfined,
    clock = { now },
)

internal const val FIXED_NOW = 1_700_000_000_000L

internal fun contextItem(
    id: String,
    source: ContextSource,
    priority: ContextPriority = source.defaultPriority,
    relevance: Double = ContextRelevance.defaultFor(source),
    content: String = "content of $id",
    path: String? = null,
): ContextItem = ContextItem(
    id = id,
    source = source,
    content = content,
    priority = priority,
    relevance = relevance,
    path = path,
)
