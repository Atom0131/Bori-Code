package com.bori.app.flavor

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Play build: no stats panel over other apps (it needs "Display over other apps"); the in-app one stays. */
object FloatingStats {
    const val AVAILABLE = false
    val running: StateFlow<Boolean> = MutableStateFlow(false)

    @Suppress("UNUSED_PARAMETER")
    fun canDraw(context: Context): Boolean = false

    @Suppress("UNUSED_PARAMETER")
    fun permissionIntent(context: Context): android.content.Intent? = null

    @Suppress("UNUSED_PARAMETER")
    fun sync(context: Context) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun hideIntent(context: Context): android.app.PendingIntent? = null
}
