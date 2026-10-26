package com.bori.core.project

import java.io.File

object RepoMapper {

    private val SKIP_DIRS = setOf(
        "build", ".gradle", ".git", ".idea", ".kotlin",
        "captures", "generated", "__pycache__", "node_modules",
    )
    private val SKIP_EXTS = setOf("class", "dex", "jar", "apk", "so", "iml", "o")

    fun generate(rootPath: String, maxLines: Int = 60): String {
        val root = File(rootPath)
        if (!root.isDirectory) return ""
        val sb = StringBuilder()
        walk(root, sb, 0, Counter(maxLines))
        return sb.toString().trimEnd()
    }

    private data class Counter(var remaining: Int)

    private fun walk(dir: File, sb: StringBuilder, depth: Int, counter: Counter) {
        if (counter.remaining <= 0) return
        val children = dir.listFiles()
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?: return
        for (child in children) {
            if (counter.remaining <= 0) break
            if (child.name.startsWith(".")) continue
            if (child.isDirectory && child.name in SKIP_DIRS) continue
            if (!child.isDirectory && child.extension in SKIP_EXTS) continue
            val indent = "  ".repeat(depth)
            val label = if (child.isDirectory) "${child.name}/" else child.name
            sb.appendLine("$indent$label")
            counter.remaining--
            if (child.isDirectory) walk(child, sb, depth + 1, counter)
        }
    }
}
