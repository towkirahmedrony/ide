package com.agentx.app.ui.ide.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agentx.app.codeintel.CodeSymbol
import com.agentx.app.codeintel.SymbolKind
import com.agentx.app.ui.ide.state.EditorStructureUiState
import com.agentx.app.ui.theme.ForgeAmber
import com.agentx.app.ui.theme.ForgeInk
import com.agentx.app.ui.theme.ForgeMint
import com.agentx.app.ui.theme.ForgeMuted
import com.agentx.app.ui.theme.ForgeSurfaceVariant

/**
 * The editor's code structure panel: the outline of the open file.
 *
 * It is intentionally small — a header that says what the file contains, and a
 * bounded list of declarations that can be tapped to jump to their line. When a
 * file has no structure, the panel says *why* (unsupported language, no parser
 * in this build, analysis limit) instead of showing an empty list.
 */
@Composable
fun CodeStructurePanel(
    state: EditorStructureUiState,
    onSymbolClick: (CodeSymbol) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val symbols = remember(state.outline) { state.flattened() }
    val subtitle = structureSubtitle(state, symbols.size)

    Column(modifier = modifier.fillMaxWidth().background(ForgeSurfaceVariant)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = symbols.isNotEmpty()) { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.AccountTree,
                contentDescription = null,
                tint = if (state.hasOutline) ForgeMint else ForgeMuted,
                modifier = Modifier.width(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Structure",
                    style = MaterialTheme.typography.labelMedium,
                    color = ForgeInk,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.unavailableReason != null) ForgeAmber else ForgeMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (symbols.isNotEmpty()) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse structure" else "Expand structure",
                    tint = ForgeMuted,
                )
            }
        }

        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 168.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                symbols.forEach { (depth, symbol) ->
                    StructureRow(symbol = symbol, depth = depth, onClick = { onSymbolClick(symbol) })
                }
                if (state.symbolCount > symbols.size) {
                    Text(
                        text = "… ${state.symbolCount - symbols.size} more symbols",
                        style = MaterialTheme.typography.labelSmall,
                        color = ForgeMuted,
                        modifier = Modifier.padding(start = 34.dp, top = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StructureRow(symbol: CodeSymbol, depth: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 12.dp + (depth * 14).dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = symbol.kind.displayName,
            style = MaterialTheme.typography.labelSmall,
            color = kindColor(symbol.kind),
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(58.dp),
        )
        Text(
            text = if (symbol.kind.isCallable) "${symbol.name}()" else symbol.name,
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            color = ForgeInk,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "L${symbol.range.start.line}",
            style = MaterialTheme.typography.labelSmall,
            color = ForgeMuted,
        )
    }
}

private fun structureSubtitle(state: EditorStructureUiState, shown: Int): String {
    state.unavailableReason?.let { reason -> return reason }

    val outline = state.outline
    if (outline == null) {
        return if (state.analyzing) "Analysing…" else "No structure yet"
    }
    return buildString {
        append(outline.language.displayName).append(" · ")
        append(outline.symbolCount).append(if (outline.symbolCount == 1) " symbol" else " symbols")
        if (state.analyzing) append(" · analysing…")
        if (outline.hasSyntaxErrors) append(" · incomplete code")
        if (outline.truncated) append(" · truncated")
        if (shown < outline.symbolCount) append(" · showing $shown")
    }
}

private fun kindColor(kind: SymbolKind) = when (kind) {
    SymbolKind.CLASS, SymbolKind.INTERFACE, SymbolKind.OBJECT, SymbolKind.ENUM -> ForgeMint
    SymbolKind.FUNCTION, SymbolKind.METHOD, SymbolKind.CONSTRUCTOR -> ForgeAmber
    SymbolKind.IMPORT, SymbolKind.NAMESPACE -> ForgeMuted
    else -> ForgeMint.copy(alpha = 0.7f)
}
