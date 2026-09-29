package com.agentx.app.termux

/**
 * Chooses the shell a session starts, the way `TermuxSession.execute` does: the first
 * usable entry of `UnixShellEnvironment.LOGIN_SHELL_BINARIES` inside `$PREFIX/bin`, and only
 * then the Android system shell as a fallback.
 *
 * The predicate is injected so the resolution order is testable without a filesystem.
 */
object TermuxShellResolver {

    /** Order matters; it is the order a Termux session resolves too. */
    val LOGIN_SHELL_BINARIES: List<String> = listOf("login", "bash", "zsh", "fish", "sh")

    /** Android's own shell. Present on every device, used only when the prefix has nothing. */
    const val SYSTEM_SHELL: String = "/system/bin/sh"

    data class Resolved(
        val executable: String,
        /** argv[0]. A login shell is passed with a leading `-` so `$0` reads `-bash`. */
        val processName: String,
        val login: Boolean,
    )

    /**
     * @param paths layout of the embedded runtime.
     * @param isExecutable probes a candidate path.
     */
    fun resolve(paths: TermuxPaths, isExecutable: (String) -> Boolean): Resolved {
        for (name in LOGIN_SHELL_BINARIES) {
            val candidate = "${paths.bin}/$name"
            if (isExecutable(candidate)) {
                return Resolved(executable = candidate, processName = "-$name", login = true)
            }
        }
        return Resolved(executable = SYSTEM_SHELL, processName = "sh", login = false)
    }
}
