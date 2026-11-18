package com.bori.core.keyboard.view

import android.content.Context
import android.content.res.Configuration

/** Unfolded foldables and tablets, where a full-width keyboard is too wide for the thumbs. */
object BigScreen {
    /** Smallest screen width (dp) that counts as big. Phones in landscape and cover screens don't. */
    const val MIN_DP = 600

    fun isBig(context: Context): Boolean =
        context.resources.configuration.smallestScreenWidthDp >= MIN_DP
}

/** Key row height (dp) for a screen height, shared by the keyboard and the calibration page. */
object KeySize {
    /** Default row: 54 dp, less on short screens (landscape, cover screens). */
    fun baseDp(screenHeightDp: Int): Float = minOf(54f, (screenHeightDp * 0.1f).coerceAtLeast(36f))

    /**
     * Tallest row allowed: 8% of the screen (5 rows ≈ 40% of the screen; the user's screenshot
     * at ~10% per row looked far too tall), but always at least 15% over the default so the
     * slider still does something on short screens.
     */
    fun maxDp(screenHeightDp: Int): Float = maxOf(baseDp(screenHeightDp) * 1.15f, screenHeightDp * 0.08f)

    fun rowDp(screenHeightDp: Int, scale: Float): Float =
        minOf(baseDp(screenHeightDp) * scale, maxDp(screenHeightDp))
}

/**
 * How the phone is held: folded (or a normal phone) vs unfolded (or a tablet), upright vs
 * sideways. Each has its own keyboard height, because thumbs reach differently in each.
 */
enum class Posture(val label: String) {
    FOLDED_UPRIGHT("Folded / phone, held upright"),
    FOLDED_SIDEWAYS("Folded / phone, held sideways"),
    OPEN_UPRIGHT("Unfolded / tablet, held upright"),
    OPEN_SIDEWAYS("Unfolded / tablet, held sideways");

    /** Pref name suffix; stable, don't rename. */
    val id: String get() = name.lowercase()

    companion object {
        fun of(c: Configuration): Posture {
            val big = c.smallestScreenWidthDp >= BigScreen.MIN_DP
            val sideways = c.orientation == Configuration.ORIENTATION_LANDSCAPE
            return when {
                big && sideways -> OPEN_SIDEWAYS
                big -> OPEN_UPRIGHT
                sideways -> FOLDED_SIDEWAYS
                else -> FOLDED_UPRIGHT
            }
        }

        fun of(context: Context): Posture = of(context.resources.configuration)
    }
}
