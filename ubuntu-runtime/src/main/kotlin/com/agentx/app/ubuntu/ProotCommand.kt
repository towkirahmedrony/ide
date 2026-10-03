package com.agentx.app.ubuntu

/**
 * One `--bind` entry for PRoot: a host path, optionally mapped to a different guest path.
 *
 * A bind with no [guest] exposes the host path at the same path inside the guest, which is
 * what `/dev`, `/proc` and `/sys` need. A bind with a [guest] is how an AgentX project is
 * exposed: the real project directory is mounted at [ProotCommand.GUEST_PROJECT_ROOT] so a
 * file created in the terminal is the same file the IDE's Files and editor show.
 */
data class BindMount(val host: String, val guest: String? = null) {
    init {
        require(host.isNotBlank()) { "A bind mount needs a host path" }
        require(host.none { it == '\n' || it == '\u0000' }) { "Illegal character in bind host path" }
        require(guest == null || guest.none { it == '\n' || it == '\u0000' }) {
            "Illegal character in bind guest path"
        }
    }

    /** The exact `HOST[:GUEST]` token PRoot's `-b` expects. */
    val spec: String get() = if (guest == null) host else "$host:$guest"
}

/** The prOT process to start: an executable, its argv and the environment it needs. */
data class ProotInvocation(
    val executable: String,
    /** argv including `argv[0]`. This is what the vendored pty's `TerminalSession` expects. */
    val arguments: List<String>,
    val environment: Map<String, String>,
) {
    /**
     * The same invocation as a `ProcessBuilder` command, for non-pty execution.
     *
     * `ProcessBuilder` uses the first element as both the program and `argv[0]`, so the
     * `"proot"` placeholder that the pty needs as `argv[0]` must be dropped here, otherwise
     * PRoot would see `proot` as its first *option*.
     */
    val processCommand: List<String>
        get() = listOf(executable) + arguments.drop(1)
}

/**
 * Builds the PRoot command line and the environment PRoot itself needs.
 *
 * The shape is the documented one:
 *
 * ```
 * proot -0 -l -r <rootfs> -b <host[:guest]>... -w <guest-cwd> <guest-command>...
 * ```
 *
 * Each flag is load-bearing and none is optional here:
 *
 * - `-r` selects the rootfs the guest sees as `/`.
 * - `-0` is PRoot's fake-root: inside the guest `id` reports `uid=0(root)` without Android
 *   root, Magisk or any system modification. It is a user-space illusion.
 * - `-l` enables link-to-symlink emulation. Ubuntu's coreutils, dpkg and `ln` create symlinks
 *   as well as hard links; on Android's app storage hard links across the guest tree are not
 *   available, so without `-l` dpkg unpacking and `install` fail. `-l` requires
 *   [NativeRuntimeLayout.l2s] to exist, be writable, and — the part that is easy to get wrong —
 *   to live *inside* the rootfs, or the links it creates are unusable from the guest.
 * - `-b` mounts infrastructure (`/dev`, `/proc`, `/sys`, the generated `resolv.conf`, `/tmp`)
 *   and the project.
 * - `-w` sets the guest working directory, which is a guest path even though the pty's host
 *   working directory is different.
 */
object ProotCommand {

    /** Where an AgentX project is exposed inside the guest. */
    const val GUEST_PROJECT_ROOT: String = "/workspace/project"

    /** The guest home directory. PRoot's fake root makes the guest user `root`. */
    const val GUEST_HOME: String = "/root"

    /** The interactive shell, started as a login shell inside the guest. */
    val LOGIN_SHELL: List<String> = listOf("/bin/bash", "--login")

    /**
     * Starts PRoot with `-V` so the packaged binary can be proven executable from
     * [NativeRuntimeLayout.nativeLibraryDir] before any Ubuntu rootfs is downloaded.
     */
    fun version(layout: NativeRuntimeLayout): ProotInvocation = ProotInvocation(
        executable = layout.proot,
        arguments = listOf("proot", ProotSelfTest.VERSION_FLAG),
        environment = prootEnvironment(
            layout = layout,
            include32BitLoader = false,
            hostLibraryPath = null,
        ),
    )

    /**
     * The invocation that unpacks the Ubuntu archive.
     *
     * The platform `tar` is *not* run directly. Android's SELinux policy forbids an untrusted
     * app from creating a hard link at all (a `neverallow` rule), so `tar -xzf` fails with
     * `can't link 'usr/bin/perl5.38.2' -> 'usr/bin/perl': Permission denied` on the two hard
     * links Ubuntu Base contains. Running it under this runtime's own PRoot with `-l`
     * (link-to-symlink) performs each `link(2)`/`linkat(2)` as a symlink to the same content, so
     * the tree extracts intact and the two entries still name one file — no copy, no dropped
     * link, no weakened checksum.
     *
     * No `-r` is passed: PRoot is acting purely as the linker here, and the archive's own paths
     * are what is being written. `PROOT_L2S_DIR` must be set or the emulation has nowhere to keep
     * the moved contents — and it must name a directory *inside* [intoDir], because the symlinks
     * it leaves behind record that path verbatim. See [NativeRuntimeLayout.l2s].
     */
    fun extraction(
        layout: NativeRuntimeLayout,
        hostTar: String,
        archivePath: String,
        intoDir: String,
    ): ProotInvocation {
        val arguments = listOf(
            "proot",
            "-l",
            "-w",
            "/",
            hostTar,
            "-xzf",
            archivePath,
            "-C",
            intoDir,
        )
        return ProotInvocation(
            executable = layout.proot,
            arguments = arguments,
            environment = prootEnvironment(
                layout = layout,
                include32BitLoader = false,
                hostLibraryPath = null,
                l2sDir = extractionStore(intoDir),
            ),
        )
    }

    /**
     * The link-to-symlink store that belongs to the tree being unpacked, `intoDir`.
     *
     * Derived from the destination, **never** from `layout.l2s`. During an installation those are
     * two different trees: `layout.l2s` is the *live* rootfs's store, while the extraction writes
     * into `rootfs.installing`. Taking the store from the layout is how the live store came to be
     * handed to an extraction that was filling the temporary tree — the log said
     * `rootfs.installing/.l2s` while the child process was given `rootfs/.l2s`.
     *
     * That mismatch is not cosmetic. `-l` records the store's absolute path inside every emulated
     * link it creates, so a store outside the tree being unpacked produces links that are made and
     * then cannot be resolved, which `tar` reports as
     * `can't link 'usr/bin/perl5.38.2' -> 'usr/bin/perl': No such file or directory`. The two
     * paths are therefore derived from one value in one place, so they cannot disagree again.
     */
    fun extractionStore(intoDir: String): String =
        "${intoDir.trimEnd('/')}/${NativeRuntimeLayout.L2S_DIR}"

    /**
     * Builds the invocation.
     *
     * @param layout native library directory and app-private runtime storage.
     * @param guestWorkingDirectory a *guest* path (for example [GUEST_PROJECT_ROOT]); the pty's
     *   host working directory is chosen separately and only has to exist on the host.
     * @param binds infrastructure and project mounts, in the order they should be applied.
     * @param guestCommand the guest argv, for example [LOGIN_SHELL].
     * @param hostLibraryPath an optional `LD_LIBRARY_PATH` for the PRoot process.
     *
     * [hostLibraryPath] exists only as a diagnostic fallback: the native build links PRoot and
     * the loader with `DT_RUNPATH=$ORIGIN` so they find `libtalloc.so` and
     * `libandroid-shmem.so` next to themselves in [NativeRuntimeLayout.nativeLibraryDir]. It is
     * left null by default because that variable is inherited by guest processes, where it can
     * shadow the guest's own libraries. See `tools/ubuntu-runtime/README.md`.
     */
    fun build(
        layout: NativeRuntimeLayout,
        guestWorkingDirectory: String,
        binds: List<BindMount>,
        guestCommand: List<String>,
        include32BitLoader: Boolean = false,
        hostLibraryPath: String? = null,
    ): ProotInvocation {
        val arguments = ArrayList<String>(8 + binds.size * 2 + guestCommand.size)
        arguments += "proot"
        arguments += "-r"
        arguments += layout.rootfs
        arguments += "-0"
        arguments += "-l"
        arguments += "-w"
        arguments += guestWorkingDirectory
        for (bind in binds) {
            arguments += "-b"
            arguments += bind.spec
        }
        arguments += guestCommand

        return ProotInvocation(
            executable = layout.proot,
            arguments = arguments,
            environment = prootEnvironment(layout, include32BitLoader, hostLibraryPath),
        )
    }

    /**
     * The environment PRoot itself needs.
     *
     * `PROOT_LOADER` must name the real interposer in `nativeLibraryDir`; Android executes only
     * `libproot.so` directly and hands every guest binary to this loader. `PROOT_L2S_DIR` is
     * where the link-to-symlink emulation keeps the contents it moves, and it has to survive
     * across sessions — a rootfs extracted with `-l` refers into it.
     */
    private fun prootEnvironment(
        layout: NativeRuntimeLayout,
        include32BitLoader: Boolean,
        hostLibraryPath: String?,
        l2sDir: String = layout.l2s,
    ): Map<String, String> {
        val environment = LinkedHashMap<String, String>()
        environment["PROOT_LOADER"] = layout.loader
        if (include32BitLoader) {
            // termux/proot reads PROOT_LOADER_32; keep PROOT_LOADER32 as an alias.
            environment["PROOT_LOADER_32"] = layout.loader32
            environment["PROOT_LOADER32"] = layout.loader32
        }
        environment["PROOT_TMP_DIR"] = layout.tmp
        environment["PROOT_L2S_DIR"] = l2sDir
        if (!hostLibraryPath.isNullOrBlank()) {
            environment["LD_LIBRARY_PATH"] = hostLibraryPath
        }
        return environment
    }

    /**
     * The infrastructure binds every session needs, in order.
     *
     * `/dev`, `/proc` and `/sys` are kernel filesystems the guest must see as themselves. The
     * generated `resolv.conf` gives guest tools working DNS. `/tmp` is pointed at app-private
     * storage so a guest process cannot fill the rootfs. [project] and [resolvConf] may be null
     * when no project is bound.
     */
    fun infrastructureBinds(
        layout: NativeRuntimeLayout,
        resolvConf: String?,
    ): List<BindMount> {
        val binds = ArrayList<BindMount>(6)
        binds += BindMount("/dev")
        binds += BindMount("/proc")
        binds += BindMount("/sys")
        binds += BindMount(layout.tmp, "/tmp")
        if (resolvConf != null) {
            binds += BindMount(resolvConf, "/etc/resolv.conf")
        }
        return binds
    }

    /** Adds the project bind when a real host directory is available. */
    fun withProject(binds: List<BindMount>, projectHostPath: String?): List<BindMount> {
        if (projectHostPath.isNullOrBlank()) return binds
        return binds + BindMount(projectHostPath, GUEST_PROJECT_ROOT)
    }
}
