package com.agentx.app.ubuntu

/**
 * Where the native runtime and the app-private rootfs live.
 *
 * Pure data with the two absolute paths injected, so every derived path is unit testable and
 * nothing here hardcodes `/data/data/...` or a generated APK install path:
 *
 * - the executable side is always [nativeLibraryDir], read from
 *   `context.applicationInfo.nativeLibraryDir` by [forContext];
 * - the guest side is always [runtimeDir], a directory this app owns under its `filesDir`.
 *
 * Android only allows `execve()` from the native library directory (for an app targeting a
 * modern SDK), which is why PRoot and its loader are addressed through [nativeLibraryDir] and
 * the guest rootfs is never executed directly.
 */
data class NativeRuntimeLayout(
    /** `context.applicationInfo.nativeLibraryDir`. Resolved at runtime, never guessed. */
    val nativeLibraryDir: String,
    /** App-private directory that holds the rootfs, the link2symlink store and scratch space. */
    val runtimeDir: String,
) {

    // ---- Native executables (nativeLibraryDir) -------------------------------------------

    /** PRoot itself; the only Android-executed binary in the guest path. */
    val proot: String get() = "$nativeLibraryDir/$PROOT_LIBRARY"

    /** The PRoot loader (ELF interposer) that the guest binaries are run through. */
    val loader: String get() = "$nativeLibraryDir/$LOADER_LIBRARY"

    /** 32-bit loader, needed only when a 32-bit guest process is started. Optional. */
    val loader32: String get() = "$nativeLibraryDir/$LOADER32_LIBRARY"

    /** Shared-memory emulation PRoot needs on Android (`shmget`/`shmat` guests). */
    val sharedMemory: String get() = "$nativeLibraryDir/$SHMEM_LIBRARY"

    /** talloc, which PRoot links against. */
    val talloc: String get() = "$nativeLibraryDir/$TALLOC_LIBRARY"

    // ---- Guest side (app-private runtime storage) ----------------------------------------

    /** Verified Ubuntu ARM64 rootfs after installation. */
    val rootfs: String get() = "$runtimeDir/$ROOTFS_DIR"

    /**
     * Scratch directory a rootfs was unpacked into before activation.
     *
     * The extraction now writes straight into [rootfs]: the tree may not be moved once it is
     * unpacked, because `-l` records absolute host paths into [l2s] inside it (see there). The
     * path is still cleared on every attempt so an install upgraded from a build that staged
     * first cannot leave a stale tree behind.
     */
    val staging: String get() = "$runtimeDir/$STAGING_DIR"

    /**
     * The guest shell, inside the rootfs. Not to be confused with an Android shell on the host:
     * this file is only reachable through PRoot, and its presence is what says the rootfs is
     * complete rather than merely extracted.
     */
    val guestShell: String get() = "$rootfs/$GUEST_SHELL_PATH"

    /**
     * The guest's `/etc/os-release`.
     *
     * The one check that identifies the guest root itself. `uname -m` cannot: it reports the
     * Android device's architecture either way, so an Android shell answers `aarch64` too.
     */
    val guestOsRelease: String get() = "$rootfs/$GUEST_OS_RELEASE_PATH"

    /**
     * PRoot's link-to-symlink store (`PROOT_L2S_DIR`).
     *
     * It is deliberately **inside** the guest rootfs, at `<rootfs>/.l2s`. That is not a
     * preference, it is what makes the emulated hard links resolvable at all.
     *
     * `-l` replaces every hard link with a symlink whose target is the literal absolute path
     * `<PROOT_L2S_DIR>/.l2s.<name><NNNN>`. When a guest opens such a link, PRoot's
     * `canonicalize()` dereferences it and runs the target through `detranslate_path()`, which
     * strips the guest root prefix *only* when the path lies under the root; a target outside
     * the root is returned unchanged and is then re-canonicalized as a **guest** path
     * (`<rootfs>/…/l2s/…`), which does not exist. The link dangles, `open()` answers `ENOENT`,
     * and a store kept beside the rootfs is exactly the configuration that produced
     * `dpkg: error setting ownership of '/usr/bin/perl5.38.2.dpkg-new': No such file or
     * directory`. Keeping it under the root means the prefix strip yields `/.l2s/…`, which
     * resolves.
     *
     * Upstream does the same: `proot-distro` pins `PROOT_L2S_DIR` to `<rootfs>/.l2s`
     * (`proot_distro/l2s.py`, `commands/login/__init__.py`).
     *
     * Because the store moves with the tree, the rootfs is extracted straight into [rootfs];
     * see [UbuntuRootfsInstaller]. It is created as part of that extraction, which is why it is
     * absent from [requiredDirectories].
     */
    val l2s: String get() = "$rootfs/$L2S_DIR"

    /** The store as the guest sees it, for diagnostics. */
    val guestL2sPath: String get() = "/$L2S_DIR"

    /** `PROOT_TMP_DIR`: PRoot's own scratch space. */
    val tmp: String get() = "$runtimeDir/$TMP_DIR"

    /** Where the downloaded rootfs archive is cached between attempts. */
    val downloads: String get() = "$runtimeDir/$DOWNLOADS_DIR"

    /** Generated `resolv.conf`, bind-mounted into the guest as `/etc/resolv.conf`. */
    val resolvConf: String get() = "$runtimeDir/etc/resolv.conf"

    /**
     * Where a SAF project is materialised so the guest can bind-mount it at
     * `/workspace/project`. A `content://` tree has no POSIX path, so it is copied here first;
     * PRoot is never handed a `content://` URI.
     */
    val workspaces: String get() = "$runtimeDir/$WORKSPACES_DIR"

    /** Install marker, written only after PRoot guest probes (`/bin/sh`, `/bin/bash`) pass. */
    val marker: String get() = "$rootfs/$MARKER_PATH"

    /**
     * Written only after the installed rootfs has been *run* through PRoot and answered the
     * guest probes in [UbuntuRuntimeVerifier]. The terminal refuses to start until it exists, so
     * a rootfs that extracted but cannot execute a shell is never presented as READY.
     */
    val verificationMarker: String get() = "$runtimeDir/$VERIFICATION_MARKER"

    /**
     * Written only once the developer toolchain (git, gh, python3, pip, node, npm, curl, wget,
     * ssh, rg) has been installed by the guest's own `apt-get` **and** every one of those
     * executables has answered inside the guest.
     *
     * It is part of READY. A rootfs whose `apt`/`dpkg` work but whose packages are missing is not
     * a developer runtime, and it must not present one: a failed package installation leaves this
     * marker absent, so [LocalUbuntuRuntime.isReady] is false and no terminal claims otherwise.
     */
    val toolchainMarker: String get() = "$runtimeDir/$TOOLCHAIN_MARKER"

    /**
     * Written when the extracted tree has to be thrown away — a damaged `dpkg` database, or a
     * failed activation — so the next attempt re-extracts instead of trying to repair a tree it
     * cannot trust. See [UbuntuRootfsInstaller.discardRootfs].
     */
    val recreateMarker: String get() = "$runtimeDir/$RECREATE_MARKER"

    /** The guest path of [runtimeDir] is not needed; only the pieces above are bound. */

    /**
     * All directories the runtime needs before it can start, in creation order.
     *
     * [l2s] is not here on purpose: it lives inside the rootfs and only exists once the archive
     * has been extracted, so it is created by the extraction rather than ahead of it.
     */
    val requiredDirectories: List<String>
        get() = listOf(runtimeDir, downloads, tmp, workspaces, "$runtimeDir/etc")

    companion object {
        const val PROOT_LIBRARY: String = "libproot.so"
        const val LOADER_LIBRARY: String = "libproot_loader.so"
        const val LOADER32_LIBRARY: String = "libproot_loader32.so"
        const val SHMEM_LIBRARY: String = "libandroid-shmem.so"
        const val TALLOC_LIBRARY: String = "libtalloc.so"

        const val ROOTFS_DIR: String = "rootfs"
        const val STAGING_DIR: String = "rootfs-staging"

        /**
         * The link-to-symlink store's name, as upstream spells it (`.l2s`, the `PREFIX` of
         * PRoot's `extension/link2symlink/link2symlink.c`).
         */
        const val L2S_DIR: String = ".l2s"
        const val TMP_DIR: String = "tmp"
        const val DOWNLOADS_DIR: String = "downloads"
        const val WORKSPACES_DIR: String = "workspaces"

        const val MARKER_PATH: String = "etc/agentx/developer-runtime.ok"

        /** Guest `/bin/bash`, relative to the rootfs. */
        const val GUEST_SHELL_PATH: String = "bin/bash"

        /** Guest `/etc/os-release`, relative to the rootfs. */
        const val GUEST_OS_RELEASE_PATH: String = "etc/os-release"
        const val VERIFICATION_MARKER: String = "rootfs-verified.ok"
        const val TOOLCHAIN_MARKER: String = "toolchain.ok"
        const val RECREATE_MARKER: String = "recreate-rootfs.ok"

        /**
         * Libraries that must be present in [nativeLibraryDir] before a guest process can be
         * started. `libproot_loader32.so` is intentionally absent: it is only needed for
         * 32-bit guest processes, which this runtime does not start.
         */
        val REQUIRED_LIBRARIES: List<String> = listOf(
            PROOT_LIBRARY,
            LOADER_LIBRARY,
            SHMEM_LIBRARY,
        )

        /** Libraries that are used when present but whose absence is not fatal alone. */
        val OPTIONAL_LIBRARIES: List<String> = listOf(
            LOADER32_LIBRARY,
            TALLOC_LIBRARY,
        )

        /**
         * Builds the layout for an application.
         *
         * [nativeLibraryDir] and [filesDir] come straight from the running app, so the layout
         * follows a move-to-SD install or a secondary user instead of assuming a path.
         */
        fun forContext(nativeLibraryDir: String, filesDir: String): NativeRuntimeLayout =
            NativeRuntimeLayout(
                nativeLibraryDir = nativeLibraryDir.trimEnd('/'),
                runtimeDir = "${filesDir.trimEnd('/')}/$RUNTIME_SUBDIR",
            )

        /** The app-private subdirectory that holds the developer runtime. */
        const val RUNTIME_SUBDIR: String = "developer-runtime"
    }
}

/**
 * What was found in [NativeRuntimeLayout.nativeLibraryDir].
 *
 * The probe is an injected predicate so it can be unit tested without a device. The runtime
 * refuses to start a guest process with a clear error when [missing] is non-empty, instead of
 * producing a `bash: not found` from inside the guest.
 */
data class NativeRuntimeProbe(
    val present: List<String>,
    val missing: List<String>,
    val optionalPresent: List<String>,
) {
    val ready: Boolean get() = missing.isEmpty()

    val summary: String
        get() = if (ready) {
            "Native runtime present: ${present.joinToString()}"
        } else {
            MISSING_APK_MESSAGE +
                " nativeLibraryDir is missing ${missing.joinToString()}." +
                " Present: ${present.ifEmpty { listOf("(none)") }.joinToString()}."
        }

    companion object {
        /**
         * Shown when the APK was built without the ARM64 PRoot libraries. This is a packaging
         * failure, not a missing Ubuntu rootfs — do not download another rootfs to "fix" it.
         */
        const val MISSING_APK_MESSAGE: String =
            "AgentX native runtime is missing from this APK. " +
                "Rebuild/reinstall the APK with ARM64 PRoot native libraries."

        /** Probes [layout] using [exists] (a file predicate) for each required and optional library. */
        fun probe(
            layout: NativeRuntimeLayout,
            exists: (String) -> Boolean,
        ): NativeRuntimeProbe {
            val present = ArrayList<String>()
            val missing = ArrayList<String>()
            for (name in NativeRuntimeLayout.REQUIRED_LIBRARIES) {
                if (exists("${layout.nativeLibraryDir}/$name")) present += name else missing += name
            }
            val optional = NativeRuntimeLayout.OPTIONAL_LIBRARIES.filter {
                exists("${layout.nativeLibraryDir}/$it")
            }
            return NativeRuntimeProbe(present = present, missing = missing, optionalPresent = optional)
        }
    }
}
