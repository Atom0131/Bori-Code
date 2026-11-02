package com.bori.core.keyboard.layout

import java.util.Locale

enum class Language(val tag: String, val displayName: String) {
    EN("en", "English"),
    ES("es", "Español");

    val locale: Locale get() = Locale.forLanguageTag(tag)

    companion object {
        fun fromTag(tag: String?): Language =
            if (tag != null && tag.lowercase(Locale.ROOT).startsWith("es")) ES else EN
    }
}
