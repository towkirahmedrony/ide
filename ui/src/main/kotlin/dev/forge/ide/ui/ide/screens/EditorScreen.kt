package dev.forge.ide.ui.ide.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.forge.ide.ui.ide.components.IdeEmptyState
import dev.forge.ide.ui.ide.components.IdeStatusPill
import dev.forge.ide.ui.ide.state.EditorUiState
import dev.forge.ide.ui.theme.ForgeAmber
import dev.forge.ide.ui.theme.ForgeCanvas
import dev.forge.ide.ui.theme.ForgeInk
import dev.forge.ide.ui.theme.ForgeMint
import dev.forge.ide.ui.theme.ForgeMuted
import dev.forge.ide.ui.theme.ForgeSurfaceVariant

private val CodeTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    lineHeight = 20.sp,
    color = ForgeInk,
)

private val GutterTextStyle = CodeTextStyle.copy(color = ForgeMuted, textAlign = TextAlign.End)

@Composable
fun EditorScreen(
    state: EditorUiState,
    onEdit: (String) -> Unit,
    onSave: () -> Unit,
    onBrowseFiles: () -> Unit,
    dismissStatus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val file = state.file
    if (file == null) {
        // A file that could not be opened (binary, media, unreadable) reports
        // why here instead of failing silently.
        val blocked = state.statusMessage
        Box(modifier = modifier.fillMaxSize().background(ForgeCanvas)) {
            IdeEmptyState(
                icon = if (blocked != null) Icons.Outlined.ErrorOutline else Icons.Filled.Description,
                title = if (blocked != null) "Unable to open file" else "No file open",
                message = blocked ?: "Pick a file from the explorer to start editing.",
                actionLabel = "Browse files",
                onAction = onBrowseFiles,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        return
    }

    Column(modifier = modifier.fillMaxSize().background(ForgeCanvas)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ForgeSurfaceVariant)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = ForgeInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = file.path,
                    style = MaterialTheme.typography.labelSmall,
                    color = ForgeMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (state.isDirty) {
                IdeStatusPill("unsaved", ForgeAmber)
                Spacer(Modifier.width(8.dp))
            }
            Button(onClick = onSave, enabled = state.isDirty && !state.saving) {
                Icon(Icons.Filled.Save, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(if (state.saving) "Saving" else "Save")
            }
        }

        state.statusMessage?.let { message ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ForgeMint.copy(alpha = 0.10f))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.labelSmall,
                    color = ForgeMint,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = dismissStatus) { Text("Dismiss") }
            }
        }

        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(top = 12.dp, bottom = 24.dp),
        ) {
            Column(modifier = Modifier.padding(start = 10.dp, end = 8.dp)) {
                for (line in 1..state.lineCount) {
                    Text(
                        text = line.toString(),
                        style = GutterTextStyle,
                        modifier = Modifier.width(40.dp),
                    )
                }
            }
            BasicTextField(
                value = state.draft,
                onValueChange = onEdit,
                textStyle = CodeTextStyle,
                cursorBrush = SolidColor(ForgeMint),
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
            )
        }

        if (state.isDirty || state.statusMessage != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ForgeSurfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = if (state.isDirty) "Modified · ${state.lineCount} lines" else "Saved",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.isDirty) ForgeAmber else ForgeMint,
                )
            }
        }
    }
}
