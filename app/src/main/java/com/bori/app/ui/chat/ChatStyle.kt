package com.bori.app.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.bori.theme.components.crystalCardSurface
import com.bori.theme.components.crystalTileSurface

// Chat's surfaces, built only from the Bori theme engine's glass tiers and Material roles, so Chat
// looks like the habit tracker in every style (Material 3, Expressive, Crystal, Borikén). No hex
// colours here or in ChatScreen: a colour that means something comes from a role below.

/** How big a Chat panel is, which decides its glass tier (and so its corner radius). */
enum class PanelTier {
    /** Messages and full cards: the habit tracker's content-card tier (`shapes.large`). */
    CARD,

    /** Tool results, notices and small inner panels: the tile tier (`shapes.medium`). */
    TILE,
}

/**
 * A glass panel (a flat tonal one under Material 3 and Expressive). Replaces a `Surface` with a
 * hand-picked colour: [fill] is a Material role, and the content reads [contentColor].
 */
@Composable
fun ChatPanel(
    modifier: Modifier = Modifier,
    fill: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    tier: PanelTier = PanelTier.TILE,
    content: @Composable () -> Unit,
) {
    val surface = when (tier) {
        PanelTier.CARD -> Modifier.crystalCardSurface(fill)
        PanelTier.TILE -> Modifier.crystalTileSurface(fill)
    }
    Box(modifier.then(surface)) {
        CompositionLocalProvider(LocalContentColor provides contentColor, content = content)
    }
}

/** What a notice in Chat is about; each maps to one Material container role. */
enum class NoticeTone {
    /** Plan mode and plan review. */
    PLAN,

    /** Ask mode and its questions. */
    ASK,

    /** Something to notice before going on: no project open, a compacted conversation. */
    WARNING,

    /** A neutral pointer, like the tutorial card. */
    INFO,
}

/** The fill, border and accent of a notice. The border is only drawn by the flat styles' tonal edge. */
data class NoticeColors(val fill: Color, val border: Color, val tint: Color, val text: Color)

@Composable
fun noticeColors(tone: NoticeTone): NoticeColors {
    val s = MaterialTheme.colorScheme
    return when (tone) {
        NoticeTone.PLAN -> NoticeColors(s.primaryContainer, s.primary.copy(alpha = 0.35f), s.primary, s.onPrimaryContainer)
        NoticeTone.ASK -> NoticeColors(s.tertiaryContainer, s.tertiary.copy(alpha = 0.35f), s.tertiary, s.onTertiaryContainer)
        NoticeTone.WARNING -> NoticeColors(s.secondaryContainer, s.secondary.copy(alpha = 0.35f), s.secondary, s.onSecondaryContainer)
        NoticeTone.INFO -> NoticeColors(s.surfaceContainerHigh, s.outlineVariant, s.primary, s.onSurface)
    }
}
