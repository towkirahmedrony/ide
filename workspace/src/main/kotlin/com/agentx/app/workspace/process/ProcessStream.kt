package com.agentx.app.workspace.process

/**
 * Incremental output from a running process. [stdout] and [stderr] are never
 * mixed into one field so the UI can colour them independently.
 */
data class ProcessStreamChunk(
    val stdout: String = "",
    val stderr: String = "",
) {
    val isEmpty: Boolean get() = stdout.isEmpty() && stderr.isEmpty()
}

/** Which stream a chunk came from. */
enum class ProcessStreamKind {
    STDOUT,
    STDERR,
    SYSTEM,
}
