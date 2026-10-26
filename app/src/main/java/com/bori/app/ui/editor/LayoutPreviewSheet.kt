package com.bori.app.ui.editor

import android.util.Xml
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.StringReader

private data class ViewNode(
    val tagName: String,
    val id: String?,
    val width: String,
    val height: String,
    val depth: Int,
)

private fun XmlPullParser.findAttr(localName: String): String? {
    for (i in 0 until attributeCount) {
        if (getAttributeName(i).substringAfterLast(':') == localName) return getAttributeValue(i)
    }
    return null
}

private fun parseLayout(xml: String): Result<List<ViewNode>> = runCatching {
    val nodes = mutableListOf<ViewNode>()
    val parser = Xml.newPullParser()
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
    parser.setInput(StringReader(xml))
    var depth = -1
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        when (event) {
            XmlPullParser.START_TAG -> {
                depth++
                val tag = parser.name.substringAfterLast('.')
                val id = parser.findAttr("id")
                    ?.removePrefix("@+id/")?.removePrefix("@id/")
                val w = parser.findAttr("layout_width")
                    ?.replace("match_parent", "match")?.replace("wrap_content", "wrap") ?: "?"
                val h = parser.findAttr("layout_height")
                    ?.replace("match_parent", "match")?.replace("wrap_content", "wrap") ?: "?"
                nodes += ViewNode(tag, id, w, h, depth)
            }
            XmlPullParser.END_TAG -> depth--
        }
        event = parser.next()
    }
    nodes
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayoutPreviewSheet(
    content: String,
    onDismiss: () -> Unit,
) {
    val result = remember(content) { parseLayout(content) }
    val nodes = result.getOrNull()
    val error = result.exceptionOrNull()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Layout Preview",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${nodes?.size ?: 0} view(s)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()

            when {
                error != null -> Text(
                    text = "Parse error: ${error.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
                nodes.isNullOrEmpty() -> Text(
                    text = "No views found",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
                else -> LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(nodes) { node -> ViewNodeRow(node) }
                }
            }
        }
    }
}

@Composable
private fun ViewNodeRow(node: ViewNode) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = (12 + node.depth * 16).dp,
                end = 12.dp,
                top = 3.dp,
                bottom = 3.dp,
            ),
        verticalAlignment = Alignment.Top,
    ) {
        if (node.depth > 0) {
            Text(
                text = "└ ",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column {
            Text(
                text = buildString {
                    append(node.tagName)
                    if (node.id != null) append("  @${node.id}")
                },
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "${node.width} × ${node.height}",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
