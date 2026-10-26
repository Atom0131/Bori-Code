package com.bori.core.editor

/**
 * Immutable snapshot of the editor's tab bar state.
 *
 * Held in [EditorController]'s `StateFlow<TabState>` and observed by
 * [EditorScreen] to render the [ScrollableTabRow] and the active editor view.
 */
data class TabState(
    val tabs: List<OpenTab> = emptyList(),
    val activeIndex: Int = 0,
) {
    /** Always in-bounds index for use in ScrollableTabRow. */
    val safeIndex: Int get() = activeIndex.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))

    /** The currently visible tab, or null when no files are open. */
    val activeTab: OpenTab? get() = tabs.getOrNull(activeIndex)
}
