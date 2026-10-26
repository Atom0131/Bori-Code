package com.bori.app.terminal

import android.content.Context
import androidx.compose.ui.geometry.Offset
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Word suggestions and swipe decoding for the terminal keyboard. Words come from four places:
 * shell words (commands, git/npm subcommands), words on the terminal screen (file names from `ls`,
 * errors), words the user has used before, and an English list (SCOWL, assets/keyboard).
 *
 * Only a word the terminal echoed (it's on the cursor's line when the word ends) is learned, so a
 * password typed at a prompt, which isn't echoed, is never stored.
 */
@Singleton
class WordPredictor @Inject constructor(@ApplicationContext private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val learnedFile = File(context.filesDir, "keyboard_learned.txt")

    /**
     * [words] sorted for prefix search, each with its cost (lower is more common), and the letters-only
     * ones by first and last letter, for swipes. Published as one object so a reader never sees half.
     */
    private class Dictionary(val words: Array<String>, val costs: FloatArray, val swipeIndex: Map<Int, IntArray>)

    @Volatile private var dict = Dictionary(emptyArray(), FloatArray(0), emptyMap())
    private val learned = ConcurrentHashMap<String, Int>()
    // Until the saved words are read, learning would save over them.
    @Volatile private var loaded = false
    private var saveJob: Job? = null

    init {
        scope.launch { load() }
    }

    private fun load() {
        val cost = HashMap<String, Float>()
        var level = 0f
        context.assets.open("keyboard/words_en.txt").bufferedReader().useLines { lines ->
            lines.forEach { line ->
                when {
                    line.startsWith("# ") -> Unit
                    line.startsWith("#") -> level += 1f
                    line.isNotEmpty() -> cost.putIfAbsent(line, level)
                }
            }
        }
        SHELL_WORDS.forEach { cost[it] = 0.5f }
        if (learnedFile.exists()) {
            learnedFile.readLines().forEach { line ->
                val (w, n) = line.split('\t').takeIf { it.size == 2 } ?: return@forEach
                n.toIntOrNull()?.let { learned.merge(w, it, Int::plus) }
            }
        }
        learned.keys.forEach { cost.putIfAbsent(it, 4f) }
        val sorted = cost.keys.sorted().toTypedArray()
        val sortedCosts = FloatArray(sorted.size) { cost.getValue(sorted[it]) }
        val index = HashMap<Int, MutableList<Int>>()
        sorted.forEachIndexed { i, w ->
            if (w.length >= 2 && w.all { it in 'a'..'z' }) index.getOrPut(pairKey(w.first(), w.last())) { mutableListOf() }.add(i)
        }
        dict = Dictionary(sorted, sortedCosts, index.mapValues { it.value.toIntArray() })
        loaded = true
    }

    private fun pairKey(first: Char, last: Char) = first.code * 128 + last.code

    private fun score(d: Dictionary, i: Int): Float {
        val n = learned[d.words[i]] ?: 0
        return d.costs[i] - 1.5f * ln(1f + n)
    }

    private fun learnedScore(n: Int) = 4f - 1.5f * ln(1f + n)

    /**
     * Up to [limit] completions of [prefix] (the word being typed), screen words first when they
     * fit. A capitalized prefix gets capitalized suggestions.
     */
    fun suggest(prefix: String, screen: Set<String>, limit: Int = 3): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val lower = prefix.lowercase()
        val ranked = HashMap<String, Float>()
        screen.forEach { w -> if (w.length > prefix.length && w.startsWith(prefix, ignoreCase = true)) ranked[w] = -1f - 0.01f * w.length }
        val d = dict
        val all = d.words
        var i = lowerBound(all, lower)
        var scanned = 0
        while (i < all.size && all[i].startsWith(lower) && scanned < 4000) {
            val w = all[i]
            if (w.length > lower.length) {
                val shown = matchCase(w, prefix)
                if (shown !in ranked) ranked[shown] = score(d, i) + 0.05f * (w.length - lower.length)
            }
            i++
            scanned++
        }
        // Words learned since the list was built (file names typed this session).
        learned.forEach { (w, n) ->
            if (w.length > lower.length && w.startsWith(lower)) {
                val shown = matchCase(w, prefix)
                if (shown !in ranked) ranked[shown] = learnedScore(n) + 0.05f * (w.length - lower.length)
            }
        }
        return ranked.entries.sortedBy { it.value }.take(limit).map { it.key }
    }

    /** Remembers [word] if the terminal [echoed] it (see the class comment). */
    fun learn(word: String, echoed: Boolean) {
        if (!loaded || !echoed || word.length < 2 || word.length > 40) return
        val lower = word.lowercase()
        learned.merge(lower, 1, Int::plus)
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(2000)
            val text = learned.entries.sortedByDescending { it.value }.take(2000).joinToString("\n") { "${it.key}\t${it.value}" }
            learnedFile.writeText(text)
        }
    }

    /**
     * The likeliest words for a swipe [path] over keys centered at [centers], [keyWidth] wide,
     * best first. Compares the path with each candidate's ideal path through its letters.
     */
    fun decodeSwipe(path: List<Offset>, centers: Map<Char, Offset>, keyWidth: Float, limit: Int = 4): List<String> {
        if (path.size < 2 || centers.isEmpty() || keyWidth <= 0f) return emptyList()
        val starts = nearest(centers, path.first(), 2, keyWidth * 1.2f)
        val ends = nearest(centers, path.last(), 3, keyWidth * 1.4f)
        val gesture = resample(path, SAMPLES)
        val d = dict
        val all = d.words
        val results = ArrayList<Pair<String, Float>>()
        for (s in starts) for (e in ends) {
            val ids = d.swipeIndex[pairKey(s, e)] ?: continue
            for (i in ids) {
                val w = all[i]
                val ideal = idealPath(w, centers) ?: continue
                // Every letter has to be near the path.
                if (ideal.any { c -> path.minOf { (it - c).getDistanceSquared() } > keyWidth * keyWidth * 1.3f }) continue
                val shape = resample(ideal, SAMPLES)
                var dist = 0f
                for (k in 0 until SAMPLES) dist += (gesture[k] - shape[k]).getDistance()
                results += w to (dist / SAMPLES / keyWidth) * 3f + 0.25f * score(d, i)
            }
        }
        return results.sortedBy { it.second }.take(limit).map { it.first }
    }

    private fun idealPath(word: String, centers: Map<Char, Offset>): List<Offset>? {
        val points = ArrayList<Offset>(word.length)
        for (c in word) {
            val p = centers[c] ?: return null
            if (points.lastOrNull() != p) points += p
        }
        // A one-key word ("aa") is a tap, not a swipe.
        return points.takeIf { it.size >= 2 }
    }

    private fun nearest(centers: Map<Char, Offset>, p: Offset, n: Int, within: Float): List<Char> {
        val sorted = centers.entries.sortedBy { (it.value - p).getDistanceSquared() }
        return sorted.take(n).filterIndexed { i, e -> i == 0 || (e.value - p).getDistance() <= within }.map { it.key }
    }

    private fun lowerBound(a: Array<String>, key: String): Int {
        var lo = 0
        var hi = a.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (a[mid] < key) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun matchCase(word: String, typed: String): String = when {
        typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
        typed.first().isUpperCase() -> word.replaceFirstChar { it.uppercase() }
        else -> word
    }

    private companion object {
        const val SAMPLES = 24

        /** Points evenly spaced along [points]. */
        fun resample(points: List<Offset>, n: Int): List<Offset> {
            var total = 0f
            for (i in 1 until points.size) total += (points[i] - points[i - 1]).getDistance()
            if (total == 0f) return List(n) { points.first() }
            val step = total / (n - 1)
            val out = ArrayList<Offset>(n)
            out += points.first()
            var carried = 0f
            var prev = points.first()
            var i = 1
            while (i < points.size && out.size < n) {
                val next = points[i]
                val seg = (next - prev).getDistance()
                if (carried + seg >= step && seg > 0f) {
                    val t = (step - carried) / seg
                    val p = prev + (next - prev) * t
                    out += p
                    prev = p
                    carried = 0f
                } else {
                    carried += seg
                    prev = next
                    i++
                }
            }
            while (out.size < n) out += points.last()
            return out
        }

        /** Commands and words that come up in a shell, ranked just after the commonest English. */
        val SHELL_WORDS = listOf(
            "ls", "cd", "pwd", "cat", "less", "more", "grep", "find", "sed", "awk", "head", "tail", "sort", "uniq",
            "wc", "diff", "echo", "export", "source", "exit", "clear", "history", "man", "which", "whoami",
            "mkdir", "rmdir", "rm", "cp", "mv", "touch", "chmod", "chown", "ln", "tar", "unzip", "zip", "gzip",
            "curl", "wget", "ssh", "scp", "ping", "ps", "kill", "top", "htop", "df", "du", "free", "env", "sudo",
            "nano", "vim", "vi", "git", "status", "add", "commit", "push", "pull", "fetch", "log", "checkout",
            "branch", "merge", "rebase", "clone", "init", "stash", "reset", "restore", "switch", "remote", "tag",
            "npm", "npx", "node", "install", "uninstall", "run", "test", "start", "build", "update", "upgrade",
            "python", "python3", "pip", "pip3", "venv", "claude", "antigravity", "apk", "xbps-install",
            "xbps-query", "xbps-remove", "gradle", "gradlew", "java", "kotlin", "make", "cmake", "cargo", "go",
            "--help", "--version", "--force", "--global", "-la", "-rf",
            "workspace", "projects", "localhost", "true", "false", "null",
        )
    }
}
