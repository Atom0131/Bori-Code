package com.bori.core.editor

object KotlinSymbolExtractor {

    private val patterns = listOf(
        Regex("""^\s*(?:(?:data|sealed|abstract|open|inner|enum)\s+)*class\s+(\w+)""") to SymbolKind.CLASS,
        Regex("""^\s*(?:companion\s+)?object\s+(\w+)""") to SymbolKind.OBJECT,
        Regex("""^\s*(?:sealed\s+|fun\s+)?interface\s+(\w+)""") to SymbolKind.INTERFACE,
        Regex("""^\s*(?:(?:suspend|private|protected|internal|override|open|abstract|inline|operator)\s+)*fun\s+(\w+)""") to SymbolKind.FUNCTION,
        Regex("""^\s*(?:(?:private|protected|internal|override|lateinit|const)\s+)*(?:val|var)\s+(\w+)""") to SymbolKind.PROPERTY,
    )

    fun extract(content: String, filePath: String): List<SymbolEntry> {
        val results = mutableListOf<SymbolEntry>()
        content.lines().forEachIndexed { idx, line ->
            for ((regex, kind) in patterns) {
                val match = regex.find(line) ?: continue
                val name = match.groupValues[1]
                if (name.isNotEmpty()) {
                    results += SymbolEntry(name, kind, filePath, idx + 1)
                }
                break
            }
        }
        return results
    }

    fun getWordPrefix(content: CharSequence, endOffset: Int): String {
        var start = endOffset.coerceAtMost(content.length)
        while (start > 0 && content[start - 1].let { it.isLetterOrDigit() || it == '_' }) start--
        return content.substring(start, endOffset.coerceAtMost(content.length))
    }
}
