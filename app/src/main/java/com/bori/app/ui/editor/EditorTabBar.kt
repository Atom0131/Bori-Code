package com.bori.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bori.core.editor.OpenTab

@Composable
fun EditorTabBar(
    tabs: List<OpenTab>,
    activeIndex: Int,
    onSelectTab: (Int) -> Unit,
    onCloseTab: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tabs.isEmpty()) return
    val accentColor = MaterialTheme.colorScheme.primary
    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .background(MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxHeight()
                    .horizontalScroll(rememberScrollState()),
            ) {
                tabs.forEachIndexed { index, tab ->
                    val isActive = index == activeIndex
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxHeight()
                            .background(
                                if (isActive) MaterialTheme.colorScheme.surface
                                else MaterialTheme.colorScheme.surfaceContainer,
                            )
                            .drawBehind {
                                if (isActive) {
                                    drawRect(
                                        color = accentColor,
                                        topLeft = Offset.Zero,
                                        size = Size(size.width, 2.dp.toPx()),
                                    )
                                }
                            }
                            .clickable { onSelectTab(index) }
                            .padding(start = 12.dp, end = 6.dp),
                    ) {
                        Text(
                            text = tab.name,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (isActive) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (tab.isDirty) {
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "●",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clickable { onCloseTab(index) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close ${tab.name}",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    VerticalDivider(modifier = Modifier.fillMaxHeight())
                }
            }
        }
        HorizontalDivider()
    }
}
