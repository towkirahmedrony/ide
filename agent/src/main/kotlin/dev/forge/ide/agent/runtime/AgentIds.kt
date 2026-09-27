package dev.forge.ide.agent.runtime

import java.util.UUID

internal object AgentIds {
    fun newId(): String = UUID.randomUUID().toString()
}

internal fun nowMillis(): Long = System.currentTimeMillis()
