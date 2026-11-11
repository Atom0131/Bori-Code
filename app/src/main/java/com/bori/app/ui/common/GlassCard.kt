package com.bori.app.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import com.bori.theme.components.crystalCardSurface

/**
 * The app's card: the Bori Habit Tracker's content-card tier from the theme engine, glass under
 * Crystal and a flat tonal card under Material 3 / Expressive. Use it instead of Material's `Card`.
 * [fill] is a role (a state colour such as `errorContainer` is fine); shape and shadow come from the
 * theme, never from the caller.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    fill: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentColor: Color = contentColorFor(fill).takeOrElse { MaterialTheme.colorScheme.onSurface },
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier.crystalCardSurface(fill)) {
        Column(
            if (onClick != null) Modifier.clip(MaterialTheme.shapes.large).clickable(onClick = onClick) else Modifier,
        ) {
            CompositionLocalProvider(LocalContentColor provides contentColor) { content() }
        }
    }
}
