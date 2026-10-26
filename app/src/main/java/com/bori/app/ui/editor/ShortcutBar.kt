package com.bori.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A horizontal row of shortcut buttons shown above the keyboard in the editor.
 *
 * Provides quick access to common characters, cursor movement, undo, and redo
 * without reaching for the keyboard's symbol pages.
 */
@Composable
fun ShortcutBar(
    onInsert: (String) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onMoveCursor: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .height(48.dp)
            .horizontalScroll(rememberScrollState()),
    ) {
        // Character insertion buttons
        listOf("{", "}", "(", ")", "[", "]", ";", ":", "\"", "'", "<", ">", "=", "+", "-", "/", "*", "!", "&", "|")
            .forEach { char ->
                ShortcutTextButton(label = char) { onInsert(char) }
            }

        VerticalDivider(modifier = Modifier.padding(vertical = 6.dp))

        // Tab insertion
        ShortcutTextButton(label = "Tab") { onInsert("    ") }

        VerticalDivider(modifier = Modifier.padding(vertical = 6.dp))

        // Cursor movement
        IconButton(onClick = { onMoveCursor(-1) }, modifier = Modifier.height(48.dp)) {
            Text(text = "←", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = { onMoveCursor(1) }, modifier = Modifier.height(48.dp)) {
            Text(text = "→", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        VerticalDivider(modifier = Modifier.padding(vertical = 6.dp))

        // Undo / redo
        IconButton(onClick = onUndo, modifier = Modifier.height(48.dp)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Undo,
                contentDescription = "Undo",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRedo, modifier = Modifier.height(48.dp)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Redo,
                contentDescription = "Redo",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ShortcutTextButton(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.height(48.dp),
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
