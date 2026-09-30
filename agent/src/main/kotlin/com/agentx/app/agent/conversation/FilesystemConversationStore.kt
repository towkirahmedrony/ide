package com.agentx.app.agent.conversation

import java.io.File

/**
 * Persists each session conversation as a JSON file under [root]. No second
 * database is introduced: this is the same app-private filesystem pattern
 * already used for imported skills.
 *
 * One file per session so a delete cannot touch another session's history.
 */
class FilesystemConversationStore(
    private val root: File,
) : ConversationStore {

    private val lock = Any()
    private val active = LinkedHashMap<String, String>()
    private var loaded = false

    override fun save(conversation: AgentConversation) {
        synchronized(lock) {
            root.mkdirs()
            val file = fileFor(conversation.id) ?: return
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(ConversationCodec.encode(conversation))
            if (file.exists()) file.delete()
            temp.renameTo(file)
        }
    }

    override fun find(sessionId: String): AgentConversation? {
        synchronized(lock) {
            val file = fileFor(sessionId) ?: return null
            if (!file.isFile) return null
            return ConversationCodec.decode(file.readText())
        }
    }

    override fun all(): List<AgentConversation> {
        synchronized(lock) {
            if (!root.isDirectory) return emptyList()
            return root.listFiles().orEmpty()
                .filter { it.isFile && it.name.endsWith(".json") && !it.name.startsWith("_") }
                .mapNotNull { file -> ConversationCodec.decode(file.readText()) }
        }
    }

    override fun delete(sessionId: String): Boolean {
        synchronized(lock) {
            val file = fileFor(sessionId) ?: return false
            val removed = if (file.isFile) file.delete() else false
            val stale = active.entries.filter { it.value == sessionId }.map { it.key }
            stale.forEach { active.remove(it) }
            if (stale.isNotEmpty()) persistActive()
            return removed
        }
    }

    fun activeSessionId(workspaceId: String?): String? {
        synchronized(lock) {
            ensureActive()
            return active[workspaceKey(workspaceId)]
        }
    }

    fun setActiveSessionId(workspaceId: String?, sessionId: String?) {
        synchronized(lock) {
            ensureActive()
            val key = workspaceKey(workspaceId)
            if (sessionId.isNullOrBlank()) active.remove(key) else active[key] = sessionId
            persistActive()
        }
    }

    private fun ensureActive() {
        if (loaded) return
        loaded = true
        val file = File(root, ACTIVE_FILE)
        if (!file.isFile) return
        val decoded = ConversationCodec.decodeAll("[]")
        runCatching {
            val text = file.readText()
            val parsed = com.agentx.app.model.json.JsonCodec.parse(text)
            val fields = parsed.objectOrNull() ?: return
            fields.forEach { (key, value) ->
                value.stringOrNull()?.takeIf { it.isNotBlank() }?.let { active[key] = it }
            }
        }
        decoded
    }

    private fun persistActive() {
        root.mkdirs()
        val fields = active.mapValues { com.agentx.app.model.json.Json.of(it.value) }
        File(root, ACTIVE_FILE).writeText(
            com.agentx.app.model.json.JsonCodec.encodeObject(fields),
        )
    }

    private fun fileFor(sessionId: String): File? {
        if (sessionId.isBlank() || sessionId.any { it == '/' || it == '\\' || it == '.' }) {
            if (sessionId.any { !it.isLetterOrDigit() && it != '-' && it != '_' }) return null
        }
        if (sessionId.startsWith("_")) return null
        return File(root, "$sessionId.json")
    }

    private fun workspaceKey(workspaceId: String?): String = workspaceId?.takeIf { it.isNotBlank() } ?: "_"

    private companion object {
        const val ACTIVE_FILE = "_active.json"
    }
}

private fun com.agentx.app.model.json.JsonValue.objectOrNull() =
    com.agentx.app.model.json.objectOrNull()

private fun com.agentx.app.model.json.JsonValue.stringOrNull() =
    com.agentx.app.model.json.stringOrNull()
