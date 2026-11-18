package com.bori.core.keyboard.learn

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Email addresses and usernames the user types into sign-in and email fields, offered again
 * in the suggestion bar there (like Gboard and Samsung Keyboard). A small JSON file in filesDir,
 * included in backups (user decision, Session 49; it was in noBackupFilesDir before and is
 * moved on first load). Never holds passwords: the
 * keyboard learns nothing in password fields.
 *
 * An entry shows once it is [confirmed] (added by hand, or typed right before a password
 * field, which marks it as a sign-in) or used [SHOW_AFTER] times.
 */
class LoginStore private constructor(context: Context) {

    class Entry(val text: String, val uses: Int, val last: Long, val confirmed: Boolean) {
        val isEmail get() = looksLikeEmail(text)
        val shown get() = confirmed || uses >= SHOW_AFTER
    }

    private val file = File(context.filesDir, "logins.json").also { f ->
        val old = File(context.noBackupFilesDir, "logins.json")
        if (old.exists() && !f.exists() && !old.renameTo(f)) runCatching { old.copyTo(f); old.delete() }
    }
    private val io = Executors.newSingleThreadExecutor()
    private var entries = ArrayList<Entry>()
    private var loaded = false

    /** Loads the file once (the keyboard and Settings share one process, so one copy). */
    @Synchronized
    private fun fresh() {
        if (loaded) return
        loaded = true
        if (!file.exists()) return
        runCatching {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val text = o.optString("t").trim()
                if (text.isNotEmpty()) entries.add(Entry(text, o.optInt("n"), o.optLong("at"), o.optBoolean("ok")))
            }
        }
    }

    @Synchronized
    private fun save() {
        val arr = JSONArray()
        for (e in entries) arr.put(JSONObject().put("t", e.text).put("n", e.uses).put("at", e.last).put("ok", e.confirmed))
        val json = arr.toString()
        io.execute {
            runCatching {
                val tmp = File(file.path + ".tmp")
                tmp.writeText(json)
                if (!tmp.renameTo(file)) file.writeText(json)
            }
        }
    }

    /** All entries, most used and most recent first. */
    @Synchronized
    fun list(): List<Entry> {
        fresh()
        return entries.sortedWith(compareByDescending<Entry> { it.confirmed }.thenByDescending { it.uses }.thenByDescending { it.last })
    }

    /** Shown entries starting with [prefix] (any case), best first. */
    @Synchronized
    fun matches(prefix: String, limit: Int): List<String> {
        val p = prefix.lowercase()
        return list().asSequence()
            .filter { it.shown && it.text.lowercase().startsWith(p) && !it.text.equals(prefix, ignoreCase = true) }
            .map { it.text }
            .take(limit)
            .toList()
    }

    /** Domains of the user's saved emails, most used first ("gmail.com"). */
    @Synchronized
    fun domains(): List<String> =
        list().filter { it.shown && it.isEmail }.map { it.text.substringAfter('@').lowercase() }.distinct()

    /** Counts one use of [text]; [confirmed] marks it as a real sign-in or a hand-added entry. */
    @Synchronized
    fun record(text: String, confirmed: Boolean) {
        val t = text.trim()
        if (!acceptable(t)) return
        fresh()
        val i = entries.indexOfFirst { it.text.equals(t, ignoreCase = true) }
        val now = System.currentTimeMillis()
        if (i >= 0) {
            val old = entries[i]
            entries[i] = Entry(t, old.uses + 1, now, old.confirmed || confirmed)
        } else {
            entries.add(Entry(t, 1, now, confirmed))
            if (entries.size > MAX_ENTRIES) {
                // Drop the least useful entry that the user didn't add or confirm.
                val victim = entries.filter { !it.confirmed }.minWithOrNull(compareBy<Entry> { it.uses }.thenBy { it.last })
                    ?: entries.minByOrNull { it.last }
                entries.remove(victim)
            }
        }
        save()
    }

    @Synchronized
    fun remove(text: String) {
        fresh()
        if (entries.removeAll { it.text.equals(text, ignoreCase = true) }) save()
    }

    @Synchronized
    fun clear() {
        fresh()
        entries.clear()
        save()
    }

    companion object {
        /** Uses before an entry that wasn't confirmed is suggested (the dictionary's rule too). */
        const val SHOW_AFTER = 2
        private const val MAX_ENTRIES = 50
        private const val MAX_LENGTH = 80

        private val EMAIL = Regex("^[A-Za-z0-9._%+'-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}$")

        /** Common email domains offered after "@" when the user's own don't match. */
        val COMMON_DOMAINS = listOf("gmail.com", "outlook.com", "hotmail.com", "yahoo.com", "icloud.com")

        fun looksLikeEmail(s: String) = s.length <= MAX_LENGTH && EMAIL.matches(s)

        /**
         * A single token that could be a sign-in name: no spaces, 3+ characters, at least one
         * letter. Digits only are skipped: those are codes and phone numbers.
         */
        fun looksLikeUsername(s: String) =
            s.length in 3..MAX_LENGTH && s.none { it.isWhitespace() } && s.any { it.isLetter() }

        fun acceptable(s: String) = looksLikeEmail(s) || looksLikeUsername(s)

        @Volatile private var instance: LoginStore? = null

        fun get(context: Context): LoginStore =
            instance ?: synchronized(this) { instance ?: LoginStore(context.applicationContext).also { instance = it } }
    }
}
