package com.bori.core.keyboard.learn

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Everything the keyboard learns from the user, kept only on this phone (app-private
 * database, excluded from backup). Per language it holds:
 *  - word counts (learned words become suggestions after [LEARN_AFTER] uses),
 *  - word-pair counts for next-word prediction,
 *  - the personal dictionary (words added explicitly, always valid),
 *  - blocked words that must never be suggested,
 *  - (all languages) the user's average finger offset at each key, for glide typing.
 * Reads come from in-memory maps; writes go to SQLite on a background thread.
 */
class UserStore private constructor(context: Context) {

    private val helper = Helper(context.applicationContext)
    private val io = Executors.newSingleThreadExecutor()

    @Volatile private var lang = ""
    private val words = ConcurrentHashMap<String, Int>()
    private val pairs = ConcurrentHashMap<String, Int>()
    private val personal: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val blocked: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** Dictionary words whose correction the user undid, and how often. */
    private val kept = ConcurrentHashMap<String, Int>()
    /** Per key: [sum dx, sum dy, samples], in key units. */
    private val offsets = ConcurrentHashMap<Char, FloatArray>()
    @Volatile private var offsetsLoaded = false

    /** Loads [language]'s data (blocking; call from a background thread). */
    @Synchronized
    fun load(language: String) {
        if (language == lang) return
        words.clear(); pairs.clear(); personal.clear(); blocked.clear(); kept.clear()
        val db = helper.readableDatabase
        db.rawQuery("SELECT word, count FROM words WHERE lang=?", arrayOf(language)).use {
            while (it.moveToNext()) words[it.getString(0)] = it.getInt(1)
        }
        db.rawQuery("SELECT prev, word, count FROM pairs WHERE lang=?", arrayOf(language)).use {
            while (it.moveToNext()) pairs[pairKey(it.getString(0), it.getString(1))] = it.getInt(2)
        }
        db.rawQuery("SELECT word FROM personal WHERE lang=?", arrayOf(language)).use {
            while (it.moveToNext()) personal.add(it.getString(0))
        }
        db.rawQuery("SELECT word FROM blocked WHERE lang=?", arrayOf(language)).use {
            while (it.moveToNext()) blocked.add(it.getString(0))
        }
        db.rawQuery("SELECT word, count FROM kept WHERE lang=?", arrayOf(language)).use {
            while (it.moveToNext()) kept[it.getString(0)] = it.getInt(1)
        }
        if (!offsetsLoaded) {
            db.rawQuery("SELECT key, sx, sy, n FROM key_offsets", null).use {
                while (it.moveToNext()) {
                    val k = it.getString(0).firstOrNull() ?: continue
                    offsets[k] = floatArrayOf(it.getFloat(1), it.getFloat(2), it.getFloat(3))
                }
            }
            offsetsLoaded = true
        }
        lang = language
    }

    // ---------------------------------------------------------------- queries (keys lowercase)

    fun countOf(word: String): Int = words[word.lowercase()] ?: 0
    fun pairCount(prev: String, word: String): Int = pairs[pairKey(prev.lowercase(), word.lowercase())] ?: 0
    fun isPersonal(word: String): Boolean = personal.contains(word.lowercase())
    fun isBlocked(word: String): Boolean = blocked.contains(word.lowercase())
    fun keptCount(word: String): Int = kept[word.lowercase()] ?: 0

    /** Learned or personal words, with their display form as stored, for fuzzy matching. */
    fun knownWords(): List<String> {
        val out = ArrayList<String>()
        for ((w, c) in words) if (c >= LEARN_AFTER) out.add(w)
        for (w in personal) if (!words.containsKey(w)) out.add(w)
        return out
    }

    /** Words the user has typed after [prev], most frequent first. */
    fun nextWords(prev: String, limit: Int): List<Pair<String, Int>> {
        val prefix = prev.lowercase() + SEP
        return pairs.entries.asSequence()
            .filter { it.key.startsWith(prefix) }
            .map { it.key.substring(prefix.length) to it.value }
            .sortedByDescending { it.second }
            .take(limit)
            .toList()
    }

    // ---------------------------------------------------------------- learning

    /**
     * Records a committed word. [boost] > 1 when the user explicitly chose a word the
     * dictionary doesn't know (picked their literal typing), so it is learned at once.
     */
    fun recordWord(prev: String?, word: String, boost: Int = 1) {
        val key = word.lowercase()
        val language = lang
        if (language.isEmpty() || key.isEmpty() || key.length > 48) return
        val count = words.merge(key, boost) { a, b -> a + b } ?: boost
        val prevKey = prev?.lowercase()
        val pairCount = if (prevKey != null) pairs.merge(pairKey(prevKey, key), 1) { a, b -> a + b } ?: 1 else 0
        val now = System.currentTimeMillis()
        io.execute {
            val db = helper.writableDatabase
            db.insertWithOnConflict("words", null, ContentValues().apply {
                put("lang", language); put("word", key); put("count", count); put("last", now)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            if (prevKey != null) {
                db.insertWithOnConflict("pairs", null, ContentValues().apply {
                    put("lang", language); put("prev", prevKey); put("word", key)
                    put("count", pairCount); put("last", now)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
    }

    /**
     * The user undid the correction of dictionary word [word]. Unlike [recordWord] this
     * doesn't boost it: it only stops the same fix once it has been undone [KEEP_AFTER] times.
     */
    fun recordKeep(word: String) {
        val key = word.lowercase()
        val language = lang
        if (language.isEmpty() || key.isEmpty()) return
        val count = kept.merge(key, 1) { a, b -> a + b } ?: 1
        io.execute {
            helper.writableDatabase.insertWithOnConflict("kept", null, ContentValues().apply {
                put("lang", language); put("word", key); put("count", count)
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    /**
     * A typed dictionary word was corrected to another word, so it was probably a slip:
     * lowers its learned count by one, so earlier slips ("ans" for "and") stop being boosted.
     */
    fun demote(word: String) {
        val key = word.lowercase()
        val language = lang
        val old = words[key] ?: return
        if (language.isEmpty()) return
        val count = old - 1
        if (count <= 0) words.remove(key) else words[key] = count
        io.execute {
            val db = helper.writableDatabase
            if (count <= 0) {
                db.delete("words", "lang=? AND word=?", arrayOf(language, key))
            } else {
                db.update("words", ContentValues().apply { put("count", count) }, "lang=? AND word=?", arrayOf(language, key))
            }
        }
    }

    fun addPersonal(word: String) {
        val key = word.lowercase()
        personal.add(key)
        blocked.remove(key)
        val language = lang
        io.execute {
            val db = helper.writableDatabase
            db.insertWithOnConflict("personal", null, ContentValues().apply {
                put("lang", language); put("word", key)
            }, SQLiteDatabase.CONFLICT_IGNORE)
            db.delete("blocked", "lang=? AND word=?", arrayOf(language, key))
        }
    }

    fun block(word: String) {
        val key = word.lowercase()
        blocked.add(key)
        personal.remove(key)
        words.remove(key)
        val language = lang
        io.execute {
            val db = helper.writableDatabase
            db.insertWithOnConflict("blocked", null, ContentValues().apply {
                put("lang", language); put("word", key)
            }, SQLiteDatabase.CONFLICT_IGNORE)
            db.delete("personal", "lang=? AND word=?", arrayOf(language, key))
            db.delete("words", "lang=? AND word=?", arrayOf(language, key))
        }
    }

    // ---------------------------------------------------------------- swipe adaptation

    /**
     * Learned finger offset at key [c] in key units (x then y), shrunk towards zero until
     * there are enough samples and capped at half a key.
     */
    fun keyOffsetX(c: Char): Float = offsets[c]?.let { (it[0] / (it[2] + OFFSET_PRIOR)).coerceIn(-0.5f, 0.5f) } ?: 0f
    fun keyOffsetY(c: Char): Float = offsets[c]?.let { (it[1] / (it[2] + OFFSET_PRIOR)).coerceIn(-0.5f, 0.5f) } ?: 0f

    /** Adds (key, dx, dy) observations from a swipe whose intended word is known. */
    fun addKeyOffsetSamples(samples: List<Triple<Char, Float, Float>>) {
        if (samples.isEmpty()) return
        val changed = HashMap<Char, FloatArray>()
        for ((k, dx, dy) in samples) {
            val a = offsets.getOrPut(k) { FloatArray(3) }
            synchronized(a) {
                if (a[2] >= OFFSET_MAX_SAMPLES) {
                    // Keep adapting: older samples fade.
                    a[0] *= 0.5f; a[1] *= 0.5f; a[2] *= 0.5f
                }
                a[0] += dx; a[1] += dy; a[2] += 1f
                changed[k] = a.copyOf()
            }
        }
        io.execute {
            val db = helper.writableDatabase
            for ((k, a) in changed) {
                db.insertWithOnConflict("key_offsets", null, ContentValues().apply {
                    put("key", k.toString()); put("sx", a[0]); put("sy", a[1]); put("n", a[2])
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
    }

    fun keyOffsetSampleCount(): Int = offsets.values.sumOf { it[2].toInt() }

    fun clearKeyOffsets() {
        offsets.clear()
        io.execute { helper.writableDatabase.delete("key_offsets", null, null) }
    }

    // ---------------------------------------------------------------- settings screen

    data class Entry(val lang: String, val word: String)

    fun listPersonal(): List<Entry> = list("SELECT lang, word FROM personal ORDER BY lang, word")
    fun listLearned(): List<Entry> =
        list("SELECT lang, word FROM words WHERE count >= $LEARN_AFTER ORDER BY lang, count DESC")
    fun listBlocked(): List<Entry> = list("SELECT lang, word FROM blocked ORDER BY lang, word")

    private fun list(sql: String): List<Entry> =
        helper.readableDatabase.rawQuery(sql, null).use { c ->
            buildList { while (c.moveToNext()) add(Entry(c.getString(0), c.getString(1))) }
        }

    /** Adds a word from the settings screen, for any language. */
    fun addPersonal(language: String, word: String) {
        val key = word.trim().lowercase()
        if (key.isEmpty()) return
        helper.writableDatabase.apply {
            insertWithOnConflict("personal", null, ContentValues().apply {
                put("lang", language); put("word", key)
            }, SQLiteDatabase.CONFLICT_IGNORE)
            delete("blocked", "lang=? AND word=?", arrayOf(language, key))
        }
        lang = ""
    }

    fun remove(table: String, e: Entry) {
        require(table in setOf("personal", "words", "blocked"))
        helper.writableDatabase.delete(table, "lang=? AND word=?", arrayOf(e.lang, e.word))
        if (table == "words") helper.writableDatabase.delete("kept", "lang=? AND word=?", arrayOf(e.lang, e.word))
        if (table == "words") helper.writableDatabase.delete("pairs", "lang=? AND (word=? OR prev=?)", arrayOf(e.lang, e.word, e.word))
        lang = "" // force a reload next time the keyboard starts
    }

    fun clearLearned() {
        helper.writableDatabase.apply {
            delete("words", null, null)
            delete("pairs", null, null)
            delete("kept", null, null)
        }
        lang = ""
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, "terminal_words.db", null, 3) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE words (lang TEXT NOT NULL, word TEXT NOT NULL, count INTEGER NOT NULL, last INTEGER NOT NULL, PRIMARY KEY (lang, word))")
            db.execSQL("CREATE TABLE pairs (lang TEXT NOT NULL, prev TEXT NOT NULL, word TEXT NOT NULL, count INTEGER NOT NULL, last INTEGER NOT NULL, PRIMARY KEY (lang, prev, word))")
            db.execSQL("CREATE TABLE personal (lang TEXT NOT NULL, word TEXT NOT NULL, PRIMARY KEY (lang, word))")
            db.execSQL("CREATE TABLE blocked (lang TEXT NOT NULL, word TEXT NOT NULL, PRIMARY KEY (lang, word))")
            createKeyOffsets(db)
            createKept(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) createKeyOffsets(db)
            if (oldVersion < 3) createKept(db)
        }

        private fun createKept(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE kept (lang TEXT NOT NULL, word TEXT NOT NULL, count INTEGER NOT NULL, PRIMARY KEY (lang, word))")
        }

        private fun createKeyOffsets(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE key_offsets (key TEXT PRIMARY KEY, sx REAL NOT NULL, sy REAL NOT NULL, n REAL NOT NULL)")
        }
    }

    companion object {
        @Volatile private var instance: UserStore? = null

        /** One store per process, shared by the keyboard service and the settings screens. */
        fun get(context: Context): UserStore =
            instance ?: synchronized(this) { instance ?: UserStore(context).also { instance = it } }

        /** Unknown words become suggestions after being typed this many times. */
        const val LEARN_AFTER = 2
        /** A real-word fix stops once the user has undone it this many times. */
        const val KEEP_AFTER = 2
        /** Offsets count as this many zero samples, so a few swipes can't shift a key much. */
        private const val OFFSET_PRIOR = 8f
        private const val OFFSET_MAX_SAMPLES = 300f
        private const val SEP = '\u0001'
        private fun pairKey(prev: String, word: String) = prev + SEP + word
    }
}
