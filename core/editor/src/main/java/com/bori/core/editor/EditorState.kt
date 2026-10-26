package com.bori.core.editor

/**
 * Immutable snapshot of the editor's current state.
 *
 * Observed by the AI module to build inline suggestion context and by the
 * chat feature to inject the current file into AI requests.
 */
data class EditorState(
    /** Absolute path to the open file, or null if no file is open. */
    val filePath: String? = null,

    /** Full document text. */
    val content: String = "",

    /** Cursor offset (character index) within [content]. */
    val cursorOffset: Int = 0,

    /** Programming language identifier (e.g. "kotlin", "java"). */
    val language: String = "",

    /** The currently selected text, or empty string if nothing is selected. */
    val selectedText: String = "",
) {
    /** Text from the start of the document up to [cursorOffset] (the FIM prefix). */
    val prefix: String get() = content.take(cursorOffset)

    /** Text from [cursorOffset] to the end of the document (the FIM suffix). */
    val suffix: String get() = content.drop(cursorOffset)
}
