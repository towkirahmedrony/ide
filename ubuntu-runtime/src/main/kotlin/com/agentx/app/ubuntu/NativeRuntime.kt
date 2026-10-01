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

    /** Where a rootfs is unpacked before it is validated and moved into [rootfs]. */
    val staging: String get() = "$runtimeDir/$STAGING_DIR"

    /**
     * PRoot's link-to-symlink store (`PROOT_L2S_DIR`).
     *
     * It must exist, be writable, live on a filesystem that supports symlinks (the app's data
     * directory does) and survive across sessions, so it sits *outside* the rootfs subtree and
     * is never replaced by an extraction. Ubuntu's coreutils and dpkg rely on this: without it
     * `ln`, `install` and dpkg's unpack step fail on the host filesystem.
     */
    val l2s: String get() = "$runtimeDir/$L2S_DIR"

    /** `PROOT_TMP_DIR`: PRoot's own scratch space. */
    val tmp: String get() = "$runtimeDir/$TMP_DIR"

    /** Where the downloaded rootfs archive is cached between attempts. */
    val downloads: String get() = "$runtimeDir/$DOWNLOADS_DIR"

    /** Generated `resolv.conf`, bind-mounted into the guest as `/etc/resolv.conf`. */
    val resolvConf: String get() = "$runtimeDir/etc/resolv.conf"

    /** Install marker, written only after the extracted tree has been validated. */
    val marker: String get() = "$rootfs/$MARKER_PATH"

    /** The guest path of [runtimeDir] is not needed; only the pieces above are bound. */

    /** All directories the runtime needs before it can start, in creation order. */
    val requiredDirectories: List<String>
        get() = listOf(runtimeDir, downloads, tmp, l2s, "$runtimeDir/etc")

    companion object {
        const val PROOT_LIBRARY: String = "libproot.so"
        const val LOADER_LIBRARY: String = "libproot_loader.so"
        const val LOADER32_LIBRARY: String = "libproot_loader32.so"
        const val SHMEM_LIBRARY: String = "libandroid-shmem.so"
        const val TALLOC_LIBRARY: String = "libtalloc.so"

        const val ROOTFS_DIR: String = "rootfs"
        const val STAGING_DIR: String = "rootfs-staging"
        const val L2S_DIR: String = "l2s"
        const val TMP_DIR: String = "tmp"
        const val DOWNLOADS_DIR: String = "downloads"

        const val MARKER_PATH: String = "etc/agentx/developer-runtime.ok"

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
            "Native runtime is incomplete in nativeLibraryDir. Missing: ${missing.joinToString()}. " +
                "Present: ${present.ifEmpty { listOf("(none)") }.joinToString()}."
        }

    companion object {
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
