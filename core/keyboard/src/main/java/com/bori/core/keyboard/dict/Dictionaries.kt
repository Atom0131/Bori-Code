package com.bori.core.keyboard.dict

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.bori.core.keyboard.layout.Language
import java.util.concurrent.Executors

/**
 * Loads bundled dictionaries off the main thread and keeps the two most recently used
 * languages in memory (about 6 MB each), so switching languages is instant.
 */
class Dictionaries(context: Context) {
    private val appContext = context.applicationContext
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val loaded = LinkedHashMap<Language, BinaryDictionary>()

    /** Calls [onReady] on the main thread with the dictionary, or null if it failed to load. */
    fun get(lang: Language, onReady: (BinaryDictionary?) -> Unit) {
        synchronized(loaded) { loaded[lang] }?.let { onReady(it); return }
        io.execute {
            val dict = synchronized(loaded) { loaded[lang] } ?: runCatching {
                BinaryDictionary.fromAsset(appContext, "dict/${lang.tag}.bdic")
            }.onFailure { Log.e(TAG, "Failed to load ${lang.tag} dictionary", it) }.getOrNull()
            if (dict != null) {
                synchronized(loaded) {
                    loaded.remove(lang)
                    loaded[lang] = dict
                    while (loaded.size > 2) loaded.remove(loaded.keys.first())
                }
            }
            main.post { onReady(dict) }
        }
    }

    /** Runs [block] on the dictionary thread (used to load user data alongside). */
    fun background(block: () -> Unit) = io.execute(block)

    private companion object {
        const val TAG = "BoriDict"
    }
}
