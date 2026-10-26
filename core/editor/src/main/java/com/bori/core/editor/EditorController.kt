package com.bori.core.editor

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton bridge between the View-based [EditorEngine] instances and the rest of the app.
 *
 * Each open file is represented by an [OpenTab] that owns its own [EditorEngine]. The full
 * tab bar state is exposed via [tabState]; the currently active editor snapshot is mirrored
 * into [currentState] so [ChatViewModel] can inject file context into AI requests without
 * needing to know about tabs at all.
 *
 * Usage:
 * - [EditorScreen] collects [tabState] to render the [ScrollableTabRow] and active editor view.
 * - [EditorViewModel.openFile] calls [openFile] when the user taps a file in the tree.
 * - [ChatViewModel] reads [currentState] to inject the active file into AI context.
 */
@Singleton
class EditorController @Inject constructor() {

    private val _tabState = MutableStateFlow(TabState())

    /** Full tab bar state — observed by [EditorScreen]. */
    val tabState: StateFlow<TabState> = _tabState.asStateFlow()

    private val _currentState = MutableStateFlow<EditorState?>(null)

    /** Each tab's text as last opened or saved, so the dirty dot means real unsaved changes. */
    private val savedText = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Most recent snapshot of the active editor's content and cursor position.
     * Updated by [EditorScreen]'s `LaunchedEffect` whenever the active tab's engine emits.
     * Read by [ChatViewModel] to inject file context into AI requests.
     */
    val currentState: StateFlow<EditorState?> = _currentState.asStateFlow()

    // ── Tab operations ────────────────────────────────────────────────────────

    /**
     * Open [path] in a new tab. If [path] is already open, switch to that tab instead.
     * A new [EditorEngine] is created here and lives inside the [OpenTab] for its lifetime.
     */
    fun openFile(context: Context, path: String, content: String) {
        val existing = _tabState.value.tabs.indexOfFirst { it.path == path }
        if (existing >= 0) {
            _tabState.value = _tabState.value.copy(activeIndex = existing)
            return
        }
        val engine = EditorEngine(context).also { it.openFile(path, content) }
        val tab = OpenTab(
            id = UUID.randomUUID().toString(),
            path = path,
            name = File(path).name,
            engine = engine,
        )
        savedText[tab.id] = content
        val tabs = _tabState.value.tabs + tab
        _tabState.value = TabState(tabs = tabs, activeIndex = tabs.lastIndex)
    }

    /** Switch the active tab to [index]. */
    fun selectTab(index: Int) {
        _tabState.value = _tabState.value.copy(activeIndex = index)
    }

    /**
     * Close the tab at [index]. The adjacent tab becomes active.
     * When the last tab is closed, [TabState.activeTab] returns null and [EditorScreen]
     * shows an empty-state placeholder.
     */
    fun closeTab(index: Int) {
        _tabState.value.tabs.getOrNull(index)?.let { savedText.remove(it.id) }
        val tabs = _tabState.value.tabs.toMutableList().also { it.removeAt(index) }
        val newIndex = (index - 1).coerceAtLeast(0)
        _tabState.value = TabState(
            tabs = tabs,
            activeIndex = if (tabs.isEmpty()) 0 else newIndex,
        )
    }

    /**
     * Set the dirty flag for the tab identified by [tabId].
     * Pass `dirty = false` after a successful save to clear the `●` indicator.
     */
    fun markDirty(tabId: String, dirty: Boolean) {
        if (!dirty) {
            _tabState.value.tabs.firstOrNull { it.id == tabId }?.let { savedText[tabId] = it.engine.currentState.content }
        }
        setDirty(tabId, dirty)
    }

    /** Called when a tab's text or cursor changes: dirty only if the text differs from the saved copy. */
    fun onContentChanged(tabId: String, content: String) {
        val dirty = savedText[tabId]?.let { it != content } ?: true
        if (_tabState.value.tabs.any { it.id == tabId && it.isDirty != dirty }) setDirty(tabId, dirty)
    }

    private fun setDirty(tabId: String, dirty: Boolean) {
        _tabState.value = _tabState.value.copy(
            tabs = _tabState.value.tabs.map {
                if (it.id == tabId) it.copy(isDirty = dirty) else it
            },
        )
    }

    /**
     * Reloads open tabs whose file changed on disk (edited by a coding CLI). Tabs with unsaved
     * changes are left alone, so the user's own edits are never overwritten.
     */
    suspend fun reloadChangedFiles() {
        for (tab in _tabState.value.tabs) {
            if (tab.isDirty) continue
            val disk = withContext(Dispatchers.IO) { File(tab.path).takeIf { it.isFile }?.readText() } ?: continue
            if (disk != tab.engine.currentState?.content) {
                withContext(Dispatchers.Main) { tab.engine.setContent(disk) }
                markDirty(tab.id, dirty = false)
            }
        }
    }

    // ── Chat context bridge ───────────────────────────────────────────────────

    /**
     * Called by [EditorScreen]'s `LaunchedEffect` whenever the active tab's engine emits a
     * new [EditorState]. Keeps [currentState] current for [ChatViewModel].
     */
    fun update(state: EditorState) {
        _currentState.value = state
    }

    // ── Diagnostics ──────────────────────────────────────────────────────────

    /**
     * Pushes diagnostics into every open tab that matches a path in [byPath].
     * Tabs not present in [byPath] have their diagnostics cleared.
     * Called by [EditorViewModel] whenever [DiagnosticsStateHolder] emits a new set.
     */
    fun applyAllDiagnostics(byPath: Map<String, List<EditorDiagnostic>>) {
        _tabState.value.tabs.forEach { tab ->
            tab.engine.applyDiagnostics(byPath[tab.path] ?: emptyList())
        }
    }

    /**
     * Moves the cursor in the tab whose [OpenTab.path] matches [path] to the given
     * 1-based [line] and [column]. No-op if the file is not currently open.
     */
    fun jumpToLine(path: String, line: Int, column: Int = 0) {
        _tabState.value.tabs.find { it.path == path }?.engine?.jumpToLine(line, column)
    }

    // ── Import quick-fix ─────────────────────────────────────────────────────

    /**
     * Inserts `import [fqn]` into [path] after the last existing import statement
     * (or the package declaration if no imports exist yet). No-op if already imported.
     */
    suspend fun insertImport(path: String, fqn: String): Result<String> {
        val importLine = "import $fqn"
        val tab = _tabState.value.tabs.find { it.path == path }
        val current: String = tab?.engine?.currentState?.content
            ?: run {
                val f = File(path)
                if (!f.exists()) return Result.failure(IllegalStateException("File not found: $path"))
                withContext(Dispatchers.IO) { f.readText() }
            }
        if (current.contains(importLine)) return Result.success("Already imported")
        val lines = current.lines()
        val lastImportIdx = lines.indexOfLast { it.trimStart().startsWith("import ") }
        val packageIdx    = lines.indexOfFirst { it.trimStart().startsWith("package ") }
        val insertAfterIdx = when {
            lastImportIdx >= 0 -> lastImportIdx
            packageIdx >= 0    -> packageIdx
            else               -> -1
        }
        val updated = if (insertAfterIdx >= 0) {
            val before = lines.take(insertAfterIdx + 1).joinToString("\n")
            val after  = lines.drop(insertAfterIdx + 1).joinToString("\n")
            "$before\n$importLine\n$after"
        } else {
            "$importLine\n$current"
        }
        withContext(Dispatchers.IO) { File(path).writeText(updated) }
        if (tab != null) {
            withContext(Dispatchers.Main) { tab.engine.setContent(updated) }
            markDirty(tab.id, dirty = false)
        }
        return Result.success("Imported $fqn")
    }

    // ── AI agent edit bridge ──────────────────────────────────────────────────

    /**
     * Applies a find-and-replace edit to the file at [path].
     *
     * - If the file is open in a tab: reads live content from the engine, applies the
     *   replacement, writes to disk, then updates the EditorEngine on the main thread.
     * - If not open: reads from disk, applies the replacement, writes back to disk.
     *
     * Returns [Result.success] with a summary string, or [Result.failure] if the file is not
     * found or [oldText] does not appear in the file content.
     */
    suspend fun applyFileEdit(path: String, oldText: String, newText: String): Result<String> {
        val tab = _tabState.value.tabs.find { it.path == path }
        val current: String = tab?.engine?.currentState?.content
            ?: run {
                val f = File(path)
                if (!f.exists()) return Result.failure(IllegalStateException("File not found: $path"))
                withContext(Dispatchers.IO) { f.readText() }
            }

        if (oldText.isEmpty()) {
            withContext(Dispatchers.IO) { File(path).writeText(newText) }
            if (tab != null) {
                withContext(Dispatchers.Main) { tab.engine.setContent(newText) }
                markDirty(tab.id, dirty = false)
            }
            return Result.success("Overwrote $path (${newText.length} chars)")
        }

        if (!current.contains(oldText))
            return Result.failure(IllegalStateException("old_text not found in $path"))

        val updated = current.replaceFirst(oldText, newText)

        withContext(Dispatchers.IO) { File(path).writeText(updated) }

        if (tab != null) {
            withContext(Dispatchers.Main) { tab.engine.setContent(updated) }
            markDirty(tab.id, dirty = false)
        }

        val delta = newText.length - oldText.length
        return Result.success(
            "Edit applied to $path (Δ${if (delta >= 0) "+$delta" else "$delta"} chars)"
        )
    }
}
