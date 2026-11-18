package com.bori.core.keyboard.settings

import com.bori.core.keyboard.view.Posture
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

    /** Stage 4: 🎤 in the suggestion bar, handing over to voice typing. */
    var voiceKey: Boolean
        get() = sp.getBoolean(VOICE_KEY, true)
        set(v) = sp.edit().putBoolean(VOICE_KEY, v).apply()

    /** Offline voice typing: "offline" (Bori's own model) or "keyboard" (another voice keyboard). */
    var voiceEngine: String
        get() = sp.getString(VOICE_ENGINE, VOICE_KEYBOARD) ?: VOICE_KEYBOARD
        set(v) = sp.edit().putString(VOICE_ENGINE, v).apply()

    /** Id of the active downloaded voice model (voice/VoiceModels.kt), or null. */
    var voiceModel: String?
        get() = sp.getString(VOICE_MODEL, null)
        set(v) = sp.edit().putString(VOICE_MODEL, v).apply()

    var keyPreview: Boolean
        get() = sp.getBoolean(KEY_PREVIEW, true)
        set(v) = sp.edit().putBoolean(KEY_PREVIEW, v).apply()

    var autoCapitalize: Boolean
        get() = sp.getBoolean(AUTO_CAP, true)
        set(v) = sp.edit().putBoolean(AUTO_CAP, v).apply()

    var doubleSpacePeriod: Boolean
        get() = sp.getBoolean(DOUBLE_SPACE, true)
        set(v) = sp.edit().putBoolean(DOUBLE_SPACE, v).apply()

    /**
     * Key height multiplier for one way of holding the phone (folded/unfolded, upright/sideways),
     * 0.6–1.5. Falls back to the old single "key height" setting.
     */
    fun keyHeightScale(p: Posture): Float =
        sp.getFloat(KEY_HEIGHT_PREFIX + p.id, sp.getFloat(KEY_HEIGHT, 1f))

    fun setKeyHeightScale(p: Posture, v: Float) =
        sp.edit().putFloat(KEY_HEIGHT_PREFIX + p.id, v.coerceIn(HEIGHT_MIN, HEIGHT_MAX)).apply()

    /** All four, indexed by [Posture.ordinal], for the keyboard view. */
    fun keyHeightScales(): FloatArray = FloatArray(Posture.entries.size) { keyHeightScale(Posture.entries[it]) }

    /** Stage 4 one-handed mode: "off", "left" or "right" (the side the keys sit on). */
    var oneHanded: String
        get() = sp.getString(ONE_HANDED, "off") ?: "off"
        set(v) {
            val e = sp.edit().putString(ONE_HANDED, v)
            if (v != "off") e.putString(ONE_HANDED_LAST, v).putBoolean(FLOATING, false)
            e.apply()
        }

    /** The side used last, for turning one-handed mode back on from the keyboard. */
    val oneHandedLast: String
        get() = sp.getString(ONE_HANDED_LAST, "right") ?: "right"

    /** The keys' share of the width in one-handed mode, 0.6–0.95. */
    var oneHandedWidth: Float
        get() = sp.getFloat(ONE_HANDED_WIDTH, 0.82f)
        set(v) = sp.edit().putFloat(ONE_HANDED_WIDTH, v.coerceIn(0.6f, 0.95f)).apply()

    /** Stage 4 floating keyboard (turns one-handed off, and the other way round). */
    var floating: Boolean
        get() = sp.getBoolean(FLOATING, false)
        set(v) {
            val e = sp.edit().putBoolean(FLOATING, v)
            if (v) e.putString(ONE_HANDED, "off")
            e.apply()
        }

    /** Floating box position, shares of the free space (0 left/top … 1 right/bottom). */
    var floatX: Float
        get() = sp.getFloat(FLOAT_X, 0.5f)
        set(v) = sp.edit().putFloat(FLOAT_X, v).apply()
    var floatY: Float
        get() = sp.getFloat(FLOAT_Y, 1f)
        set(v) = sp.edit().putFloat(FLOAT_Y, v).apply()

    fun setFloatPosition(x: Float, y: Float) {
        sp.edit().putFloat(FLOAT_X, x).putFloat(FLOAT_Y, y).apply()
    }

    /** Floating keyboard width, share of the screen width (0.45–0.95). */
    var floatWidth: Float
        get() = sp.getFloat(FLOAT_WIDTH, 0.75f)
        set(v) = sp.edit().putFloat(FLOAT_WIDTH, v.coerceIn(0.45f, 0.95f)).apply()

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

    /** Selected theme id (see theme/ThemeStore). */
    var themeId: String
        get() = sp.getString(THEME_ID, null) ?: com.bori.core.keyboard.theme.ThemeStore.CLASSIC
        set(v) = sp.edit().putString(THEME_ID, v).apply()

    /** Gboard-style "Key borders": false draws key labels without key shapes. */
    var keyBorders: Boolean
        get() = sp.getBoolean(KEY_BORDERS, true)
        set(v) = sp.edit().putBoolean(KEY_BORDERS, v).apply()

    /** Keys & letters choices on top of any theme; "" = the theme's own (see ThemeOverrides). */
    var keyLook: String
        get() = sp.getString(KEY_LOOK, "") ?: ""
        set(v) = sp.edit().putString(KEY_LOOK, v).apply()
    var keyShape: String
        get() = sp.getString(KEY_SHAPE, "") ?: ""
        set(v) = sp.edit().putString(KEY_SHAPE, v).apply()
    var keyFont: String
        get() = sp.getString(KEY_FONT, "") ?: ""
        set(v) = sp.edit().putString(KEY_FONT, v).apply()
    var labelCase: String
        get() = sp.getString(LABEL_CASE, "") ?: ""
        set(v) = sp.edit().putString(LABEL_CASE, v).apply()
    var labelScale: Float
        get() = sp.getFloat(LABEL_SCALE, 1f)
        set(v) = sp.edit().putFloat(LABEL_SCALE, v).apply()
    var keyOpacity: Float
        get() = sp.getFloat(KEY_OPACITY, 1f)
        set(v) = sp.edit().putFloat(KEY_OPACITY, v).apply()
    var keyHints: Boolean
        get() = sp.getBoolean(KEY_HINTS, true)
        set(v) = sp.edit().putBoolean(KEY_HINTS, v).apply()

    /** Key sound pack id (ime/SoundPacks), "system" = the phone's own click. */
    var soundPack: String
        get() = sp.getString(SOUND_PACK, "system") ?: "system"
        set(v) = sp.edit().putString(SOUND_PACK, v).apply()

    /** Key sound volume for packs, 0.05–1. */
    var soundVolume: Float
        get() = sp.getFloat(SOUND_VOLUME, 0.6f)
        set(v) = sp.edit().putFloat(SOUND_VOLUME, v.coerceIn(0.05f, 1f)).apply()

    /** Key-press effect id (EffectsView.Effect), "none" = off. */
    var pressEffect: String
        get() = sp.getString(PRESS_EFFECT, "none") ?: "none"
        set(v) = sp.edit().putString(PRESS_EFFECT, v).apply()

    /** Effect colour: "theme" (accent), "rainbow" or "#RRGGBB". */
    var effectColor: String
        get() = sp.getString(EFFECT_COLOR, "theme") ?: "theme"
        set(v) = sp.edit().putString(EFFECT_COLOR, v).apply()

    /** LED lighting mode id (LedLights.Mode), target, colour ("rainbow" or "#RRGGBB"), speed, brightness. */
    var ledMode: String
        get() = sp.getString(LED_MODE, "off") ?: "off"
        set(v) = sp.edit().putString(LED_MODE, v).apply()
    var ledTarget: String
        get() = sp.getString(LED_TARGET, "keys") ?: "keys"
        set(v) = sp.edit().putString(LED_TARGET, v).apply()
    var ledColor: String
        get() = sp.getString(LED_COLOR, "rainbow") ?: "rainbow"
        set(v) = sp.edit().putString(LED_COLOR, v).apply()
    var ledSpeed: Float
        get() = sp.getFloat(LED_SPEED, 1f)
        set(v) = sp.edit().putFloat(LED_SPEED, v).apply()
    var ledBrightness: Float
        get() = sp.getFloat(LED_BRIGHTNESS, 0.6f)
        set(v) = sp.edit().putFloat(LED_BRIGHTNESS, v).apply()

    /** LED rainbow size: rainbows across the keyboard (1 = one, edge to edge). */
    var ledSize: Float
        get() = sp.getFloat(LED_SIZE, 1f)
        set(v) = sp.edit().putFloat(LED_SIZE, v).apply()
    /** LED rainbow wave direction: ltr, rtl or diagonal (LedLights.Direction). */
    var ledDirection: String
        get() = sp.getString(LED_DIRECTION, "ltr") ?: "ltr"
        set(v) = sp.edit().putString(LED_DIRECTION, v).apply()

    /** Ambient background effect (view/AmbientView.kt), "none" = off. */
    var ambientEffect: String
        get() = sp.getString(AMBIENT_EFFECT, "none") ?: "none"
        set(v) = sp.edit().putString(AMBIENT_EFFECT, v).apply()
    var ambientAmount: Float
        get() = sp.getFloat(AMBIENT_AMOUNT, 0.6f)
        set(v) = sp.edit().putFloat(AMBIENT_AMOUNT, v).apply()
    var ambientSpeed: Float
        get() = sp.getFloat(AMBIENT_SPEED, 1f)
        set(v) = sp.edit().putFloat(AMBIENT_SPEED, v).apply()

    /** Theme 3.10 holiday looks: "off", "effect" (effect only) or "theme" (colours + effect). */
    var holidayMode: String
        get() = sp.getString(HOLIDAY_MODE, "off") ?: "off"
        set(v) = sp.edit().putString(HOLIDAY_MODE, v).apply()

    /** A holiday look tried from Settings, shown until [holidayPreviewUntil] (epoch ms). */
    val holidayPreview: String?
        get() = sp.getString(HOLIDAY_PREVIEW, null)
            ?.takeIf { System.currentTimeMillis() < sp.getLong(HOLIDAY_PREVIEW_UNTIL, 0L) }

    fun previewHoliday(id: String?, minutes: Int = 10) {
        val e = sp.edit()
        if (id == null) e.remove(HOLIDAY_PREVIEW).remove(HOLIDAY_PREVIEW_UNTIL)
        else e.putString(HOLIDAY_PREVIEW, id).putLong(HOLIDAY_PREVIEW_UNTIL, System.currentTimeMillis() + minutes * 60_000L)
        e.apply()
    }

    /** Theme 3.10 part 2: the city for "Follows the weather" (label + coordinates), or null. */
    val weatherCity: com.bori.core.keyboard.theme.Weather.City?
        get() {
            val name = sp.getString(WEATHER_NAME, null) ?: return null
            val lat = sp.getString(WEATHER_LAT, null)?.toDoubleOrNull() ?: return null
            val lon = sp.getString(WEATHER_LON, null)?.toDoubleOrNull() ?: return null
            return com.bori.core.keyboard.theme.Weather.City(
                name, sp.getString(WEATHER_DETAIL, "") ?: "", lat, lon,
                sp.getString(WEATHER_CC, "") ?: "", sp.getString(WEATHER_ADMIN1, "") ?: "",
                sp.getInt(WEATHER_POP, 0), sp.getString(WEATHER_ELEV, null)?.toDoubleOrNull() ?: 0.0,
            )
        }

    fun setWeatherCity(c: com.bori.core.keyboard.theme.Weather.City) {
        sp.edit().putString(WEATHER_NAME, c.name).putString(WEATHER_DETAIL, c.detail)
            .putString(WEATHER_LAT, c.lat.toString()).putString(WEATHER_LON, c.lon.toString())
            .putString(WEATHER_CC, c.countryCode).putString(WEATHER_ADMIN1, c.admin1)
            .putInt(WEATHER_POP, c.population).putString(WEATHER_ELEV, c.elevation.toString()).apply()
    }

    /** Theme 3.10 part 3: the keyboard wears a theme for the weather city's kind of place. */
    var cityLook: Boolean
        get() = sp.getBoolean(CITY_LOOK, false)
        set(v) = sp.edit().putBoolean(CITY_LOOK, v).apply()

    /** "auto" (detected from the city) or a Places.Look id picked by hand. */
    var cityLookPick: String
        get() = sp.getString(CITY_LOOK_PICK, "auto") ?: "auto"
        set(v) = sp.edit().putString(CITY_LOOK_PICK, v).apply()

    /** Settings → Themes → Background, on top of any theme; null = the theme's own. */
    var backgroundOverride: com.bori.core.keyboard.theme.BackgroundSpec?
        get() = sp.getString(BACKGROUND, null)?.takeIf { it.isNotEmpty() }?.let {
            runCatching {
                com.bori.core.keyboard.theme.ThemeStore.json.decodeFromString(com.bori.core.keyboard.theme.BackgroundSpec.serializer(), it)
            }.getOrNull()
        }
        set(v) = sp.edit().putString(
            BACKGROUND,
            v?.let { com.bori.core.keyboard.theme.ThemeStore.json.encodeToString(com.bori.core.keyboard.theme.BackgroundSpec.serializer(), it) } ?: "",
        ).apply()

    fun themeOverrides() = com.bori.core.keyboard.theme.ThemeOverrides(
        look = keyLook.ifEmpty { null },
        shape = keyShape.ifEmpty { null },
        font = keyFont.ifEmpty { null },
        labelCase = labelCase.ifEmpty { null },
        labelScale = labelScale,
        keyOpacity = keyOpacity,
        hints = keyHints,
        keyShapes = keyBorders,
        backdrop = backgroundOverride,
    )

    /** Bumped whenever a user theme is saved, so an open keyboard reloads it. */
    var themeRevision: Int
        get() = sp.getInt(THEME_REVISION, 0)
        set(v) = sp.edit().putInt(THEME_REVISION, v).apply()

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

    /** Swipe trail colour: [TRAIL_THEME] (theme accent), [TRAIL_RAINBOW], or an ARGB colour. */
    var glideTrailColor: Int
        get() = sp.getInt(GLIDE_TRAIL_COLOR, TRAIL_THEME)
        set(v) = sp.edit().putInt(GLIDE_TRAIL_COLOR, v).apply()

    /** Swipe trail thickness in dp (the newest part of the trail; older parts taper). */
    var glideTrailWidth: Float
        get() = sp.getFloat(GLIDE_TRAIL_WIDTH, DEFAULT_TRAIL_WIDTH)
        set(v) = sp.edit().putFloat(GLIDE_TRAIL_WIDTH, v).apply()

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

    /** Taps near a key border go to the key the user's heat map says they were aiming for. */
    var aimKeys: Boolean
        get() = sp.getBoolean(AIM_KEYS, true)
        set(v) = sp.edit().putBoolean(AIM_KEYS, v).apply()

    /** Postures (Posture.id) the thumb calibration was done for. */
    var calibrated: Set<String>
        get() = sp.getStringSet(CALIBRATED, emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet(CALIBRATED, v.toSet()).apply()

    /** The keyboard asked once whether to run the thumb calibration. */
    var calibrationAsked: Boolean
        get() = sp.getBoolean(CALIBRATION_ASKED, false)
        set(v) = sp.edit().putBoolean(CALIBRATION_ASKED, v).apply()

    /** Touch heat map: learn where this user's taps land per key and aim corrections there. */
    var touchMap: Boolean
        get() = sp.getBoolean(TOUCH_MAP, true)
        set(v) = sp.edit().putBoolean(TOUCH_MAP, v).apply()

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

    /** Learn emails and usernames typed in sign-in fields and suggest them there (learn/LoginStore.kt). */
    var rememberLogins: Boolean
        get() = sp.getBoolean(REMEMBER_LOGINS, true)
        set(v) = sp.edit().putBoolean(REMEMBER_LOGINS, v).apply()

    /** How long unpinned clips are kept, in ms; 0 = until deleted. Default one week. */
    var clipboardKeepMs: Long
        get() = sp.getLong(CLIPBOARD_KEEP, CLIP_KEEP_WEEK)
        set(v) = sp.edit().putLong(CLIPBOARD_KEEP, v).apply()

    /** Online GIF search (KLIPY). Off until the user turns it on and adds an API key. */
    var gifSearch: Boolean
        get() = sp.getBoolean(GIF_SEARCH, false)
        set(v) = sp.edit().putBoolean(GIF_SEARCH, v).apply()

    /** The user's own KLIPY API key (kept on the phone; never in the source code). */
    var klipyKey: String
        get() = sp.getString(KLIPY_KEY, "").orEmpty()
        set(v) = sp.edit().putString(KLIPY_KEY, v.trim()).apply()

    /** KLIPY content filter: off, low, medium or high. */
    var gifFilter: String
        get() = sp.getString(GIF_FILTER, "medium") ?: "medium"
        set(v) = sp.edit().putString(GIF_FILTER, v).apply()

    /** Random id KLIPY asks for (per user, stays the same); not linked to anything else. */
    val gifCustomerId: String
        get() = sp.getString(GIF_CUSTOMER, null) ?: java.util.UUID.randomUUID().toString().also {
            sp.edit().putString(GIF_CUSTOMER, it).apply()
        }

    /** GIFs sent recently, newest first, as a JSON array (see GifItem). */
    var recentGifs: String
        get() = sp.getString(RECENT_GIFS, "[]") ?: "[]"
        set(v) = sp.edit().putString(RECENT_GIFS, v).apply()

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
    /** Emoji panel style: an [com.bori.core.keyboard.view.EmojiPack] id, or "system". */
    var emojiStyle: String
        get() = sp.getString(EMOJI_STYLE, null) ?: com.bori.core.keyboard.view.EmojiPack.DEFAULT
        set(v) = sp.edit().putString(EMOJI_STYLE, v).apply()

    var recentEmoji: List<String>
        get() = sp.getString(RECENT_EMOJI, "").orEmpty().split('\n').filter { it.isNotEmpty() }
        set(v) = sp.edit().putString(RECENT_EMOJI, v.joinToString("\n")).apply()

    companion object {
        private const val RECENT_EMOJI = "recent_emoji"
        const val CLIPBOARD_HISTORY = "clipboard_history"
        private const val CLIPBOARD_CHIP = "clipboard_chip"
        private const val REMEMBER_LOGINS = "remember_logins"
        const val CLIPBOARD_KEEP = "clipboard_keep_ms"
        const val CLIP_KEEP_HOUR = 60 * 60 * 1000L
        const val CLIP_KEEP_DAY = 24 * CLIP_KEEP_HOUR
        const val CLIP_KEEP_WEEK = 7 * CLIP_KEEP_DAY
        const val CLIP_KEEP_FOREVER = 0L
        private const val GIF_SEARCH = "gif_search"
        private const val KLIPY_KEY = "klipy_key"
        private const val GIF_FILTER = "gif_filter"
        private const val GIF_CUSTOMER = "gif_customer_id"
        private const val RECENT_GIFS = "recent_gifs"
        private const val TAP_TO_FIX = "tap_to_fix"
        private const val FOLLOW_NO_SUGGESTIONS = "follow_no_suggestions"
        const val DEFAULT_SPLIT_GAP = 0.3f
        const val DEFAULT_SPLIT_EDGE = 0.04f
        const val SPLIT_GAP = "split_gap"
        const val SPLIT_EDGE = "split_edge"
        const val SPLIT_BIG_KEY = "split_big_screens"
        const val ONE_HANDED = "one_handed"
        const val ONE_HANDED_WIDTH = "one_handed_width"
        const val FLOATING = "floating"
        private const val VOICE_KEY = "voice_key"
        private const val VOICE_ENGINE = "voice_engine"
        private const val VOICE_MODEL = "voice_model"
        const val VOICE_OFFLINE = "offline"
        const val VOICE_KEYBOARD = "keyboard"
        const val FLOAT_X = "float_x"
        const val FLOAT_Y = "float_y"
        const val FLOAT_WIDTH = "float_width"
        private const val ONE_HANDED_LAST = "one_handed_last"
        private const val ADAPT_SWIPE = "adapt_swipe"
        private const val TYPO_LOG = "typo_log"
        const val TOUCH_MAP = "touch_map"
        const val AIM_KEYS = "aim_keys"
        private const val CALIBRATED = "calibrated_postures"
        private const val CALIBRATION_ASKED = "calibration_asked"
        private const val GLIDE = "glide_typing"
        private const val GLIDE_TRAIL = "glide_trail"
        private const val EMOJI_STYLE = "emoji_style"
        private const val GLIDE_TRAIL_COLOR = "glide_trail_color"
        private const val GLIDE_TRAIL_WIDTH = "glide_trail_width"
        /** Not real colours (alpha 0), so they can't clash with a picked colour. */
        const val TRAIL_THEME = 0
        const val TRAIL_RAINBOW = 1
        const val DEFAULT_TRAIL_WIDTH = 5f
        private const val SPACE_CURSOR = "space_cursor"
        private const val DELETE_SLIDE = "delete_slide"
        private const val KEY_FLICKS = "key_flicks"
        private const val SUGGESTIONS = "show_suggestions"
        private const val NEXT_WORD = "next_word_prediction"
        private const val AUTOCORRECT = "autocorrect_level"
        const val THEME_MODE = "theme_mode"
        const val THEME_ID = "theme_id"
        const val THEME_REVISION = "theme_revision"
        const val KEY_BORDERS = "key_borders"
        private const val KEY_LOOK = "key_look"
        private const val KEY_SHAPE = "key_shape"
        private const val KEY_FONT = "key_font"
        private const val LABEL_CASE = "label_case"
        private const val LABEL_SCALE = "label_scale"
        private const val KEY_OPACITY = "key_opacity"
        private const val KEY_HINTS = "key_hints"
        /** Any of these changing re-themes an open keyboard. */
        val THEME_KEYS = setOf(
            THEME_MODE, THEME_ID, THEME_REVISION, KEY_BORDERS, KEY_LOOK, KEY_SHAPE, KEY_FONT,
            LABEL_CASE, LABEL_SCALE, KEY_OPACITY, KEY_HINTS, BACKGROUND,
            HOLIDAY_MODE, HOLIDAY_PREVIEW, HOLIDAY_PREVIEW_UNTIL, WEATHER_NAME, WEATHER_LAT, WEATHER_LON,
            CITY_LOOK, CITY_LOOK_PICK,
        )
        private const val WEATHER_CC = "weather_cc"
        private const val WEATHER_ADMIN1 = "weather_admin1"
        private const val WEATHER_POP = "weather_pop"
        private const val WEATHER_ELEV = "weather_elev"
        private const val CITY_LOOK = "city_look"
        private const val CITY_LOOK_PICK = "city_look_pick"
        private const val WEATHER_NAME = "weather_city"
        private const val WEATHER_DETAIL = "weather_city_detail"
        private const val WEATHER_LAT = "weather_lat"
        private const val WEATHER_LON = "weather_lon"
        private const val HOLIDAY_MODE = "holiday_mode"
        private const val HOLIDAY_PREVIEW = "holiday_preview"
        private const val HOLIDAY_PREVIEW_UNTIL = "holiday_preview_until"
        private const val BACKGROUND = "background_override"
        private const val PRESS_EFFECT = "press_effect"
        private const val SOUND_PACK = "sound_pack"
        private const val SOUND_VOLUME = "sound_volume"
        private const val EFFECT_COLOR = "effect_color"
        private const val LED_MODE = "led_mode"
        private const val LED_TARGET = "led_target"
        private const val LED_COLOR = "led_color"
        private const val LED_SPEED = "led_speed"
        private const val LED_BRIGHTNESS = "led_brightness"
        /** Prefs that change press effects or LED lighting (the IME re-applies them live). */
        private const val LED_SIZE = "led_size"
        private const val LED_DIRECTION = "led_direction"
        private const val AMBIENT_EFFECT = "ambient_effect"
        private const val AMBIENT_AMOUNT = "ambient_amount"
        private const val AMBIENT_SPEED = "ambient_speed"
        val EFFECT_KEYS = setOf(
            PRESS_EFFECT, EFFECT_COLOR, LED_MODE, LED_TARGET, LED_COLOR, LED_SPEED, LED_BRIGHTNESS,
            AMBIENT_EFFECT, AMBIENT_AMOUNT, AMBIENT_SPEED, LED_SIZE, LED_DIRECTION,
        )
        private const val INCOGNITO = "incognito"
        private const val BLOCK_OFFENSIVE = "block_offensive"
        private const val HAPTICS = "haptics"
        private const val VIBRATION_STRENGTH = "vibration_strength"
        private const val SOUND = "sound"
        private const val KEY_PREVIEW = "key_preview"
        private const val AUTO_CAP = "auto_cap"
        private const val DOUBLE_SPACE = "double_space_period"
        private const val KEY_HEIGHT = "key_height_scale"
        const val KEY_HEIGHT_PREFIX = "key_height_"
        const val HEIGHT_MIN = 0.6f
        const val HEIGHT_MAX = 1.5f
        private const val NUMBER_ROW = "number_row"
        private const val SPLIT_BIG = SPLIT_BIG_KEY
        private const val SPLIT_ASKED = "split_big_screens_asked"
    }
}
