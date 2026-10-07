package com.agentx.app.ui.ide.permissions

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner

/**
 * Reads the real Android permission/access state for [AgentPermission] and builds the system
 * screens that can grant what the app cannot request itself.
 *
 * This is the only place the app touches Android's permission APIs for the Permissions screen.
 * Everything it feeds the UI is a value, so a denial is just a state the screen renders.
 */
class AndroidPermissionReader(private val context: Context) {

    private val activity: Activity? = context.findActivity()

    /**
     * The host Activity's lifecycle, when there is one. The screen re-reads the real state on
     * every resume, so a grant made in Android Settings shows up as soon as the user returns.
     */
    val lifecycle: Lifecycle? = (activity as? LifecycleOwner)?.lifecycle

    private val ledger = PermissionRequestLedger(context)

    /** The real state of every permission AgentX declares, in display order. */
    fun snapshot(): List<AgentPermissionState> = AgentPermission.entries.map(::stateOf)

    /** Records that the app asked for [permission], which is how "don't ask again" is told apart. */
    fun markRequested(permission: AgentPermission) = ledger.markRequested(permission.androidName)

    /**
     * The system screens that can grant [target], best match first. A page that cannot be
     * resolved on a given device is skipped so the caller can fall back to the next one.
     */
    fun settingsIntents(target: PermissionSettingsTarget): List<Intent> {
        val packageUri = Uri.fromParts("package", context.packageName, null)
        return when (target) {
            PermissionSettingsTarget.ALL_FILES -> listOf(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri),
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri),
            )

            PermissionSettingsTarget.NOTIFICATIONS -> listOf(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri),
            )

            PermissionSettingsTarget.APP_DETAILS -> listOf(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri),
            )
        }
    }

    private fun stateOf(permission: AgentPermission): AgentPermissionState {
        val sdkInt = Build.VERSION.SDK_INT
        val resolution = when (permission) {
            AgentPermission.NOTIFICATIONS -> notificationsResolution(
                sdkInt = sdkInt,
                permissionGranted = permissionGranted(permission.androidName),
                notificationsEnabled = notificationsEnabled(),
                canAskAgain = canAskAgain(permission),
            )

            else -> permission.resolve(
                PermissionSignals(
                    sdkInt = sdkInt,
                    granted = isGranted(permission),
                    runtimeGrantable = isRuntimeGrantable(permission, sdkInt),
                    canAskAgain = canAskAgain(permission),
                ),
            )
        }
        return AgentPermissionState(permission, resolution.status, resolution.action)
    }

    private fun isGranted(permission: AgentPermission): Boolean =
        when (permission) {
            // "All files access" has no per-permission check; Android exposes it as a mode.
            AgentPermission.ALL_FILES_ACCESS ->
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

            else -> permissionGranted(permission.androidName)
        }

    private fun permissionGranted(name: String): Boolean =
        context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED

    private fun notificationsEnabled(): Boolean {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        return manager?.areNotificationsEnabled() == true
    }

    private fun isRuntimeGrantable(permission: AgentPermission, sdkInt: Int): Boolean {
        val runtimeSdk = permission.runtimeSdk ?: return false
        return sdkInt >= runtimeSdk
    }

    /**
     * Whether Android will still show the request dialog for [permission].
     *
     * `shouldShowRequestPermissionRationale` is false both before the first request and after the
     * user refused to be asked again, so the app's own record of having asked is what separates
     * "not asked yet" from "don't ask again". While the ledger says it has never been asked, or
     * while Android is still willing to explain a denial, the dialog is offered again.
     */
    private fun canAskAgain(permission: AgentPermission): Boolean {
        if (!isRuntimeGrantable(permission, Build.VERSION.SDK_INT)) return false
        if (ledger.wasRequested(permission.androidName)) {
            return activity?.shouldShowRequestPermissionRationale(permission.androidName) == true
        }
        return true
    }
}

/** Unwraps the host Activity from a Compose `LocalContext`, or null when there is none. */
private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/**
 * Remembers which runtime permissions the app has actually asked for. Android alone cannot tell
 * "never asked" apart from "don't ask again", so this small record lives beside the permission
 * manager and is used only to report the status honestly.
 */
private class PermissionRequestLedger(context: Context) {

    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun wasRequested(name: String): Boolean = preferences.getBoolean(name, false)

    fun markRequested(name: String) {
        preferences.edit().putBoolean(name, true).apply()
    }

    private companion object {
        const val PREFERENCES = "agentx.permissions"
    }
}
