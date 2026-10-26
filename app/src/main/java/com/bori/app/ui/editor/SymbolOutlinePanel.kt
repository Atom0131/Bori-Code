package com.bori.app.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.bori.core.editor.SymbolEntry

@Composable
fun SymbolOutlinePanel(
    symbols: List<SymbolEntry>,
    onSymbolClick: (SymbolEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (symbols.isEmpty()) return
    androidx.compose.foundation.layout.Column(modifier = modifier) {
        symbols.forEach { sym ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSymbolClick(sym) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = sym.kind.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(52.dp),
                )
                Text(
                    text = sym.name,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = ":${sym.line}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
