package com.bori.app.terminal

/** How the Terminal screen divides its space between sessions. */
enum class Split(val panes: Int, val label: String) {
    SINGLE(1, "One"),
    COLUMNS(2, "Side by side"),
    ROWS(2, "Stacked"),
    GRID(4, "Grid of four"),
}

/**
 * Which session each pane shows ([slots], tab ids; null = empty pane) and which pane has the
 * keyboard. A session is shown in at most one pane: its view sets the PTY's size, so two views
 * of different sizes would keep resizing it.
 */
data class PaneLayout(
    val split: Split = Split.SINGLE,
    val slots: List<Int?> = listOf(null),
    val focused: Int = 0,
) {
    val focusedTabId: Int? get() = slots.getOrNull(focused)
}
