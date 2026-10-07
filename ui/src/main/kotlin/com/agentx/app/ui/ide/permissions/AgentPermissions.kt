package com.agentx.app.ui.ide.permissions

/**
 * One Android permission or special access that AgentX declares and actually uses.
 *
 * This list is the whole of what the Permissions screen shows, and every entry maps to a real
 * declaration in this app's merged manifest and a real feature that exercises it. Nothing is
 * listed because an IDE could use it: AgentX does not request the camera or the microphone. The one
 * storage access it does declare — "All files access" — is here because projects and clones really
 * live in shared storage, so a denial turns those features off and the row says which ones.
 */
enum class AgentPermission(
    /** The manifest permission name, exactly as declared. */
    val androidName: String,
    /** Short name shown on the row. */
    val label: String,
    /** The AgentX feature that uses this permission. */
    val feature: String,
    /** Why AgentX needs it, in the user's terms. */
    val purpose: String,
    val category: PermissionCategory,
    /** A denial only disables one capability; the rest of AgentX keeps working. */
    val optional: Boolean = false,
    /** Lowest Android API level at which this declaration exists. */
    val minSdk: Int = 1,
    /** For a runtime permission, the API level from which Android asks for it at runtime. */
    val runtimeSdk: Int? = null,
    /** The system screen that can grant this access once it has been denied. */
    val settingsTarget: PermissionSettingsTarget = PermissionSettingsTarget.APP_DETAILS,
) {
    /** The model gateway, Git, web tools and the terminal all reach the network. */
    INTERNET(
        androidName = "android.permission.INTERNET",
        label = "Internet access",
        feature = "Models, Git, web tools, terminal",
        purpose = "Connects to the configured model endpoint, clones and pushes Git repositories, " +
            "reads GitHub checks, opens authorization pages, and lets the terminal install packages.",
        category = PermissionCategory.NORMAL,
    ),

    /** Reading the active network so the guest gets a correct resolver configuration. */
    NETWORK_STATE(
        androidName = "android.permission.ACCESS_NETWORK_STATE",
        label = "Network state",
        feature = "Embedded developer runtime",
        purpose = "Reads the active network's DNS servers so the embedded Ubuntu runtime writes a " +
            "correct /etc/resolv.conf for the guest shell.",
        category = PermissionCategory.NORMAL,
    ),

    /** Keeps a terminal session alive while the app is in the background. */
    FOREGROUND_SERVICE(
        androidName = "android.permission.FOREGROUND_SERVICE",
        label = "Foreground service",
        feature = "Terminal keep-alive",
        purpose = "Lets the terminal keep a foreground service running, so a development server " +
            "started from the shell is not stopped when AgentX goes to the background.",
        category = PermissionCategory.NORMAL,
        minSdk = 28,
    ),

    /** The typed foreground service a modern Android requires for the keep-alive service. */
    FOREGROUND_SERVICE_DATA_SYNC(
        androidName = "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
        label = "Foreground service (data sync)",
        feature = "Terminal keep-alive",
        purpose = "Declares the dataSync type for the terminal's keep-alive service. Android 14 " +
            "and later require the type before the service may start.",
        category = PermissionCategory.NORMAL,
        minSdk = 34,
    ),

    /** The terminal's ongoing notification and its keep-alive service. */
    NOTIFICATIONS(
        androidName = "android.permission.POST_NOTIFICATIONS",
        label = "Notifications",
        feature = "Terminal keep-alive",
        purpose = "Shows the terminal's ongoing notification, so a running shell session is " +
            "visible and Android does not stop its keep-alive service. The terminal still runs " +
            "without it.",
        category = PermissionCategory.RUNTIME,
        optional = true,
        runtimeSdk = 33,
        settingsTarget = PermissionSettingsTarget.NOTIFICATIONS,
    ),

    /** The AgentX project folder in shared storage, and binding a picked project into the guest. */
    ALL_FILES_ACCESS(
        androidName = "android.permission.MANAGE_EXTERNAL_STORAGE",
        label = "All files access",
        feature = "Projects, GitHub clones, terminal project bind",
        purpose = "Lets AgentX create and manage its projects in the AgentX folder in your shared " +
            "storage, clone GitHub repositories into that same folder, and bind a project folder " +
            "at its real phone-storage path for the embedded terminal — so a change made in the " +
            "terminal is the same file the IDE reads. Without it AgentX cannot create a project " +
            "or clone a repository, and the shell runs in the guest home with nothing mounted.",
        category = PermissionCategory.SPECIAL,
        optional = true,
        minSdk = 30,
        settingsTarget = PermissionSettingsTarget.ALL_FILES,
    ),
}

/** How Android manages a permission, which is also how the screen groups the rows. */
enum class PermissionCategory(
    val sectionTitle: String,
    val sectionNote: String,
) {
    SPECIAL(
        "Special app access",
        "Granted from Android Settings, never by an in-app dialog.",
    ),
    RUNTIME(
        "Runtime permissions",
        "Android asks for these while the app is running.",
    ),
    NORMAL(
        "Normal permissions",
        "Granted automatically at install; Android does not let you turn them off per app.",
    ),
}

/** The real platform state of one permission, as the screen shows it. */
enum class PermissionStatus {
    GRANTED,
    DENIED,
    PERMANENTLY_DENIED,
    NOT_APPLICABLE,
    UNAVAILABLE,
}

/** What the user can do about a permission, if anything. */
enum class PermissionAction {
    NONE,
    REQUEST,
    OPEN_SETTINGS,
}

/** Which system screen can grant an access that the app cannot request itself. */
enum class PermissionSettingsTarget {
    APP_DETAILS,
    ALL_FILES,
    NOTIFICATIONS,
}

/** The raw platform facts about one permission, gathered off-device so the rule stays pure. */
data class PermissionSignals(
    val sdkInt: Int,
    /** The platform reports the permission/access as granted. */
    val granted: Boolean,
    /** The permission exists as a runtime permission on this SDK. */
    val runtimeGrantable: Boolean,
    /** Android will still show the request dialog for this permission. */
    val canAskAgain: Boolean,
)

/** A permission's status together with the action that would change it. */
data class PermissionResolution(
    val status: PermissionStatus,
    val action: PermissionAction,
)

/** One row of the Permissions screen. */
data class AgentPermissionState(
    val permission: AgentPermission,
    val status: PermissionStatus,
    val action: PermissionAction,
)

/**
 * Resolves the status and action for this permission from the platform facts.
 *
 * The version boundaries are handled here rather than in the UI: a declaration that does not
 * exist on this Android version is [PermissionStatus.NOT_APPLICABLE], a runtime permission the
 * user denied offers the request dialog again only while Android is still willing to show it,
 * and a special access is only ever grantable from system settings.
 */
fun AgentPermission.resolve(signals: PermissionSignals): PermissionResolution {
    if (signals.sdkInt < minSdk) {
        return PermissionResolution(PermissionStatus.NOT_APPLICABLE, PermissionAction.NONE)
    }
    if (signals.granted) {
        return PermissionResolution(PermissionStatus.GRANTED, PermissionAction.NONE)
    }
    return when (category) {
        // A normal permission is granted at install. If the platform says otherwise, a policy
        // or a managed profile is blocking it and the app cannot ask for it.
        PermissionCategory.NORMAL ->
            PermissionResolution(PermissionStatus.UNAVAILABLE, PermissionAction.NONE)

        // A special access is only granted in system settings, never by a dialog.
        PermissionCategory.SPECIAL ->
            PermissionResolution(PermissionStatus.DENIED, PermissionAction.OPEN_SETTINGS)

        PermissionCategory.RUNTIME -> when {
            signals.runtimeGrantable && signals.canAskAgain ->
                PermissionResolution(PermissionStatus.DENIED, PermissionAction.REQUEST)

            signals.runtimeGrantable ->
                PermissionResolution(PermissionStatus.PERMANENTLY_DENIED, PermissionAction.OPEN_SETTINGS)

            // Not a runtime permission on this Android version: only the system setting applies.
            else -> PermissionResolution(PermissionStatus.DENIED, PermissionAction.OPEN_SETTINGS)
        }
    }
}

/**
 * Resolves the Notifications row across Android versions.
 *
 * Below Android 13 there is no runtime permission; whether AgentX may post notifications is the
 * user's system setting, so only Settings can change it. From Android 13 the runtime permission
 * is asked for first, and only once Android refuses to ask again does the row point at Settings.
 * A granted permission with the channel turned off is still a denial the user has to fix in
 * Settings, so it reports denied rather than pretending notifications work.
 */
fun notificationsResolution(
    sdkInt: Int,
    permissionGranted: Boolean,
    notificationsEnabled: Boolean,
    canAskAgain: Boolean,
): PermissionResolution = when {
    sdkInt >= AgentPermission.NOTIFICATIONS.runtimeSdk!! && !permissionGranted ->
        if (canAskAgain) {
            PermissionResolution(PermissionStatus.DENIED, PermissionAction.REQUEST)
        } else {
            PermissionResolution(PermissionStatus.PERMANENTLY_DENIED, PermissionAction.OPEN_SETTINGS)
        }

    notificationsEnabled -> PermissionResolution(PermissionStatus.GRANTED, PermissionAction.NONE)

    else -> PermissionResolution(PermissionStatus.DENIED, PermissionAction.OPEN_SETTINGS)
}
