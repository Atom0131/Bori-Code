package com.bori.app.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Content insets for a screen's Scaffold. Every screen sits inside the shell, whose bottom bar
 * (or, unfolded, its own padding) already clears the gesture bar, and whose top bars handle
 * the status bar; a Scaffold's default insets would leave a second gap at the bottom.
 */
val ShellContentInsets = WindowInsets(0)

/** Widest a page of text and controls gets; wider windows center it. */
val ReadableWidth: Dp = 840.dp

/**
 * The app's top bar: a title with an optional one-line subtitle, a back arrow when [onBack] is
 * set, and the screen's [actions]. Every screen uses it so they look and behave alike.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoriTopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    containerColor: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
    titleContent: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = {
            if (titleContent != null) {
                titleContent()
            } else {
                Column {
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors().let { base ->
            if (containerColor == Color.Unspecified && contentColor == Color.Unspecified) base
            else TopAppBarDefaults.topAppBarColors(
                containerColor = containerColor.takeOrElse { base.containerColor },
                titleContentColor = contentColor.takeOrElse { base.titleContentColor },
                navigationIconContentColor = contentColor.takeOrElse { base.navigationIconContentColor },
                actionIconContentColor = contentColor.takeOrElse { base.actionIconContentColor },
            )
        },
    )
}

/** Centers [content] at [maxWidth] or narrower, so pages don't stretch across an unfolded screen. */
@Composable
fun ReadableColumn(
    modifier: Modifier = Modifier,
    maxWidth: Dp = ReadableWidth,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(modifier = Modifier.widthIn(max = maxWidth).fillMaxWidth(), content = content)
    }
}

/** Caps a scrolling page at [max] wide and centers it; for lists that can't use [ReadableColumn]. */
fun Modifier.readableWidth(max: Dp = ReadableWidth): Modifier =
    fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = max).fillMaxWidth()
