package com.bori.core.editor

import android.content.Context
import android.view.View
import androidx.core.content.res.ResourcesCompat
import com.bori.core.editor.R
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Wrapper around Sora [CodeEditor] that exposes reactive [Flow] streams for
 * document changes and cursor movement.
 */
class EditorEngine(context: Context) {

    private val completionProvider = SoraCompletionProvider()

    private val editor: CodeEditor = CodeEditor(context).apply {
        setEditorLanguage(completionProvider)
        // Match the Obsidian Flux design system — JetBrains Mono for code.
        ResourcesCompat.getFont(context, R.font.jetbrains_mono_regular)?.let { jbMono ->
            typefaceText = jbMono
            typefaceLineNumber = jbMono
        }
    }

    /** Exposed as [View] so callers outside `:core:editor` don't need Sora on their classpath. */
    val view: View get() = editor

    private var dark: Boolean? = null

    /** Dark or light colors to match the app's theme; [background] is the app's surface. */
    fun setTheme(dark: Boolean, background: Int, gutter: Int) {
        if (this.dark == dark && editor.colorScheme.getColor(EditorColorScheme.WHOLE_BACKGROUND) == background) return
        this.dark = dark
        val scheme = if (dark) SchemeDarcula() else EditorColorScheme()
        scheme.setColor(EditorColorScheme.WHOLE_BACKGROUND, background)
        scheme.setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, gutter)
        editor.colorScheme = scheme
    }

    private var density = context.resources.displayMetrics.density

    /**
     * The two screens of a foldable can differ in density and the app isn't restarted when you
     * fold, so the text is rescaled here to stay the same size on both.
     */
    fun setDensity(density: Float) {
        if (density == this.density) return
        editor.setTextSizePx(editor.textSizePx * density / this.density)
        this.density = density
    }

    /** Long lines wrap instead of running off the edge; on for narrow screens. */
    fun setWordWrap(wrap: Boolean) {
        if (editor.isWordwrap != wrap) editor.isWordwrap = wrap
    }

    /** Called by EditorViewModel after extracting symbols from the current file. */
    fun updateSymbols(symbols: List<SymbolEntry>) {
        completionProvider.symbols = symbols
    }

    /** Called by [EditorScreen] when [InlineSuggestionEngine] emits a new suggestion. */
    fun setSuggestion(text: String) {
        completionProvider.pendingSuggestion = text
        editor.post {
            editor.getComponent(EditorAutoCompletion::class.java).requireCompletion()
        }
    }

    private var filePath: String? = null
    private var language: String = "java"

    /** Current editor snapshot. Call from any thread. */
    val currentState: EditorState
        get() {
            val content = editor.text.toString()
            val cursor = editor.cursor
            val selectedText = if (cursor.isSelected) {
                try {
                    val start = lineColToOffset(cursor.leftLine, cursor.leftColumn)
                        .coerceIn(0, content.length)
                    val end = lineColToOffset(cursor.rightLine, cursor.rightColumn)
                        .coerceIn(0, content.length)
                    if (start < end) content.substring(start, end) else ""
                } catch (_: Exception) { "" }
            } else ""
            return EditorState(
                filePath = filePath,
                content = content,
                cursorOffset = cursor.left,
                language = language,
                selectedText = selectedText,
            )
        }

    /**
     * Hot flow of [EditorState] updates.
     *
     * Emits whenever the document content or cursor position changes.
     */
    val stateFlow: Flow<EditorState> = callbackFlow {
        val contentSubscription = editor.subscribeEvent(ContentChangeEvent::class.java) { _, _ ->
            trySend(currentState)
        }
        val selectionSubscription = editor.subscribeEvent(SelectionChangeEvent::class.java) { _, _ ->
            trySend(currentState)
        }
        awaitClose {
            contentSubscription.unsubscribe()
            selectionSubscription.unsubscribe()
        }
    }

    /** Load file content into the editor. */
    fun openFile(path: String, content: String, lang: String = detectLanguage(path)) {
        filePath = path
        language = lang
        editor.setText(content)
    }

    /** Insert [text] at the current cursor position. */
    fun insertAtCursor(text: String) {
        editor.insertText(text, editor.cursor.left)
    }

    /** Replace the full document content (e.g. after agent applies a diff). */
    fun setContent(content: String) {
        editor.setText(content)
    }

    /** Undo the last edit action. */
    fun undo() {
        editor.undo()
    }

    /** Redo the previously undone edit action. */
    fun redo() {
        editor.redo()
    }

    /**
     * Move the cursor by [delta] characters (positive = right, negative = left).
     * The resulting offset is clamped to valid document bounds.
     */
    fun moveCursor(delta: Int) {
        editor.post {
            val current = editor.cursor.left
            val newOffset = (current + delta).coerceIn(0, editor.text.length)
            editor.setSelection(newOffset, newOffset)
        }
    }

    /** Put the cursor at character [offset], clamped to the document. */
    fun setCursor(offset: Int) {
        editor.post {
            val pos = editor.text.indexer.getCharPosition(offset.coerceIn(0, editor.text.length))
            editor.setSelection(pos.line, pos.column)
        }
    }

    /**
     * Jump the cursor to the given 1-based [line] and [column].
     * Safe to call from any thread — posts to the editor's view thread.
     */
    fun jumpToLine(line: Int, column: Int = 0) {
        editor.post {
            val lineIdx = (line - 1).coerceIn(0, editor.text.lineCount - 1)
            val colIdx = column.coerceIn(0, editor.text.getColumnCount(lineIdx))
            editor.setSelection(lineIdx, colIdx)
        }
    }

    /**
     * Pushes [diagnostics] into Sora's diagnostics container, replacing any previously set
     * diagnostics. Each item is rendered as a squiggly underline at the corresponding line.
     * Safe to call from any thread.
     */
    fun applyDiagnostics(diagnostics: List<EditorDiagnostic>) {
        editor.post {
            editor.diagnostics?.reset()
            diagnostics.forEach { d ->
                try {
                    val lineIdx = (d.line - 1).coerceIn(0, editor.text.lineCount - 1)
                    val lineLen = editor.text.getColumnCount(lineIdx)
                    val lineStart = lineColToOffset(lineIdx, 0)
                    val colOffset = if (d.column > 0) (d.column - 1).coerceAtMost(lineLen) else 0
                    val start = lineStart + colOffset
                    val end = (lineStart + lineLen).coerceAtMost(editor.text.length)
                    if (start > end) return@forEach
                    val sev: Short = if (d.severity == EditorDiagnostic.Severity.ERROR)
                        DiagnosticRegion.SEVERITY_ERROR
                    else
                        DiagnosticRegion.SEVERITY_WARNING
                    val region = DiagnosticRegion(start, end, sev)
                    editor.diagnostics?.addDiagnostic(region)
                } catch (_: Exception) { /* skip malformed */ }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Converts a 0-based [line] and [col] into a character offset within the document.
     * Each line is followed by one newline character.
     */
    private fun lineColToOffset(line: Int, col: Int): Int {
        var offset = 0
        for (i in 0 until line) {
            offset += editor.text.getColumnCount(i) + 1
        }
        return offset + col
    }

    companion object {
        fun detectLanguage(filePath: String): String = when {
            filePath.endsWith(".kt") || filePath.endsWith(".kts") -> "kotlin"
            filePath.endsWith(".java") -> "java"
            filePath.endsWith(".py") -> "python"
            filePath.endsWith(".js") || filePath.endsWith(".ts") -> "javascript"
            filePath.endsWith(".xml") -> "xml"
            filePath.endsWith(".json") -> "json"
            filePath.endsWith(".md") -> "markdown"
            else -> "text"
        }
    }
}
