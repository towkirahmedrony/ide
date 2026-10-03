package com.agentx.app.ubuntu

/**
 * Catalog of the Ubuntu ARM64 Base rootfs.
 *
 * Only a real, verifiable entry is listed. There is no "skip verification" path: an entry
 * without a URL, a 64-character lower-case hex SHA-256, a positive size and a named upstream
 * revision is not [Entry.available], and provisioning refuses rather than downloading
 * something unverifiable.
 *
 * The arm64 values below were read from the upstream `SHA256SUMS` and a `HEAD` request on the
 * release directory (`third_party/ubuntu/README.md` records how to re-derive them). They are
 * the official Ubuntu Base image, which is the rootfs source the brief chose; it is a real
 * Ubuntu userland with `bash`, `sh`, `coreutils`, `apt` and `dpkg`, so the runtime does not
 * depend on Termux's package manager at all.
 */
object UbuntuRootfsCatalog {

    const val SOURCE_REVISION_UNBUILT: String = "unbuilt"

    /** Ubuntu Base release currently pinned. */
    const val UBUNTU_RELEASE: String = "24.04.5"
    const val UBUNTU_CODENAME: String = "noble"

    /** The one ABI this runtime ships today. Others are listed as unavailable, not fabricated. */
    const val SUPPORTED_ANDROID_ABI: String = "arm64-v8a"

    /** Rough extracted size of the 24.04 arm64 base image, for the install screen's estimate. */
    const val INSTALLED_SIZE_APPROX_BYTES: Long = 110L * 1024L * 1024L

    /**
     * The platform `tar` the extraction is piped through PRoot.
     *
     * It is Android's own toybox tar. The extraction never runs it directly: it is started
     * *by* PRoot with `-l` so that the archive's hard links become symlinks, see
     * [UbuntuRootfsInstaller].
     */
    const val HOST_TAR: String = "/system/bin/tar"

    /**
     * Packages the developer toolchain is made of, installed with the guest's own `apt-get`.
     *
     * Nothing here is a Termux package: these come from Ubuntu's `ports.ubuntu.com/ubuntu-ports`
     * archive, which is what a real Ubuntu userland expects. `git`, `gh`, `python3`, `nodejs`
     * and `npm` are ordinary Ubuntu packages, not a re-created package ecosystem.
     *
     * `debconf` is here for `/usr/sbin/dpkg-preconfigure`, and only for that. Ubuntu Base ships
     * the `70debconf` apt hook in `/etc/apt/apt.conf.d` but not the package that implements it,
     * so a fresh guest printed `/bin/sh: 1: /usr/sbin/dpkg-preconfigure: not found` on every
     * install. It is a consequence of the base image being minimal, not of anything apt does
     * wrong, and one explicit dependency removes it instead of leaving a permanent error in the
     * install log for the next person to chase.
     */
    val TOOLCHAIN_PACKAGES: List<String> = listOf(
        "bash",
        "apt",
        "apt-utils",
        "dpkg",
        "debconf",
        "git",
        "gh",
        "python3",
        "python3-pip",
        "nodejs",
        "npm",
        "curl",
        "wget",
        "ca-certificates",
        "openssh-client",
        "ripgrep",
    )

    /**
     * One executable the developer toolchain has to provide, with the output that proves it ran.
     *
     * These are the brief's list, plus `apt` and `dpkg` themselves. They are checked *inside the
     * guest* after the install, because a package that unpacked is not a binary that starts: the
     * `perl-base` unpack failed outright, and a rootfs whose link-to-symlink store is unreachable
     * fails on exactly the entries whose packages contain a hard link, nowhere else.
     */
    data class ToolchainCommand(
        /** What the check is called in reports. */
        val label: String,
        /**
         * The guest command. It is run by a shell with the guest `PATH`, and `stdout` and
         * `stderr` are merged: `ssh -V` answers on `stderr`.
         */
        val command: String,
        /**
         * A substring the merged output must contain, or null when a zero exit status and
         * non-empty output are the whole test (`npm --version` prints a bare version).
         */
        val expectOutput: String? = null,
    )

    val REQUIRED_TOOLCHAIN_COMMANDS: List<ToolchainCommand> = listOf(
        ToolchainCommand(label = "git", command = "git --version", expectOutput = "git version"),
        ToolchainCommand(label = "python3", command = "python3 --version", expectOutput = "Python 3"),
        ToolchainCommand(label = "pip", command = "python3 -m pip --version", expectOutput = "pip"),
        ToolchainCommand(label = "node", command = "node --version", expectOutput = "v"),
        ToolchainCommand(label = "npm", command = "npm --version"),
        ToolchainCommand(label = "gh", command = "gh --version", expectOutput = "gh version"),
        ToolchainCommand(label = "rg", command = "rg --version", expectOutput = "ripgrep"),
        ToolchainCommand(label = "curl", command = "curl --version", expectOutput = "curl"),
        ToolchainCommand(label = "wget", command = "wget --version", expectOutput = "Wget"),
        ToolchainCommand(label = "ssh", command = "ssh -V", expectOutput = "OpenSSH"),
        ToolchainCommand(label = "apt", command = "apt --version", expectOutput = "apt"),
        ToolchainCommand(label = "dpkg", command = "dpkg --version", expectOutput = "dpkg"),
    )

    /**
     * A hard link the Ubuntu Base archive contains.
     *
     * [file] is stored as a regular file, [link] as a hard link to it. Ubuntu Base 24.04.5 arm64
     * contains exactly two of them, both verified against the published archive's SHA-256
     * (`third_party/ubuntu/README.md`):
     *
     * ```text
     * usr/bin/perl5.38.2  link to  usr/bin/perl
     * usr/bin/uncompress  link to  usr/bin/gunzip
     * ```
     *
     * They are the reason a plain `tar -x` cannot install this rootfs: Android's SELinux policy
     * forbids an untrusted app from creating a hard link at all, so `tar` fails with
     * `can't link ... : Permission denied`. See [UbuntuRootfsInstaller].
     */
    data class HardLink(
        /** Absolute guest path of the regular file the archive stores. */
        val file: String,
        /** Absolute guest path of the hard link the archive stores. */
        val link: String,
    )

    /**
     * The hard links extraction must preserve, stated as relationships rather than paths.
     *
     * Validation checks that the two entries really name the same file after extraction (a
     * real hard link, or PRoot's emulated one), not merely that both paths exist.
     */
    val REQUIRED_HARD_LINKS: List<HardLink> = listOf(
        HardLink(file = "usr/bin/perl", link = "usr/bin/perl5.38.2"),
        HardLink(file = "usr/bin/gunzip", link = "usr/bin/uncompress"),
    )

    data class Entry(
        val androidAbi: String,
        val ubuntuRelease: String,
        val assetName: String,
        val url: String?,
        val sha256: String?,
        val archiveSizeBytes: Long?,
        val sourceRevision: String,
        /** Absolute guest paths that must exist after extraction, checked before the marker. */
        val requiredGuestFiles: List<String> = REQUIRED_GUEST_FILES,
    ) {
        val available: Boolean
            get() = !url.isNullOrBlank() &&
                sha256 != null &&
                sha256.length == 64 &&
                sha256.all { it in "0123456789abcdef" } &&
                archiveSizeBytes != null &&
                archiveSizeBytes > 0L &&
                sourceRevision != SOURCE_REVISION_UNBUILT

        val downloadSizeApproxBytes: Long get() = archiveSizeBytes ?: 0L

        fun unavailableReason(): String =
            "No verified Ubuntu rootfs is available for $androidAbi. " +
                "$assetName must be published with an immutable URL, a 64-character lower-case " +
                "SHA-256, a positive size and a named upstream revision before it can be " +
                "installed (sourceRevision=$sourceRevision)."
    }

    /**
     * The entries that prove the extracted tree is a complete Ubuntu userland.
     *
     * Declared before [entries] because an [Entry]'s default refers to it. These are checked by
     * [UbuntuRootfsInstaller] as the *authoritative* answer to "is this a rootfs?" — the same list
     * decides whether a tree found on disk may be reused, whether the install marker may be
     * honoured, and whether an installation may be promoted. A marker never overrides it.
     *
     * Directories are listed, not only files. A tree that kept `/usr/bin/perl` while losing `/usr`
     * or `/var` is not a Ubuntu rootfs, and "the files I looked for happen to be there" is exactly
     * the answer that let a stale tree be treated as installed.
     *
     * `bin/bash` is listed as well as `usr/bin/bash` on purpose: Ubuntu uses a merged `/usr`, so
     * `bin` is a symlink to `usr/bin`, and requiring both proves symlinks survived extraction.
     * The hard-link pairs are here because dpkg and coreutils resolve through them; see
     * [REQUIRED_HARD_LINKS] and the relationship check in the installer.
     */
    val REQUIRED_GUEST_FILES: List<String> = listOf(
        // The guest shell, by both names.
        "bin/sh",
        "bin/bash",
        "usr/bin/sh",
        "usr/bin/bash",
        "usr/bin/dash",
        // Identity of the guest root.
        "etc/os-release",
        // The userland tree itself.
        "usr",
        "usr/bin",
        "usr/lib",
        "etc",
        "var",
        "var/lib",
        "home",
        "root",
        "tmp",
        // The package manager, which is what the whole installation exists to use.
        "usr/bin/apt-get",
        "usr/bin/dpkg",
        "usr/bin/env",
        "usr/bin/ls",
        // The hard-link pairs.
        "usr/bin/perl",
        "usr/bin/perl5.38.2",
        "usr/bin/gunzip",
        "usr/bin/uncompress",
    )

    /**
     * Directories a complete runtime has that the base archive does not necessarily ship.
     *
     * They are created by the installer's configuration step — `apt` and `dpkg` need them and
     * create them on first use, which is too late to be a precondition of using apt. Checked
     * after configuration, not after extraction, so validation never demands something the
     * archive was never going to contain.
     */
    val REQUIRED_RUNTIME_DIRECTORIES: List<String> = listOf(
        "var/lib/dpkg",
        "var/cache/apt",
        "var/cache/apt/archives",
        "var/log/apt",
        "run",
        "tmp",
    )

    val entries: List<Entry> = listOf(
        Entry(
            androidAbi = "arm64-v8a",
            ubuntuRelease = UBUNTU_RELEASE,
            assetName = "ubuntu-base-$UBUNTU_RELEASE-base-arm64.tar.gz",
            url = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/" +
                "ubuntu-base-$UBUNTU_RELEASE-base-arm64.tar.gz",
            sha256 = "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2",
            archiveSizeBytes = 29_936_675L,
            sourceRevision = "ubuntu-base:$UBUNTU_RELEASE-$UBUNTU_CODENAME@cdimage.ubuntu.com",
        ),
        pending("armeabi-v7a"),
        pending("x86"),
        pending("x86_64"),
    )

    fun forAbis(supportedAbis: List<String>): Entry? {
        for (abi in supportedAbis) {
            entries.firstOrNull { it.androidAbi == abi }?.let { return it }
        }
        return null
    }

    fun forAbi(abi: String): Entry? = entries.firstOrNull { it.androidAbi == abi }

    private fun pending(androidAbi: String): Entry = Entry(
        androidAbi = androidAbi,
        ubuntuRelease = UBUNTU_RELEASE,
        assetName = "ubuntu-base-$UBUNTU_RELEASE-base-<arch>.tar.gz",
        url = null,
        sha256 = null,
        archiveSizeBytes = null,
        sourceRevision = SOURCE_REVISION_UNBUILT,
    )
}
