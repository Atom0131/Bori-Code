package com.bori.core.editor

/**
 * A diagnostic marker to be rendered as a squiggly underline inside [EditorEngine].
 *
 * This is the `:core:editor`-local representation. The `:app` layer maps the
 * build pipeline's `Diagnostic` model to this type before calling
 * [EditorController.applyAllDiagnostics].
 *
 * [line] and [column] are 1-based. [column] is 0 when the source didn't include column info.
 */
data class EditorDiagnostic(
    val line: Int,
    val column: Int,
    val severity: Severity,
    val message: String,
) {
    enum class Severity { ERROR, WARNING }
}
