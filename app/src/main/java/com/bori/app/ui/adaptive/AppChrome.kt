package com.bori.app.ui.adaptive

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * App-wide chrome state. [fullScreen] is set by the Terminal while it fills the screen; the
 * shell then hides the bottom bar or rail. [terminalKeys] is set while the Terminal's own keyboard
 * is open; the bottom bar steps aside for it, as it does for the phone's keyboard.
 */
class AppChrome(
    val fullScreen: MutableState<Boolean> = mutableStateOf(false),
    val terminalKeys: MutableState<Boolean> = mutableStateOf(false),
)

val LocalAppChrome = staticCompositionLocalOf { AppChrome() }
