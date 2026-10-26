package com.bori.core.editor

import io.github.rosemoe.sora.lang.completion.CompletionItemKind

enum class SymbolKind(val label: String, val completionKind: CompletionItemKind) {
    CLASS("class", CompletionItemKind.Class),
    OBJECT("object", CompletionItemKind.Module),
    INTERFACE("interface", CompletionItemKind.Interface),
    FUNCTION("fun", CompletionItemKind.Function),
    PROPERTY("val/var", CompletionItemKind.Field),
}

data class SymbolEntry(
    val name: String,
    val kind: SymbolKind,
    val filePath: String,
    val line: Int,
)
