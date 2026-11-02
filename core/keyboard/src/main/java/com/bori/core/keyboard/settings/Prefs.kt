package com.bori.core.keyboard.settings

import android.content.Context
import android.content.SharedPreferences
import com.bori.core.keyboard.predict.Aggressiveness
import com.bori.core.keyboard.theme.ThemeMode

/** Keyboard settings, shared by the settings app and the IME service. */
class Prefs(context: Context, file: String = "bori_settings") {
    private val sp = context.applicationContext.getSharedPreferences(file, Context.MODE_PRIVATE)

    var haptics: Boolean
        get() = sp.getBoolean(HAPTICS, true)
        set(v) = sp.edit().putBoolean(HAPTICS, v).apply()

    /** Key vibration strength, 1–100. */
    var vibrationStrength: Int
        get() = sp.getInt(VIBRATION_STRENGTH, 60)
        set(v) = sp.edit().putInt(VIBRATION_STRENGTH, v.coerceIn(1, 100)).apply()

    var sound: Boolean
        get() = sp.getBoolean(SOUND, false)
        set(v) = sp.edit().putBoolean(SOUND, v).apply()

    var keyPreview: Boolean
        get() = sp.getBoolean(KEY_PREVIEW, true)
        set(v) = sp.edit().putBoolean(KEY_PREVIEW, v).apply()

    var autoCapitalize: Boolean
        get() = sp.getBoolean(AUTO_CAP, true)
        set(v) = sp.edit().putBoolean(AUTO_CAP, v).apply()

    var doubleSpacePeriod: Boolean
        get() = sp.getBoolean(DOUBLE_SPACE, true)
        set(v) = sp.edit().putBoolean(DOUBLE_SPACE, v).apply()

    /** Multiplier for the default key height, 0.8–1.3. */
    var keyHeightScale: Float
        get() = sp.getFloat(KEY_HEIGHT, 1f)
        set(v) = sp.edit().putFloat(KEY_HEIGHT, v.coerceIn(0.8f, 1.3f)).apply()

    /** Split keyboard on big screens (unfolded foldables, tablets). */
    var splitOnBigScreens: Boolean
        get() = sp.getBoolean(SPLIT_BIG, true)
        set(v) = sp.edit().putBoolean(SPLIT_BIG, v).putBoolean(SPLIT_ASKED, true).apply()

    /** Split keyboard: space between the halves, as a share of the screen width (0.1–0.5). */
    var splitGap: Float
        get() = sp.getFloat(SPLIT_GAP, DEFAULT_SPLIT_GAP)
        set(v) = sp.edit().putFloat(SPLIT_GAP, v.coerceIn(0.1f, 0.5f)).apply()

    /** Split keyboard: space between each half and the screen edge, as a share of the width (0–0.2). */
    var splitEdge: Float
        get() = sp.getFloat(SPLIT_EDGE, DEFAULT_SPLIT_EDGE)
        set(v) = sp.edit().putFloat(SPLIT_EDGE, v.coerceIn(0f, 0.2f)).apply()

    /** Calls [onChange] with the key of every setting changed while registered. */
    fun listen(onChange: (String?) -> Unit): SharedPreferences.OnSharedPreferenceChangeListener {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> onChange(key) }
        sp.registerOnSharedPreferenceChangeListener(l)
        return l
    }

    fun unlisten(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.unregisterOnSharedPreferenceChangeListener(l)

    /** The user has chosen a big-screen layout, so the keyboard stops asking. */
    var splitAsked: Boolean
        get() = sp.getBoolean(SPLIT_ASKED, false)
        set(v) = sp.edit().putBoolean(SPLIT_ASKED, v).apply()

    /** A row of digits above the letters. */
    var numberRow: Boolean
        get() = sp.getBoolean(NUMBER_ROW, true)
        set(v) = sp.edit().putBoolean(NUMBER_ROW, v).apply()

    var showSuggestions: Boolean
        get() = sp.getBoolean(SUGGESTIONS, true)
        set(v) = sp.edit().putBoolean(SUGGESTIONS, v).apply()

    var nextWordPrediction: Boolean
        get() = sp.getBoolean(NEXT_WORD, true)
        set(v) = sp.edit().putBoolean(NEXT_WORD, v).apply()

    var autocorrect: Aggressiveness
        get() = runCatching { Aggressiveness.valueOf(sp.getString(AUTOCORRECT, null) ?: "") }
            .getOrDefault(Aggressiveness.NORMAL)
        set(v) = sp.edit().putString(AUTOCORRECT, v.name).apply()

    var themeMode: ThemeMode
        get() = runCatching { ThemeMode.valueOf(sp.getString(THEME_MODE, null) ?: "") }
            .getOrDefault(ThemeMode.SYSTEM)
        set(v) = sp.edit().putString(THEME_MODE, v.name).apply()

    /** Nothing typed is learned while on. */
    var incognito: Boolean
        get() = sp.getBoolean(INCOGNITO, false)
        set(v) = sp.edit().putBoolean(INCOGNITO, v).apply()

    var blockOffensive: Boolean
        get() = sp.getBoolean(BLOCK_OFFENSIVE, true)
        set(v) = sp.edit().putBoolean(BLOCK_OFFENSIVE, v).apply()

    var glideTyping: Boolean
        get() = sp.getBoolean(GLIDE, true)
        set(v) = sp.edit().putBoolean(GLIDE, v).apply()

    var glideTrail: Boolean
        get() = sp.getBoolean(GLIDE_TRAIL, true)
        set(v) = sp.edit().putBoolean(GLIDE_TRAIL, v).apply()

    var spaceCursor: Boolean
        get() = sp.getBoolean(SPACE_CURSOR, true)
        set(v) = sp.edit().putBoolean(SPACE_CURSOR, v).apply()

    var deleteSlide: Boolean
        get() = sp.getBoolean(DELETE_SLIDE, true)
        set(v) = sp.edit().putBoolean(DELETE_SLIDE, v).apply()

    /** Quick upward swipe on a letter types its corner hint (digit on the top row). */
    var keyFlicks: Boolean
        get() = sp.getBoolean(KEY_FLICKS, true)
        set(v) = sp.edit().putBoolean(KEY_FLICKS, v).apply()

    /** Keep a local log of the user's own corrections (see TypoRecorder). Off by default. */
    var typoLog: Boolean
        get() = sp.getBoolean(TYPO_LOG, false)
        set(v) = sp.edit().putBoolean(TYPO_LOG, v).apply()

    /** Learn this user's finger offset per key from accepted glides and the Swipe Trainer. */
    var adaptSwipe: Boolean
        get() = sp.getBoolean(ADAPT_SWIPE, true)
        set(v) = sp.edit().putBoolean(ADAPT_SWIPE, v).apply()

    /** Keep recently copied text for the clipboard panel (on this phone only). */
    var clipboardHistory: Boolean
        get() = sp.getBoolean(CLIPBOARD_HISTORY, true)
        set(v) = sp.edit().putBoolean(CLIPBOARD_HISTORY, v).apply()

    /** Offer just-copied text as a chip in the suggestion bar. */
    var clipboardChip: Boolean
        get() = sp.getBoolean(CLIPBOARD_CHIP, true)
        set(v) = sp.edit().putBoolean(CLIPBOARD_CHIP, v).apply()

    /**
     * Honour fields that ask for no suggestions (TYPE_TEXT_FLAG_NO_SUGGESTIONS). Off by
     * default: the Google search box and other search boxes set it, and with it on they get
     * no swipe, suggestions or corrections.
     */
    var followNoSuggestions: Boolean
        get() = sp.getBoolean(FOLLOW_NO_SUGGESTIONS, false)
        set(v) = sp.edit().putBoolean(FOLLOW_NO_SUGGESTIONS, v).apply()

    /** Tapping a word already typed shows fixes for it in the suggestion bar. */
    var tapToFix: Boolean
        get() = sp.getBoolean(TAP_TO_FIX, true)
        set(v) = sp.edit().putBoolean(TAP_TO_FIX, v).apply()

    /** Recently used emoji, newest first, one per line. */
    var recentEmoji: List<String>
        get() = sp.getString(RECENT_EMOJI, "").orEmpty().split('\n').filter { it.isNotEmpty() }
        set(v) = sp.edit().putString(RECENT_EMOJI, v.joinToString("\n")).apply()

    companion object {
        private const val RECENT_EMOJI = "recent_emoji"
        const val CLIPBOARD_HISTORY = "clipboard_history"
        private const val CLIPBOARD_CHIP = "clipboard_chip"
        private const val TAP_TO_FIX = "tap_to_fix"
        private const val FOLLOW_NO_SUGGESTIONS = "follow_no_suggestions"
        const val DEFAULT_SPLIT_GAP = 0.3f
        const val DEFAULT_SPLIT_EDGE = 0.04f
        const val SPLIT_GAP = "split_gap"
        const val SPLIT_EDGE = "split_edge"
        const val SPLIT_BIG_KEY = "split_big_screens"
        private const val ADAPT_SWIPE = "adapt_swipe"
        private const val TYPO_LOG = "typo_log"
        private const val GLIDE = "glide_typing"
        private const val GLIDE_TRAIL = "glide_trail"
        private const val SPACE_CURSOR = "space_cursor"
        private const val DELETE_SLIDE = "delete_slide"
        private const val KEY_FLICKS = "key_flicks"
        private const val SUGGESTIONS = "show_suggestions"
        private const val NEXT_WORD = "next_word_prediction"
        private const val AUTOCORRECT = "autocorrect_level"
        const val THEME_MODE = "theme_mode"
        private const val INCOGNITO = "incognito"
        private const val BLOCK_OFFENSIVE = "block_offensive"
        private const val HAPTICS = "haptics"
        private const val VIBRATION_STRENGTH = "vibration_strength"
        private const val SOUND = "sound"
        private const val KEY_PREVIEW = "key_preview"
        private const val AUTO_CAP = "auto_cap"
        private const val DOUBLE_SPACE = "double_space_period"
        private const val KEY_HEIGHT = "key_height_scale"
        private const val NUMBER_ROW = "number_row"
        private const val SPLIT_BIG = SPLIT_BIG_KEY
        private const val SPLIT_ASKED = "split_big_screens_asked"
    }
}
