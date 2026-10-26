package com.bori.core.editor

import android.os.Bundle
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.completion.CompletionCancelledException
import io.github.rosemoe.sora.lang.completion.CompletionItemKind
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.lang.completion.SimpleCompletionItem
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.ContentReference

class SoraCompletionProvider : EmptyLanguage() {

    /** Set by EditorScreen when InlineSuggestionEngine emits a new suggestion. */
    var pendingSuggestion: String? = null

    /** Updated by EditorViewModel whenever the file content changes. */
    var symbols: List<SymbolEntry> = emptyList()

    @Throws(CompletionCancelledException::class)
    override fun requireAutoComplete(
        content: ContentReference,
        position: CharPosition,
        publisher: CompletionPublisher,
        extraArguments: Bundle,
    ) {
        // AI suggestion — shown first
        pendingSuggestion?.takeIf { it.isNotBlank() }?.let { suggestion ->
            val label = suggestion.lines().firstOrNull()?.trim() ?: ""
            if (label.isNotEmpty()) {
                publisher.addItem(
                    SimpleCompletionItem(label, "AI", label.length, suggestion)
                        .kind(CompletionItemKind.Snippet)
                )
            }
        }

        // Local symbol completions — filtered by current word prefix
        val charSeq = content.reference
        val prefix = KotlinSymbolExtractor.getWordPrefix(charSeq, position.index)
        if (prefix.length >= 2) {
            symbols
                .filter { it.name.startsWith(prefix, ignoreCase = true) && it.name != prefix }
                .take(10)
                .forEach { sym ->
                    publisher.addItem(
                        SimpleCompletionItem(sym.name, sym.kind.label, prefix.length, sym.name)
                            .kind(sym.kind.completionKind)
                    )
                }

            // Classpath class names (AndroidX + user libs) — shown after local symbols
            ClasspathIndex.filterByPrefix(prefix).forEach { name ->
                publisher.addItem(
                    SimpleCompletionItem(name, "class", prefix.length, name)
                        .kind(CompletionItemKind.Class)
                )
            }
        }

        publisher.updateList()
    }
}
