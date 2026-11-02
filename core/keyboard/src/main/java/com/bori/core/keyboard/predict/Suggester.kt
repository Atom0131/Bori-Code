package com.bori.core.keyboard.predict

import com.bori.core.keyboard.dict.BinaryDictionary
import com.bori.core.keyboard.learn.UserStore
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln

enum class Aggressiveness { OFF, MILD, NORMAL, AGGRESSIVE }

class Suggestion(
    /** Text to commit, already in the capitalisation the user typed. */
    val word: String,
    val score: Float,
    val kind: Kind,
    val cost: Float = 0f,
    /** Dictionary node, or -1 (learned words, multi-word splits). */
    val node: Int = -1,
) {
    enum class Kind { TYPED, EXACT, CORRECTION, COMPLETION, PREDICTION }
}

class SuggestResult(
    val typed: String,
    val typedValid: Boolean,
    /** Best first; never contains the typed literal. */
    val ranked: List<Suggestion>,
    /** What space/punctuation will commit instead of [typed], if anything. */
    val autocorrect: Suggestion?,
)

/**
 * Ranks words for the suggestion strip: candidates from [SpellCorrector] (corrections and
 * completions) and the user's learned words, scored by the language model
 *   score = ln P(word | previous word) + user boosts − COST_WEIGHT × edit cost
 * and decides whether the word typed should be autocorrected. Constants are mirrored in
 * tools/suggest_sim.py.
 */
class Suggester(
    private val dict: BinaryDictionary,
    private val user: UserStore,
    private val locale: Locale,
) {
    private val corrector = SpellCorrector(dict)
    var blockOffensive = true

    /** Context for prediction: the word before the cursor, or the start of a sentence. */
    class Context(val prevWord: String?, val sentenceStart: Boolean) {
        companion object {
            val NONE = Context(null, false)
        }
    }

    fun suggest(composer: Composer, ctx: Context, prox: KeyProximity?, level: Aggressiveness): SuggestResult {
        val typed = composer.typed
        val key = typed.lowercase(locale)
        val m = key.length
        val prevNode = prevNodeOf(ctx)
        corrector.search(key, composer, prox)

        val byKey = HashMap<String, Suggestion>()
        fun offer(word: String, score: Float, kind: Suggestion.Kind, cost: Float, node: Int = -1) {
            val k = word.lowercase(locale)
            if (user.isBlocked(k)) return
            val old = byKey[k]
            if (old == null || score > old.score) byKey[k] = Suggestion(composer.applyCase(word, locale), score, kind, cost, node)
        }

        for ((node, cost) in corrector.matches) {
            if (blockOffensive && dict.isOffensive(node)) continue
            val kind = if (cost == 0f) Suggestion.Kind.EXACT else Suggestion.Kind.CORRECTION
            offer(dict.wordOf(node), lm(node, prevNode, ctx) - COST_WEIGHT * cost, kind, cost, node)
        }
        val prefixList = corrector.prefixes.entries.sortedBy { it.value }.take(MAX_PREFIXES)
        for ((pnode, pcost) in prefixList) {
            corrector.topDescendants(pnode, dict.depthOf(pnode), COMPLETIONS_PER_PREFIX, m) { node, depth ->
                val score = lm(node, prevNode, ctx) - COST_WEIGHT * pcost - COMPLETION_PENALTY -
                    COMPLETION_PER_CHAR * (depth - m)
                val word = dict.wordOf(node)
                if (!byKey.containsKey(word.lowercase(locale))) offer(word, score, Suggestion.Kind.COMPLETION, pcost, node)
            }
        }
        // Learned words the dictionary doesn't know (names, slang, jargon).
        corrector.prepare(key, composer, prox)
        for (w in user.knownWords()) {
            if (dict.findWord(w) >= 0) continue
            val boost = learnedLm(w, ctx)
            if (w.length > m && w.startsWith(key)) {
                offer(w, boost - COMPLETION_PENALTY - COMPLETION_PER_CHAR * (w.length - m), Suggestion.Kind.COMPLETION, 0f)
            } else if (kotlin.math.abs(w.length - m) <= 2) {
                val cost = corrector.costTo(w)
                if (cost <= SpellCorrector.maxCost(m)) {
                    offer(w, boost - COST_WEIGHT * cost, if (cost == 0f) Suggestion.Kind.EXACT else Suggestion.Kind.CORRECTION, cost)
                }
            }
        }

        val dictNode = dict.findWord(key)
        val learned = user.isLearned(key)
        val typedValid = dictNode >= 0 || learned || isShouting(typed)
        var ranked = byKey.values.sortedByDescending { it.score }
        var auto = chooseAutocorrect(composer, key, typedValid, ranked, level, ctx, prevNode, dictNode)
        if (!typedValid && level != Aggressiveness.OFF && !composer.autocorrectSuppressed) {
            // Missed space ("troublwithe" → "trouble with the") when it reads better than any
            // single word.
            val split = bestSplit(key, composer, prox, prevNode, ctx)
            val single = ranked.firstOrNull { it.kind != Suggestion.Kind.COMPLETION }?.score ?: Float.NEGATIVE_INFINITY
            if (split != null && split.score > single + SPLIT_MARGIN) {
                auto = split
                ranked = listOf(split) + ranked
            }
        }
        return SuggestResult(typed, typedValid, ranked.take(MAX_RESULTS), auto)
    }

    private fun chooseAutocorrect(
        composer: Composer,
        key: String,
        typedValid: Boolean,
        ranked: List<Suggestion>,
        level: Aggressiveness,
        ctx: Context,
        prevNode: Int,
        dictNode: Int,
    ): Suggestion? {
        if (level == Aggressiveness.OFF || composer.autocorrectSuppressed) return null
        if (typedValid) {
            // Re-case from the dictionary ("i" → "I", "spain" → "Spain").
            val exact = ranked.firstOrNull { it.kind == Suggestion.Kind.EXACT }
            if (exact != null && exact.word != composer.typed && exact.word.lowercase(locale) == key) return exact
            // Your own words and ALL-CAPS typing are never "fixed". Merely having typed a
            // dictionary word before doesn't count: earlier slips ("ans" for "and") were
            // learned too. Undoing the same fix twice does.
            if (dictNode < 0 || isShouting(composer.typed)) return null
            if (user.isPersonal(key) || user.keptCount(key) >= UserStore.KEEP_AFTER) return null
            if (dict.keepsAsTyped(dictNode)) return null
            return realWordFix(key, dictNode, prevNode, ctx, ranked, level)
        }
        // Only the best-ranked correction may be applied; never fall back to a worse one.
        val best = ranked.firstOrNull { it.kind != Suggestion.Kind.COMPLETION } ?: return null
        val m = key.length
        val lower = best.word.lowercase(locale)
        // "hel" → "he" just drops what the user typed last; they were probably mid-word.
        if (m <= 4 && key.startsWith(lower)) return null
        return best.takeIf { it.cost <= autoCost(m, level) }
    }

    /**
     * A real word that is probably a slip: "ans" → "and", "I cant" → "I can't". Only near-key
     * swaps, swapped letters or an added apostrophe count (never dropping or adding letters,
     * so plurals like "reasons" are safe). Rare look-alikes need a small margin; common words
     * (its/it's, were/we're) also need the previous word to support the fix.
     */
    private fun realWordFix(
        key: String,
        dictNode: Int,
        prevNode: Int,
        ctx: Context,
        ranked: List<Suggestion>,
        level: Aggressiveness,
    ): Suggestion? {
        val letters = key.count { it != '\'' }
        val apostrophes = key.length - letters
        val alt = ranked.firstOrNull { s ->
            if (s.kind == Suggestion.Kind.COMPLETION || s.node < 0 || s.node == dictNode) return@firstOrNull false
            val w = s.word.lowercase(locale)
            val wl = w.count { it != '\'' }
            w != key && wl == letters && w.length - wl >= apostrophes
        } ?: return null
        if (alt.cost > REALFIX_MAX_COST) return null
        val rare = dict.freqOf(dictNode) < REALFIX_RARE_FREQ
        if (!rare && (prevNode < 0 || dict.bigramLogP(prevNode, alt.node) == null)) return null
        val margin = (if (rare) REALFIX_MARGIN_RARE else REALFIX_MARGIN_COMMON) / levelFactor(level)
        // The typed word is judged without the user's boosts, which past slips inflate.
        return alt.takeIf { it.score - lm(dictNode, prevNode, ctx, withUser = false) >= margin }
    }

    // ---------------------------------------------------------------- missed spaces

    private class Piece(val node: Int, val cost: Float)
    private class Split(val nodes: List<Int>, val cost: Float, val score: Float)

    /** Best reading of [key] as 2–3 words, as a suggestion, or null. */
    private fun bestSplit(key: String, composer: Composer, prox: KeyProximity?, prevNode: Int, ctx: Context): Suggestion? {
        if (key.length < SPLIT_MIN_LENGTH) return null
        val cache = HashMap<String, Piece?>()
        val split = splitFrom(key, 0, key.length, prevNode, ctx, composer, prox, cache) ?: return null
        val text = split.nodes.joinToString(" ") { dict.wordOf(it) }
        return Suggestion(composer.applyCase(text, locale), split.score, Suggestion.Kind.CORRECTION, split.cost)
    }

    private fun splitFrom(
        key: String, start: Int, end: Int, prevNode: Int, ctx: Context,
        composer: Composer, prox: KeyProximity?, cache: HashMap<String, Piece?>,
    ): Split? {
        val m = end - start
        if (m < SPLIT_MIN_LENGTH) return null
        var best: Split? = null
        for (i in start + 1 until end) {
            // A missing space before i, or a letter next to the spacebar typed instead of one.
            val cuts = if (key[i] in SPACE_KEYS && i > start && i < end - 1) 2 else 1
            for (c in 0 until cuts) {
                val rightStart = if (c == 0) i else i + 1
                val extra = if (c == 0) 0f else SPACE_KEY_COST
                val p1 = piece(key, start, i, prevNode, composer, prox, cache) ?: continue
                val ctx1 = Context(dict.wordOf(p1.node), sentenceStart = false)
                val lm1 = lm(p1.node, prevNode, ctx)
                piece(key, rightStart, end, p1.node, composer, prox, cache)?.let { p2 ->
                    val cost = p1.cost + p2.cost + extra
                    val score = lm1 + lm(p2.node, p1.node, ctx1) - COST_WEIGHT * cost - SPLIT_PENALTY
                    if (score > (best?.score ?: Float.NEGATIVE_INFINITY)) best = Split(listOf(p1.node, p2.node), cost, score)
                }
                if (m >= SPLIT_THREE_MIN_LENGTH && end - rightStart >= 3) {
                    splitFrom(key, rightStart, end, p1.node, ctx1, composer, prox, cache)?.let { rest ->
                        val cost = p1.cost + extra + rest.cost
                        val score = lm1 - COST_WEIGHT * (p1.cost + extra) + rest.score - SPLIT_PENALTY
                        if (score > (best?.score ?: Float.NEGATIVE_INFINITY)) best = Split(listOf(p1.node) + rest.nodes, cost, score)
                    }
                }
            }
        }
        return best
    }

    /** The best ordinary, common word for key[start, end): exact, or a cheap fix. */
    private fun piece(
        key: String, start: Int, end: Int, prevNode: Int,
        composer: Composer, prox: KeyProximity?, cache: HashMap<String, Piece?>,
    ): Piece? {
        val cacheKey = "$start:$end:$prevNode"
        if (cache.containsKey(cacheKey)) return cache[cacheKey]
        val sub = key.substring(start, end)
        fun usable(node: Int): Boolean =
            dict.freqOf(node) >= (if (dict.keyOf(node).length == 2) SPLIT_SHORT_MIN_FREQ else SPLIT_MIN_FREQ) &&
                !dict.isOffensive(node) && dict.isPlainWord(node) &&
                !user.isBlocked(dict.keyOf(node)) &&
                (sub.length > 1 || dict.keyOf(node) in singleLetterWords)
        var out: Piece? = null
        val exact = dict.findWord(sub)
        if (exact >= 0 && usable(exact)) {
            out = Piece(exact, 0f)
        } else if (sub.length >= 3) {
            corrector.search(sub, composer, prox, SPLIT_PIECE_MAX_COST, start)
            var bestScore = Float.NEGATIVE_INFINITY
            for ((node, cost) in corrector.matches) {
                if (!usable(node)) continue
                val sc = lm(node, prevNode, Context.NONE) - COST_WEIGHT * cost
                if (sc > bestScore) {
                    bestScore = sc
                    out = Piece(node, cost)
                }
            }
        }
        cache[cacheKey] = out
        return out
    }

    private val singleLetterWords: Set<String> =
        if (locale.language == "es") setOf("a", "y", "o", "e", "u") else setOf("a", "i")

    /** Next-word predictions for an empty composer. */
    fun predict(ctx: Context, capitalize: Boolean, limit: Int = 3): List<Suggestion> {
        val prevNode = prevNodeOf(ctx)
        val byKey = HashMap<String, Suggestion>()
        fun offer(word: String, score: Float) {
            val k = word.lowercase(locale)
            if (user.isBlocked(k) || byKey[k]?.let { it.score >= score } == true) return
            val shown = if (capitalize) word.replaceFirstChar { it.titlecase(locale) } else word
            byKey[k] = Suggestion(shown, score, Suggestion.Kind.PREDICTION)
        }
        if (prevNode >= 0) {
            dict.forEachNext(prevNode) { node, logP ->
                if (!(blockOffensive && dict.isOffensive(node))) {
                    offer(dict.wordOf(node), logP + userBoost(dict.keyOf(node), ctx))
                }
            }
        }
        ctx.prevWord?.let { prev ->
            for ((w, count) in user.nextWords(prev, limit * 2)) {
                val node = dict.findWord(w)
                val word = if (node >= 0) dict.wordOf(node) else w
                val base = if (node >= 0) dict.bigramLogP(prevNode, node) ?: dict.unigramLogP(node) else LEARNED_BASE
                offer(word, base + userBoost(w, ctx) + 0.5f * ln(1f + count))
            }
        }
        return byKey.values.sortedByDescending { it.score }.take(limit)
    }

    // ---------------------------------------------------------------- language model

    /** ln P(word | context) plus user boosts, for ranking glide candidates. */
    fun languageScore(node: Int, ctx: Context): Float = lm(node, prevNodeOf(ctx), ctx)

    fun isBlockedWord(node: Int): Boolean =
        (blockOffensive && dict.isOffensive(node)) || user.isBlocked(dict.keyOf(node))

    private fun prevNodeOf(ctx: Context): Int = when {
        ctx.sentenceStart -> BinaryDictionary.ROOT
        ctx.prevWord != null -> dict.findWord(ctx.prevWord.lowercase(locale))
        else -> -1
    }

    private fun lm(node: Int, prevNode: Int, ctx: Context, withUser: Boolean = true): Float {
        val pu = exp(dict.unigramLogP(node))
        val base = if (prevNode < 0) {
            ln(pu)
        } else {
            val pb = dict.bigramLogP(prevNode, node)
            if (pb != null) ln(BIGRAM_WEIGHT * exp(pb) + (1 - BIGRAM_WEIGHT) * pu) else ln(BIGRAM_MISSING * pu)
        }
        return if (withUser) base + userBoost(dict.keyOf(node), ctx) else base
    }

    private fun learnedLm(word: String, ctx: Context): Float = LEARNED_BASE + userBoost(word, ctx)

    private fun userBoost(key: String, ctx: Context): Float {
        var b = 0f
        val c = user.countOf(key)
        if (c > 0) b += USER_WORD_BOOST * ln(1f + c)
        val prev = ctx.prevWord
        if (prev != null) {
            val pc = user.pairCount(prev, key)
            if (pc > 0) b += USER_PAIR_BOOST * ln(1f + pc)
        }
        return b
    }

    private fun isShouting(typed: String) = typed.length > 1 && typed.none { it.isLowerCase() }

    companion object {
        const val COST_WEIGHT = 4.5f
        const val COMPLETION_PENALTY = 1.5f
        const val COMPLETION_PER_CHAR = 0.1f
        const val BIGRAM_WEIGHT = 0.65f
        /** Unigram factor when the previous word has no stored pair (pairs are pruned, so a
         *  missing one is weak evidence; 1 - BIGRAM_WEIGHT punished "almost there"). */
        const val BIGRAM_MISSING = 0.65f
        const val USER_WORD_BOOST = 0.6f
        const val USER_PAIR_BOOST = 1.0f
        /** ln P for a learned word the dictionary doesn't know, before boosts. */
        const val LEARNED_BASE = -11f
        const val MAX_PREFIXES = 12
        const val COMPLETIONS_PER_PREFIX = 3
        const val MAX_RESULTS = 8

        // Real-word fixes (see realWordFix); tuned with tools/typo_bench.py.
        const val REALFIX_MAX_COST = 1.0f
        const val REALFIX_RARE_FREQ = 170
        const val REALFIX_MARGIN_RARE = 1.0f
        const val REALFIX_MARGIN_COMMON = 2.5f

        // Missed-space splits (see bestSplit).
        const val SPLIT_MIN_LENGTH = 4
        const val SPLIT_THREE_MIN_LENGTH = 8
        const val SPLIT_PENALTY = 2.5f
        const val SPLIT_PIECE_MAX_COST = 0.7f
        const val SPLIT_MIN_FREQ = 140
        /** Two-letter pieces must be really common ("me", "of"), not "er" or "en". */
        const val SPLIT_SHORT_MIN_FREQ = 170
        const val SPLIT_MARGIN = 0f
        const val SPACE_KEYS = "bnvcm"
        const val SPACE_KEY_COST = 0.3f

        private fun levelFactor(level: Aggressiveness): Float = when (level) {
            Aggressiveness.OFF -> 0f
            Aggressiveness.MILD -> 0.65f
            Aggressiveness.NORMAL -> 1f
            Aggressiveness.AGGRESSIVE -> 1.3f
        }

        fun autoCost(n: Int, level: Aggressiveness): Float {
            val normal = when {
                n <= 2 -> 1.0f
                n <= 3 -> 1.0f
                n <= 4 -> 1.3f
                n < 7 -> 1.6f
                // Long words survive two slips and stay unambiguous (tools/typo_bench.py set D).
                else -> 2.0f
            }
            return when (level) {
                Aggressiveness.OFF -> -1f
                Aggressiveness.MILD -> if (n <= 2) 0.5f else normal * 0.65f
                Aggressiveness.NORMAL -> normal
                Aggressiveness.AGGRESSIVE -> normal * 1.3f
            }
        }
    }
}
