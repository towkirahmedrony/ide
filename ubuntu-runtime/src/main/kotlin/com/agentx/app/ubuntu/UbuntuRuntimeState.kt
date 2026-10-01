package com.agentx.app.ubuntu

/**
 * Lifecycle of the embedded developer runtime, in the order a user meets it.
 *
 * These are the states the brief names. They are a single source of truth: the UI reads them
 * from [LocalUbuntuRuntime.state] rather than re-deriving them, so the install progress, the
 * "runtime missing" screen and the terminal header cannot disagree.
 */
enum class AgentxRuntimeState(val wireName: String) {
    /** No verified rootfs is installed. The first-run screen is shown. */
    NOT_INSTALLED("not_installed"),

    /** The Ubuntu rootfs archive is being fetched. */
    DOWNLOADING("downloading"),

    /** The archive is being hashed against the pinned SHA-256. */
    VERIFYING("verifying"),

    /** The verified archive is being unpacked. */
    EXTRACTING("extracting"),

    /** The tree exists and is being configured (apt, sources, resolv, runtime directories). */
    INSTALLING("installing"),

    /** A verified rootfs is present and a guest process can be started. */
    READY("ready"),

    /** The first PRoot process is being started. */
    STARTING("starting"),

    /** At least one developer shell or long-running process is live. */
    RUNNING("running"),

    /** Installation or startup failed. [RuntimeStatus.message] says what and where. */
    ERROR("error"),
}

/**
 * The step that actually failed, so the UI can name it instead of showing one undifferentiated
 * "install failed". The names match the interesting boundaries of [UbuntuRootfsInstaller].
 */
enum class UbuntuInstallStage(val wireName: String) {
    DOWNLOAD("download"),
    CHECKSUM("checksum"),
    EXTRACTION("extraction"),
    VALIDATION("validation"),
    CONFIGURATION("configuration"),
    ACTIVATION("activation"),
    RUNTIME("runtime"),
}

/**
 * What the runtime is doing right now.
 *
 * @param state the coarse lifecycle state (drives which screen is shown).
 * @param progressPercent 0..100 while [state] is [AgentxRuntimeState.DOWNLOADING].
 * @param installedBytes / [totalBytes] sizes for the download progress line.
 * @param stage which step failed, when [state] is [AgentxRuntimeState.ERROR].
 * @param message human-readable detail for the current state or the failure.
 */
data class RuntimeStatus(
    val state: AgentxRuntimeState,
    val progressPercent: Int = 0,
    val installedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val stage: UbuntuInstallStage? = null,
    val message: String? = null,
) {
    val isBusy: Boolean
        get() = state == AgentxRuntimeState.DOWNLOADING ||
            state == AgentxRuntimeState.VERIFYING ||
            state == AgentxRuntimeState.EXTRACTING ||
            state == AgentxRuntimeState.INSTALLING ||
            state == AgentxRuntimeState.STARTING

    val canInstall: Boolean
        get() = state == AgentxRuntimeState.NOT_INSTALLED || state == AgentxRuntimeState.ERROR

    companion object {
        val NotInstalled: RuntimeStatus = RuntimeStatus(AgentxRuntimeState.NOT_INSTALLED)
        val Ready: RuntimeStatus = RuntimeStatus(AgentxRuntimeState.READY)
    }
}
