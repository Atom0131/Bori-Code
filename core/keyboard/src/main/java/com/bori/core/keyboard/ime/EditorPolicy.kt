package com.bori.core.keyboard.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.bori.core.keyboard.layout.FieldVariant
import com.bori.core.keyboard.layout.LayoutId

/** What the current text field allows, derived once per field from its EditorInfo. */
data class EditorPolicy(
    val initialLayout: LayoutId,
    val variant: FieldVariant,
    val isPassword: Boolean,
    /** Suggestions, autocorrect and clipboard history stay off. */
    val noSuggestions: Boolean,
    /**
     * The app set TYPE_TEXT_FLAG_NO_SUGGESTIONS on a normal text field. The Google search box
     * does, and so do many search boxes. Gboard still glides and corrects there, so Bori
     * ignores the flag unless Settings → "Follow apps that turn suggestions off" is on.
     */
    val appNoSuggestions: Boolean,
    /** Nothing typed here is learned (passwords, incognito requests). */
    val noLearning: Boolean,
    val autoCapAllowed: Boolean,
    /** IME action for the enter key, or null when enter should insert a newline. */
    val enterAction: Int?,
) {
    companion object {
        val DEFAULT = EditorPolicy(LayoutId.ALPHA, FieldVariant.TEXT, false, false, false, false, true, null)

        fun from(info: EditorInfo?): EditorPolicy {
            if (info == null) return DEFAULT
            val type = info.inputType
            val cls = type and InputType.TYPE_MASK_CLASS
            val variation = type and InputType.TYPE_MASK_VARIATION
            val flags = type and InputType.TYPE_MASK_FLAGS

            val textPassword = cls == InputType.TYPE_CLASS_TEXT && variation in setOf(
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            )
            val numberPassword = cls == InputType.TYPE_CLASS_NUMBER &&
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            val password = textPassword || numberPassword

            val layout = when (cls) {
                InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_DATETIME -> LayoutId.NUMBER
                InputType.TYPE_CLASS_PHONE -> LayoutId.PHONE
                else -> LayoutId.ALPHA
            }
            val variant = when {
                cls != InputType.TYPE_CLASS_TEXT -> FieldVariant.TEXT
                variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> FieldVariant.EMAIL
                variation == InputType.TYPE_TEXT_VARIATION_URI -> FieldVariant.URL
                else -> FieldVariant.TEXT
            }
            // Filter fields (YouTube's search box is one) get suggestions and a composing word
            // like any text field, as Gboard and Samsung Keyboard do. With letters committed one
            // by one instead, YouTube's big-screen search box took the text but never drew it.
            val noSuggest = password || cls != InputType.TYPE_CLASS_TEXT ||
                variant != FieldVariant.TEXT
            val appNoSuggest = (flags and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0
            val noLearning = password ||
                (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0

            val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
            val multiLine = (flags and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
            val noEnterAction = (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
            val enterAction = when {
                noEnterAction || multiLine -> null
                action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED -> null
                else -> action
            }

            return EditorPolicy(
                initialLayout = layout,
                variant = variant,
                isPassword = password,
                noSuggestions = noSuggest,
                appNoSuggestions = appNoSuggest,
                noLearning = noLearning,
                autoCapAllowed = !password && variant == FieldVariant.TEXT && cls == InputType.TYPE_CLASS_TEXT,
                enterAction = enterAction,
            )
        }

        fun enterLabel(action: Int?): String = when (action) {
            EditorInfo.IME_ACTION_GO -> "Go"
            EditorInfo.IME_ACTION_SEARCH -> "⌕"
            EditorInfo.IME_ACTION_SEND -> "Send"
            EditorInfo.IME_ACTION_NEXT -> "Next"
            EditorInfo.IME_ACTION_PREVIOUS -> "Prev"
            EditorInfo.IME_ACTION_DONE -> "Done"
            else -> "↵"
        }
    }
}
