package com.bori.core.editor

object CodeFormatter {

    /**
     * Applies safe, always-correct formatting to [content]:
     * 1. CRLF → LF
     * 2. Leading tabs → 4 spaces, trailing whitespace stripped per line
     * 3. Max 2 consecutive blank lines
     * 4. Import sorting (alphabetical, grouped by package tier) for Kotlin and Java
     * 5. Exactly one trailing newline
     */
    fun format(content: String, language: String): String {
        var result = content

        // 1. Normalize line endings
        result = result.replace("\r\n", "\n").replace("\r", "\n")

        // 2. Normalize leading whitespace + strip trailing whitespace per line
        result = result.lines().joinToString("\n") { line ->
            val withoutTrailing = line.trimEnd()
            val body = withoutTrailing.trimStart()
            val leadingLen = withoutTrailing.length - body.length
            val leading = withoutTrailing.substring(0, leadingLen).replace("\t", "    ")
            leading + body
        }

        // 3. Collapse 3+ consecutive blank lines to 2
        result = result.replace(Regex("\n{4,}"), "\n\n\n")

        // 4. Sort imports for Kotlin and Java
        if (language == "kotlin" || language == "java") {
            result = sortImports(result)
        }

        // 5. Exactly one trailing newline
        result = result.trimEnd('\n') + "\n"

        return result
    }

    private fun sortImports(content: String): String {
        val lines = content.split("\n")
        var firstImport = -1
        var lastImport = -1

        lines.forEachIndexed { i, line ->
            if (line.trim().startsWith("import ")) {
                if (firstImport == -1) firstImport = i
                lastImport = i
            }
        }

        if (firstImport == -1 || firstImport >= lastImport) return content

        val block = lines.subList(firstImport, lastImport + 1)
        val sorted = block
            .filter { it.trim().startsWith("import ") }
            .sortedWith(
                compareBy(
                    { importTier(it.trim()) },
                    { it.trim() },
                ),
            )

        val newLines = lines.toMutableList()
        var cursor = firstImport
        sorted.forEach { imp -> newLines[cursor++] = imp }
        while (cursor <= lastImport) newLines[cursor++] = ""

        return newLines.joinToString("\n")
    }

    private fun importTier(importLine: String): Int = when {
        importLine.startsWith("import java.") || importLine.startsWith("import javax.") -> 0
        importLine.startsWith("import android.") -> 1
        importLine.startsWith("import androidx.") -> 2
        importLine.startsWith("import kotlinx.") || importLine.startsWith("import kotlin.") -> 3
        else -> 4
    }
}
