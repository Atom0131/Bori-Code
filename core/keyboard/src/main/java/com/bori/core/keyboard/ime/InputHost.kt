package com.bori.core.keyboard.ime

import android.content.Context
import android.view.inputmethod.InputConnection

/**
 * Where [TypingSession] types. In Bori Keyboard that's the input method service; in Bori Code it's
 * the terminal's type bar. Bori Code's own file: `scripts/sync_bori_keyboard.sh` keeps it.
 */
interface InputHost {
    val context: Context
    val currentInputConnection: InputConnection?
    fun sendDownUpKeyEvents(keyCode: Int)
    fun sendKeyChar(c: Char)
    fun toggleClipboard()
    fun openSettings()
}
