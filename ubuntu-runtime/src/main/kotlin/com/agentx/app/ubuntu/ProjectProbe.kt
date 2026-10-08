package com.agentx.app.ubuntu

import android.os.Build
import android.os.Environment
import com.agentx.app.termux.DeveloperLogCategory
import com.agentx.app.termux.DeveloperLogger
import java.io.File

/**
 * Whether [path] can really be bound at /workspace.
 *
 * `isDirectory && canRead()` is not enough on Android 11+: without "All files access" a shared-storage
 * folder can pass both checks while its non-media files are filtered out, which shows up as an
 * empty /workspace. For shared storage the real answer therefore also needs All files access.
 * Every decision is logged so the Developer Logs screen shows why a path was or was not bound.
 *
 * What counts as shared storage is not decided here: [UbuntuProjectBindings.isSharedStorageLocation]
 * owns that definition, so this gate and the terminal's "Grant Access" prompt cannot disagree about
 * whether a given project is the kind that needs the access.
 */
internal fun probeProjectDirectory(path: String): Boolean {
    val dir = File(path)
    val isDir = dir.isDirectory
    val canRead = dir.canRead()
    val sharedStorage = UbuntuProjectBindings.isSharedStorageLocation(path)
    val allFiles = Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
        runCatching { Environment.isExternalStorageManager() }.getOrDefault(true)
    val entries = if (isDir) dir.list()?.size else null
    val usable = isDir && canRead && (!sharedStorage || allFiles)
    DeveloperLogger.info(
        DeveloperLogCategory.ROOTFS,
        "Project probe path=$path isDir=$isDir canRead=$canRead sharedStorage=$sharedStorage " +
            "allFilesAccess=$allFiles entries=$entries usable=$usable",
    )
    return usable
}
