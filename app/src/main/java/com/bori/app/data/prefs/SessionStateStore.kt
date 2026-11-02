package com.bori.app.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** How the terminal's keys look and behave; set in the keyboard's settings sheet. */
data class TerminalKeysSettings(
    /** Row height, times the default (0.8–1.3, as in Bori Keyboard). */
    val height: Float = 1f,
    /** Split the keys in two halves: null follows the screen (split on an unfolded foldable). */
    val split: Boolean? = null,
    /** Split keys: space between the halves, as a share of the keyboard's width (0.1–0.5). */
    val gap: Float = 0.3f,
    /** Split keys: space between each half and the edge, as a share of the width (0–0.2). */
    val edge: Float = 0.04f,
    /** Key vibration, 0 (off) to 100. */
    val vibration: Int = 25,
    /** OFF, MILD, NORMAL or AGGRESSIVE. */
    val autocorrect: String = "NORMAL",
    val numberRow: Boolean = true,
    /** "en" or "es". */
    val language: String = "en",
)

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
        val AUTO_ENTER_EXPLAINED = booleanPreferencesKey("auto_enter_explained")
        val KEYS_HEIGHT = floatPreferencesKey("terminal_keys_height")
        val KEYS_SPLIT = stringPreferencesKey("terminal_keys_split")
        val KEYS_GAP = floatPreferencesKey("terminal_keys_split_gap")
        val KEYS_EDGE = floatPreferencesKey("terminal_keys_split_edge")
        val KEYS_VIBRATION = intPreferencesKey("terminal_keys_vibration")
        val KEYS_AUTOCORRECT = stringPreferencesKey("terminal_keys_autocorrect")
        val KEYS_NUMBER_ROW = booleanPreferencesKey("terminal_keys_number_row")
        val KEYS_LANGUAGE = stringPreferencesKey("terminal_keys_language")
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

    /** The user has read what auto-enter does (shown the first time it's turned on). */
    val autoEnterExplained: Flow<Boolean> = dataStore.data.map { it[Keys.AUTO_ENTER_EXPLAINED] ?: false }

    suspend fun markAutoEnterExplained() {
        dataStore.edit { it[Keys.AUTO_ENTER_EXPLAINED] = true }
    }

    val terminalKeys: Flow<TerminalKeysSettings> = dataStore.data.map { p ->
        val d = TerminalKeysSettings()
        TerminalKeysSettings(
            height = p[Keys.KEYS_HEIGHT] ?: d.height,
            split = when (p[Keys.KEYS_SPLIT]) { "on" -> true; "off" -> false; else -> null },
            gap = p[Keys.KEYS_GAP] ?: d.gap,
            edge = p[Keys.KEYS_EDGE] ?: d.edge,
            vibration = p[Keys.KEYS_VIBRATION] ?: d.vibration,
            autocorrect = p[Keys.KEYS_AUTOCORRECT] ?: d.autocorrect,
            numberRow = p[Keys.KEYS_NUMBER_ROW] ?: d.numberRow,
            language = p[Keys.KEYS_LANGUAGE] ?: d.language,
        )
    }

    suspend fun setTerminalKeys(s: TerminalKeysSettings) {
        dataStore.edit {
            it[Keys.KEYS_HEIGHT] = s.height
            it[Keys.KEYS_SPLIT] = when (s.split) { true -> "on"; false -> "off"; null -> "auto" }
            it[Keys.KEYS_GAP] = s.gap
            it[Keys.KEYS_EDGE] = s.edge
            it[Keys.KEYS_VIBRATION] = s.vibration
            it[Keys.KEYS_AUTOCORRECT] = s.autocorrect
            it[Keys.KEYS_NUMBER_ROW] = s.numberRow
            it[Keys.KEYS_LANGUAGE] = s.language
        }
    }
}
