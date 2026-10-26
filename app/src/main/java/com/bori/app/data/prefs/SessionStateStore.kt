package com.bori.app.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** The open editor files, so they come back after Android ends the app in the background. */
data class EditorSnapshot(val paths: List<String>, val activeIndex: Int, val cursor: Int)

/**
 * Where the user was working: open files, the terminal split and editor options. Kept apart from
 * [UserPreferences] because it changes all the time and isn't a setting.
 */
@Singleton
class SessionStateStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private object Keys {
        val EDITOR_TABS = stringPreferencesKey("session_editor_tabs")
        val EDITOR_ACTIVE = intPreferencesKey("session_editor_active")
        val EDITOR_CURSOR = intPreferencesKey("session_editor_cursor")
        val TERMINAL_SPLIT = stringPreferencesKey("session_terminal_split")
        val ACTIVE_PROJECT = stringPreferencesKey("session_active_project")
        // Unset means automatic: wrap on narrow screens.
        val WORD_WRAP = booleanPreferencesKey("editor_word_wrap")
        val FOLD_TIP_SEEN = booleanPreferencesKey("fold_tip_seen")
        val TERMINAL_SYSTEM_KEYBOARD = booleanPreferencesKey("terminal_system_keyboard")
    }

    suspend fun editor(): EditorSnapshot? {
        val prefs = dataStore.data.first()
        val paths = prefs[Keys.EDITOR_TABS]?.split('\n')?.filter(String::isNotEmpty).orEmpty()
        if (paths.isEmpty()) return null
        return EditorSnapshot(paths, prefs[Keys.EDITOR_ACTIVE] ?: 0, prefs[Keys.EDITOR_CURSOR] ?: 0)
    }

    suspend fun saveEditor(snapshot: EditorSnapshot) {
        dataStore.edit {
            it[Keys.EDITOR_TABS] = snapshot.paths.joinToString("\n")
            it[Keys.EDITOR_ACTIVE] = snapshot.activeIndex
            it[Keys.EDITOR_CURSOR] = snapshot.cursor
        }
    }

    /** The open project's folder; empty when none is open. */
    suspend fun activeProject(): String? = dataStore.data.first()[Keys.ACTIVE_PROJECT]?.takeIf { it.isNotEmpty() }

    suspend fun saveActiveProject(rootPath: String?) {
        dataStore.edit { it[Keys.ACTIVE_PROJECT] = rootPath.orEmpty() }
    }

    suspend fun terminalSplit(): String? = dataStore.data.first()[Keys.TERMINAL_SPLIT]

    suspend fun saveTerminalSplit(name: String) {
        dataStore.edit { it[Keys.TERMINAL_SPLIT] = name }
    }

    val wordWrap: Flow<Boolean?> = dataStore.data.map { it[Keys.WORD_WRAP] }

    suspend fun setWordWrap(wrap: Boolean) {
        dataStore.edit { it[Keys.WORD_WRAP] = wrap }
    }

    /** The phone's own keyboard in the terminal instead of Bori's terminal keys. */
    val terminalSystemKeyboard: Flow<Boolean> = dataStore.data.map { it[Keys.TERMINAL_SYSTEM_KEYBOARD] ?: false }

    suspend fun setTerminalSystemKeyboard(system: Boolean) {
        dataStore.edit { it[Keys.TERMINAL_SYSTEM_KEYBOARD] = system }
    }

    val foldTipSeen: Flow<Boolean> = dataStore.data.map { it[Keys.FOLD_TIP_SEEN] ?: false }

    suspend fun markFoldTipSeen() {
        dataStore.edit { it[Keys.FOLD_TIP_SEEN] = true }
    }
}
