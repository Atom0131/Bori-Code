package com.bori.core.editor

/**
 * Represents a single open file tab in the editor.
 *
 * Each tab owns exactly one [EditorEngine] instance. Tabs are identified by a
 * stable [id] (UUID) so Compose can key views correctly when the active tab changes.
 *
 * [isDirty] is set to true by [EditorController] whenever the engine emits a
 * content change, and cleared when [EditorController.markDirty] is called with
 * `dirty = false` after a successful save.
 */
data class OpenTab(
    val id: String,
    val path: String,
    val name: String,
    val isDirty: Boolean = false,
    val engine: EditorEngine,
)
