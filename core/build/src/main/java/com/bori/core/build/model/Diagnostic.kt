package com.bori.core.build.model

/**
 * A single compiler or tool diagnostic (error or warning) extracted from build output.
 *
 * [line] and [column] are 1-based. [column] is 0 when the source didn't include column info.
 */
data class Diagnostic(
    val filePath: String,
    val line: Int,
    val column: Int,
    val severity: Severity,
    val message: String,
) {
    enum class Severity { ERROR, WARNING }
}
