package com.agentx.app.termux

/**
 * Filesystem layout of the embedded Termux environment.
 *
 * Mirrors the layout documented in termux-packages
 * (`scripts/properties.sh`: `TERMUX__ROOTFS = $TERMUX_APP__DATA_DIR/files`,
 * `TERMUX__PREFIX = $TERMUX__ROOTFS/usr`, `TERMUX__HOME = $TERMUX__ROOTFS/home`),
 * except that the data directory is this app's own `filesDir` unless the build opts
 * into the official prefix. See [TermuxPrefixPolicy].
 *
 * Pure data: no Android API, so the layout can be unit tested.
 */
data class TermuxPaths(
    /** Absolute path of the app's private data directory, e.g. `/data/data/com.agentx.app`. */
    val appDataDir: String,
    /** Subdirectory that holds the Termux rootfs. Upstream calls this `files`. */
    val rootfsSubdir: String = ROOTFS_SUBDIR,
) {
    /** Termux rootfs, upstream `$TERMUX__ROOTFS`. */
    val rootfs: String get() = "$appDataDir/$rootfsSubdir"

    /** Package prefix, upstream `$TERMUX__PREFIX` and the shell's `$PREFIX`. */
    val prefix: String get() = "$rootfs/$USR_SUBDIR"

    /** Staging prefix the bootstrap is unpacked into before it is moved into place. */
    val stagingPrefix: String get() = "$rootfs/usr-staging"

    /** Login directory, upstream `$TERMUX__HOME`. */
    val home: String get() = "$rootfs/home"

    val bin: String get() = "$prefix/bin"
    val lib: String get() = "$prefix/lib"
    val tmp: String get() = "$prefix/tmp"

    /** Environment file that `$PREFIX/etc/profile` sources in a login shell. */
    val envFile: String get() = "$prefix/etc/termux/termux.env"

    /** Where the bootstrap archive is cached between downloads. */
    val downloadDir: String get() = "$rootfs/.bootstrap"

    /** Termux-visible project trees, used to mirror workspaces the shell cannot reach. */
    val workspaces: String get() = "$rootfs/workspaces"

    /** `$HOME/.bashrc` and friends live here; created on first provision. */
    val homeStorage: String get() = "$home/storage"

    /** True when [prefix] is the prefix every official Termux artifact is compiled against. */
    val usesOfficialPrefix: Boolean get() = prefix == OFFICIAL_PREFIX

    /** True when [appDataDir] is the data directory official Termux artifacts assume. */
    val usesOfficialPackageDir: Boolean get() = appDataDir == OFFICIAL_APP_DATA_DIR

    companion object {
        const val ROOTFS_SUBDIR: String = "files"
        const val USR_SUBDIR: String = "usr"

        /** Package name Termux itself uses; its data dir is what official artifacts hard-code. */
        const val OFFICIAL_PACKAGE_NAME: String = "com.termux"
        const val OFFICIAL_APP_DATA_DIR: String = "/data/data/$OFFICIAL_PACKAGE_NAME"

        /** The prefix termux-packages bakes into every bootstrap and repository package. */
        const val OFFICIAL_PREFIX: String = "$OFFICIAL_APP_DATA_DIR/$ROOTFS_SUBDIR/$USR_SUBDIR"

        /** Upstream rejects prefixes longer than 90 bytes including the NUL terminator. */
        const val MAX_PREFIX_LENGTH: Int = 89

        /**
         * Builds the layout for an application data directory.
         *
         * `/data/user/0/<pkg>` is an alias of `/data/data/<pkg>`; the canonical form is used
         * because paths appear in shell output and in `/proc/<pid>/cwd` comparisons.
         */
        fun forAppDataDir(appDataDir: String, rootfsSubdir: String = ROOTFS_SUBDIR): TermuxPaths =
            TermuxPaths(appDataDir = canonicalAppDataDir(appDataDir), rootfsSubdir = rootfsSubdir)

        /** Rewrites the multi-user alias `/data/user/<n>/<pkg>` to `/data/data/<pkg>`. */
        fun canonicalAppDataDir(path: String): String {
            val trimmed = path.trimEnd('/')
            if (!trimmed.startsWith("/data/user/")) return trimmed
            val afterPrefix = trimmed.removePrefix("/data/user/")
            val separator = afterPrefix.indexOf('/')
            if (separator <= 0) return trimmed
            return "/data/data/" + afterPrefix.substring(separator + 1)
        }
    }
}
