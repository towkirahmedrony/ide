package com.agentx.app.workspace.memory

import com.agentx.app.workspace.WorkspaceId
import com.agentx.app.workspace.WorkspaceMetadataStore
import com.agentx.app.workspace.WorkspaceRecord

/** Non-persistent [WorkspaceMetadataStore] used by tests and the demo backend. */
class InMemoryWorkspaceMetadataStore : WorkspaceMetadataStore {

    private val entries = LinkedHashMap<WorkspaceId, WorkspaceRecord>()
    private var lastOpened: WorkspaceId? = null

    override suspend fun records(): List<WorkspaceRecord> = entries.values.toList()

    override suspend fun find(id: WorkspaceId): WorkspaceRecord? = entries[id]

    override suspend fun save(record: WorkspaceRecord) {
        entries[record.metadata.id] = record
    }

    override suspend fun delete(id: WorkspaceId) {
        entries.remove(id)
        if (lastOpened == id) lastOpened = null
    }

    override suspend fun lastOpenedId(): WorkspaceId? = lastOpened

    override suspend fun setLastOpened(id: WorkspaceId?) {
        lastOpened = id
    }
}
