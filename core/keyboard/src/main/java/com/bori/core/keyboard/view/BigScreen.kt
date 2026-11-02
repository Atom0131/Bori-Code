package com.bori.core.keyboard.view

import android.content.Context

/** Unfolded foldables and tablets, where a full-width keyboard is too wide for the thumbs. */
object BigScreen {
    /** Smallest screen width (dp) that counts as big. Phones in landscape and cover screens don't. */
    const val MIN_DP = 600

    fun isBig(context: Context): Boolean =
        context.resources.configuration.smallestScreenWidthDp >= MIN_DP
}
