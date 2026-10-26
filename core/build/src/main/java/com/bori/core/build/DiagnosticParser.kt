package com.bori.core.build

import com.bori.core.build.model.Diagnostic

/**
 * Parses raw build log lines into structured [Diagnostic] objects.
 *
 * Handles two formats emitted by the build pipeline:
 *
 * ECJ (Java compiler):
 *   `/path/to/File.java:42: error: cannot find symbol`
 *
 * AAPT2 (resource compiler):
 *   `/path/to/res/layout/main.xml:5:3: error: attribute not found`
 *
 * Lines that don't match either pattern are silently skipped.
 */
object DiagnosticParser {

    // path:line:col: error/warning: message  (aapt2 with column)
    private val WITH_COL = Regex("""^(.+):(\d+):(\d+):\s+(error|warning):\s+(.+)$""")

    // path:line: error/warning: message  (ecj, no column)
    private val WITHOUT_COL = Regex("""^(.+):(\d+):\s+(error|warning):\s+(.+)$""")

    fun parse(logs: List<String>): List<Diagnostic> {
        val result = mutableListOf<Diagnostic>()
        for (raw in logs) {
            val d = parseWithCol(raw.trim()) ?: parseWithoutCol(raw.trim()) ?: continue
            result += d
        }
        return result
    }

    private fun parseWithCol(line: String): Diagnostic? {
        val m = WITH_COL.matchEntire(line) ?: return null
        val (path, lineNum, col, sev, msg) = m.destructured
        return Diagnostic(
            filePath = path,
            line = lineNum.toIntOrNull() ?: return null,
            column = col.toIntOrNull() ?: 0,
            severity = sev.toSeverity(),
            message = msg,
        )
    }

    private fun parseWithoutCol(line: String): Diagnostic? {
        val m = WITHOUT_COL.matchEntire(line) ?: return null
        val (path, lineNum, sev, msg) = m.destructured
        return Diagnostic(
            filePath = path,
            line = lineNum.toIntOrNull() ?: return null,
            column = 0,
            severity = sev.toSeverity(),
            message = msg,
        )
    }

    private fun String.toSeverity() =
        if (this == "error") Diagnostic.Severity.ERROR else Diagnostic.Severity.WARNING
}
