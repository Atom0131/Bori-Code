package com.bori.core.keyboard.layout

enum class LayoutId {
    /** Letters for the active language (resolved by [Layouts.build]). */
    ALPHA,
    SYMBOLS,
    SYMBOLS_MORE,
    NUMBER,
    PHONE,
}

/** Field-specific tweaks to the alphabet and symbol bottom rows. */
enum class FieldVariant { TEXT, EMAIL, URL }
