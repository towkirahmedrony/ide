package com.agentx.app.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.agentx.app.ui.ide.data.WorkspacePicker

/**
 * Android implementation of [WorkspacePicker] backed by the system folder
 * picker (ACTION_OPEN_DOCUMENT_TREE).
 *
 * The rest of the app only ever receives an opaque handle string, so no UI or
 * domain code depends on Android storage types.
 */
@Composable
fun rememberAndroidWorkspacePicker(): WorkspacePicker {
    val pending = remember { mutableStateOf<((String?) -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val callback = pending.value
        pending.value = null
        callback?.invoke(uri?.toString())
    }

    return remember(launcher) {
        WorkspacePicker { onPicked ->
            pending.value = onPicked
            launcher.launch(null)
        }
    }
}
