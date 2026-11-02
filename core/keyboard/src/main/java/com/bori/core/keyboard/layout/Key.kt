package com.bori.core.keyboard.layout

/** Visual and behavioural class of a key; drives styling (theme) and touch rules. */
enum class KeyKind { LETTER, FUNCTION, SPACE, ENTER }

/** What a key does when it is activated. */
sealed interface KeyCode {
    data class Text(val text: String) : KeyCode
    data class Switch(val target: LayoutId) : KeyCode
    data object Shift : KeyCode
    data object Delete : KeyCode
    data object Enter : KeyCode
    data object Space : KeyCode
    data object Globe : KeyCode
}

data class Key(
    val label: String,
    val code: KeyCode,
    val width: Float = 1f,
    val kind: KeyKind = KeyKind.LETTER,
    /** Long-press alternatives; the first entry is selected by default. */
    val popup: List<String> = emptyList(),
    /** Small hint drawn in the key corner (usually popup[0]). */
    val hint: String? = null,
) {
    val isLetter: Boolean get() = code is KeyCode.Text && label.length == 1 && label[0].isLetter()
}

data class KeyboardLayout(
    val id: LayoutId,
    val rows: List<List<Key>>,
)
