package com.bori.app.terminal

import android.content.Context
import com.bori.core.keyboard.dict.BinaryDictionary
import com.bori.core.keyboard.dict.Dictionaries
import com.bori.core.keyboard.layout.Language
import com.bori.core.keyboard.learn.UserStore
import com.bori.core.keyboard.predict.Suggester
import com.bori.core.keyboard.swipe.GlideDecoder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The terminal keyboard's language data: Bori Keyboard's dictionary, its spell corrector and glide
 * decoder, and what it has learned from the user (in its own `terminal_words.db`). Loaded off the
 * main thread the first time the keys open; [engine] is null until then.
 */
@Singleton
class TerminalWords @Inject constructor(@ApplicationContext private val context: Context) {

    class Engine(
        val language: Language,
        val dict: BinaryDictionary,
        val suggester: Suggester,
        val decoder: GlideDecoder,
        val user: UserStore,
    )

    private val dictionaries by lazy { Dictionaries(context) }
    private val _engine = MutableStateFlow<Engine?>(null)
    val engine: StateFlow<Engine?> = _engine.asStateFlow()
    private var loading: Language? = null

    /** Loads [language] unless it's loaded or loading already. Main thread. */
    fun load(language: Language) {
        if (_engine.value?.language == language || loading == language) return
        loading = language
        val user = UserStore.get(context)
        dictionaries.background { user.load(language.tag) }
        dictionaries.get(language) { dict ->
            if (loading != language) return@get
            loading = null
            dict ?: return@get
            _engine.value = Engine(language, dict, Suggester(dict, user, language.locale), GlideDecoder(dict), user)
        }
    }
}
