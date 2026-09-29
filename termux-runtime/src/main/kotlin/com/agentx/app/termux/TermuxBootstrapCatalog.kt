package com.agentx.app.termux

/**
 * Catalog of AgentX custom-prefix bootstrap archives.
 *
 * Official Termux bootstraps are compiled for [TermuxPaths.OFFICIAL_PREFIX] and must not be
 * listed here. Until Part 2 produces real archives, every entry is unavailable: no URL, no
 * SHA-256, no size. Provisioning must fail with that fact instead of downloading anything.
 */
object TermuxBootstrapCatalog {

    const val SOURCE_REVISION_UNBUILT: String = "unbuilt"

    data class Entry(
        val androidAbi: String,
        val termuxArch: String,
        val prefix: String,
        val sourceRevision: String,
        val assetName: String,
        val url: String?,
        val sha256: String?,
        val archiveSizeBytes: Long?,
        val fileCount: Int?,
    ) {
        val available: Boolean
            get() = !url.isNullOrBlank() &&
                sha256 != null &&
                sha256.length == 64 &&
                sha256.all { it in "0123456789abcdef" } &&
                archiveSizeBytes != null &&
                archiveSizeBytes > 0L &&
                fileCount != null &&
                fileCount > 0 &&
                sourceRevision != SOURCE_REVISION_UNBUILT

        fun unavailableReason(): String =
            "No custom AgentX bootstrap is available yet for $androidAbi. " +
                "Part 2 must produce $assetName built for prefix $prefix " +
                "(sourceRevision=$sourceRevision) with a real SHA-256, URL or asset, archive size and file count. " +
                "Official Termux archives for ${TermuxPaths.OFFICIAL_PREFIX} must not be used."
    }

    val entries: List<Entry> = listOf(
        Entry(
            androidAbi = "arm64-v8a",
            termuxArch = "aarch64",
            prefix = TermuxPaths.AGENTX_PREFIX,
            sourceRevision = "termux-packages@2fdb0c07f3fec34adf24c8af515c852fc51f4c9b+agentx",
            assetName = "bootstrap-aarch64.zip",
            url = "https://github.com/towkirahmedrony/ide/releases/download/agentx-bootstrap-2026.09.30-r1/bootstrap-aarch64.zip",
            sha256 = "163fe26fbf96da5e2e2cff387fb59e0d5b3ceb882fd49b61ef3b3bd0b8806bdc",
            archiveSizeBytes = 26398671L,
            fileCount = 2833,
        ),
        pending("armeabi-v7a", "arm"),
        pending("x86", "i686"),
        pending("x86_64", "x86_64"),
    )

    fun forAbis(supportedAbis: List<String>): Entry? {
        for (abi in supportedAbis) {
            entries.firstOrNull { it.androidAbi == abi }?.let { return it }
        }
        return null
    }

    fun forAbi(abi: String): Entry? = entries.firstOrNull { it.androidAbi == abi }

    fun forTermuxArch(termuxArch: String): Entry? = entries.firstOrNull { it.termuxArch == termuxArch }

    private fun pending(androidAbi: String, termuxArch: String): Entry = Entry(
        androidAbi = androidAbi,
        termuxArch = termuxArch,
        prefix = TermuxPaths.AGENTX_PREFIX,
        sourceRevision = SOURCE_REVISION_UNBUILT,
        assetName = "bootstrap-$termuxArch.zip",
        url = null,
        sha256 = null,
        archiveSizeBytes = null,
        fileCount = null,
    )
}
