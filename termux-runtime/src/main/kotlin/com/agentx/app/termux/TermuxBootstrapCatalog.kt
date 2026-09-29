package com.agentx.app.termux

/**
 * The official Termux bootstrap releases this runtime can install.
 *
 * Values are the pins from termux-app's own `app/build.gradle` (`downloadBootstraps`), which is
 * how the Termux app verifies the same archives before embedding them. Keeping the checksums
 * here means a compromised or truncated download is rejected instead of extracted.
 *
 * Updating: bump [RELEASE], copy the four digests from upstream, and let
 * `TermuxBootstrapCatalogTest` check that every supported ABI is covered.
 */
object TermuxBootstrapCatalog {

    /** termux-packages release tag segment; URL-encoded `+` separates variant from version. */
    const val RELEASE: String = "2026.02.12-r1"

    /** Package variant; Android 7+ is the only one Termux still ships packages for. */
    const val VARIANT: String = "apt.android-7"

    /** `<TERMUX__PREFIX>/bin` — what the archive's entries are relative to. */
    const val ARCHIVE_ROOT: String = "."

    const val RELEASE_BASE_URL: String =
        "https://github.com/termux/termux-packages/releases/download/bootstrap-$RELEASE%2B$VARIANT"

    /** A single bootstrap archive. */
    data class Entry(
        /** Termux target triple, as it appears in the release asset name. */
        val termuxArch: String,
        /** Android ABI this archive runs on. */
        val androidAbi: String,
        val sha256: String,
    ) {
        val fileName: String get() = "bootstrap-$termuxArch.zip"
        val url: String get() = "$RELEASE_BASE_URL/$fileName"
        val targetPath: String get() = "lib/bootstrap-$termuxArch.zip"
    }

    /**
     * Pinned artifacts, digests taken verbatim from termux-app `app/build.gradle`.
     * `arm` = armeabi-v7a, `i686` = x86, `aarch64` = arm64-v8a, `x86_64` = x86_64.
     */
    val entries: List<Entry> = listOf(
        Entry(
            termuxArch = "aarch64",
            androidAbi = "arm64-v8a",
            sha256 = "ea2aeba8819e517db711f8c32369e89e7c52cee73e07930ff91185e1ab93f4f3",
        ),
        Entry(
            termuxArch = "arm",
            androidAbi = "armeabi-v7a",
            sha256 = "a38f4d3b2f735f83be2bf54eff463e86dc32a3e2f9f861c1557c4378d249c018",
        ),
        Entry(
            termuxArch = "i686",
            androidAbi = "x86",
            sha256 = "f5bc0b025b9f3b420b5fcaeefc064f888f5f22a0d6fd7090f4aac0c33eb3555b",
        ),
        Entry(
            termuxArch = "x86_64",
            androidAbi = "x86_64",
            sha256 = "b7fd0f2e3a4de534be3144f9f91acc768630fc463eaf134ab2e64c545e834f7a",
        ),
    )

    /** The archive to use on a device reporting these supported ABIs, most preferred first. */
    fun forAbis(supportedAbis: List<String>): Entry? {
        for (abi in supportedAbis) {
            entries.firstOrNull { it.androidAbi == abi }?.let { return it }
        }
        return null
    }

    fun forAbi(abi: String): Entry? = entries.firstOrNull { it.androidAbi == abi }

    fun forTermuxArch(termuxArch: String): Entry? = entries.firstOrNull { it.termuxArch == termuxArch }
}
