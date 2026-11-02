package com.bori.core.keyboard.dict

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.bori.core.keyboard.ime.DiagLog
import com.bori.core.keyboard.layout.Language
import java.util.concurrent.Executors

/**
 * Loads bundled dictionaries off the main thread and keeps the two most recently used
 * languages in memory (about 19 MB each since the Wiktionary words), so switching is instant.
 */
class Dictionaries(context: Context, private val maxLanguages: Int = 2) {
    private val appContext = context.applicationContext
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val loaded = LinkedHashMap<Language, BinaryDictionary>()

    /** Calls [onReady] on the main thread with the dictionary, or null if it failed to load. */
    fun get(lang: Language, onReady: (BinaryDictionary?) -> Unit) {
        synchronized(loaded) { loaded[lang] }?.let { onReady(it); return }
        io.execute {
            val dict = synchronized(loaded) { loaded[lang] } ?: load(lang)
            main.post { onReady(dict) }
        }
    }

    /**
     * Loads [lang], freeing the other cached language and retrying once if memory runs out.
     * Failures are written to the diagnostics log (Settings → Copy diagnostics).
     */
    private fun load(lang: Language): BinaryDictionary? {
        for (attempt in 0..1) {
            val t0 = SystemClock.uptimeMillis()
            try {
                val dict = BinaryDictionary.fromAsset(appContext, "dict/${lang.tag}.bdic")
                dict.typos = BinaryDictionary.typosFromAsset(appContext, "dict/${lang.tag}_typos.txt")
                DiagLog.event(
                    "dictionary ${lang.tag} read in ${SystemClock.uptimeMillis() - t0} ms, " +
                        "${dict.typos.size} typos, heap ${heapMb()}",
                )
                synchronized(loaded) {
                    loaded.remove(lang)
                    loaded[lang] = dict
                    while (loaded.size > maxLanguages) loaded.remove(loaded.keys.first())
                }
                return dict
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "Out of memory loading ${lang.tag} dictionary", e)
                DiagLog.event("dictionary ${lang.tag} OUT OF MEMORY (attempt ${attempt + 1}), heap ${heapMb()}")
                synchronized(loaded) { loaded.clear() }
                System.gc()
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to load ${lang.tag} dictionary", e)
                DiagLog.event("dictionary ${lang.tag} load error: $e")
                return null
            }
        }
        return null
    }

    private fun heapMb(): String {
        val rt = Runtime.getRuntime()
        val used = (rt.totalMemory() - rt.freeMemory()) shr 20
        return "${used}/${rt.maxMemory() shr 20} MB"
    }

    /** Runs [block] on the dictionary thread (used to load user data alongside). */
    fun background(block: () -> Unit) = io.execute(block)

    private companion object {
        const val TAG = "BoriDict"
    }
}
