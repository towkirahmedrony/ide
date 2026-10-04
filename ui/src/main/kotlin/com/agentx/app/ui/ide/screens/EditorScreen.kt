package com.agentx.app.ui.ide.screens

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentx.app.codeintel.CodeSymbol
import com.agentx.app.ui.ide.components.CodeStructurePanel
import com.agentx.app.ui.ide.components.IdeEmptyState
import com.agentx.app.ui.ide.components.IdeStatusPill
import com.agentx.app.ui.ide.state.EditorStructureUiState
import com.agentx.app.ui.ide.state.EditorUiState
import com.agentx.app.ui.theme.ForgeAmber
import com.agentx.app.ui.theme.ForgeCanvas
import com.agentx.app.ui.theme.ForgeInk
import com.agentx.app.ui.theme.ForgeMint
import com.agentx.app.ui.theme.ForgeMuted
import com.agentx.app.ui.theme.ForgeSurfaceVariant
import kotlinx.coroutines.launch

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
    /** Structure of the open file; empty when no code intelligence is wired. */
    structure: EditorStructureUiState = EditorStructureUiState(),
    /** Reports the caret to the state holder, 1-based like the gutter. */
    onCursorMoved: (Int, Int) -> Unit = { _, _ -> },
    /** Discards the draft and shows what the file holds on disk right now. */
    onReloadFromDisk: () -> Unit = {},
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

        // The file changed on disk while the editor held it. When the draft is dirty it is kept
        // untouched — the user decides — and this strip is the notice that something happened.
        if (state.externallyModified) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ForgeAmber.copy(alpha = 0.16f))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (state.isDirty) {
                        "This file changed on disk. Your unsaved edits were kept."
                    } else {
                        "This file changed on disk since it was opened."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = ForgeAmber,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onReloadFromDisk) {
                    Text(if (state.isDirty) "Discard & reload" else "Reload")
                }
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

        // The caret is tracked here so the panel can name the symbol it sits in,
        // and a tapped symbol can scroll the editor to its line.
        val scrollState = rememberScrollState()
        val density = LocalDensity.current
        val lineHeightPx = with(density) { CodeTextStyle.lineHeight.toPx() }
        val scope = rememberCoroutineScope()
        var fieldValue by remember(file.path) {
            mutableStateOf(TextFieldValue(state.draft, TextRange(state.draft.length)))
        }
        LaunchedEffect(state.draft) {
            // The state holder stays the source of truth: an edit from outside the
            // field (opening another file) replaces the text here.
            if (fieldValue.text != state.draft) {
                fieldValue = TextFieldValue(state.draft, TextRange(state.draft.length))
            }
        }
        val jumpToLine: (Int) -> Unit = { line ->
            scope.launch {
                scrollState.animateScrollTo((((line - 1).coerceAtLeast(0)) * lineHeightPx).toInt())
            }
        }

        CodeStructurePanel(
            state = structure,
            onSymbolClick = { symbol: CodeSymbol -> jumpToLine(symbol.range.start.line) },
        )

        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState)
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
                value = fieldValue,
                onValueChange = { value ->
                    fieldValue = value
                    onEdit(value.text)
                    val caret = caretPosition(value.text, value.selection.start)
                    onCursorMoved(caret.first, caret.second)
                },
                textStyle = CodeTextStyle,
                cursorBrush = SolidColor(ForgeMint),
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
            )
        }

        if (state.isDirty || state.statusMessage != null || structure.cursorSymbol != null) {
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
                structure.cursorSymbol?.let { symbol ->
                    Text(
                        text = "· ${symbol.label} · L${symbol.range.start.line}",
                        style = MaterialTheme.typography.labelSmall,
                        color = ForgeMint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** 1-based line and column of [offset] in [text], as the editor displays them. */
private fun caretPosition(text: String, offset: Int): Pair<Int, Int> {
    val clamped = offset.coerceIn(0, text.length)
    var line = 1
    var lineStart = 0
    for (index in 0 until clamped) {
        if (text[index] == '\n') {
            line++
            lineStart = index + 1
        }
    }
    return line to (clamped - lineStart + 1)
}
