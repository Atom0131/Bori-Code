package com.bori.core.keyboard.theme

/** How keys are drawn (see KeyPainter). */
enum class KeyLook(val id: String, val title: String) {
    FLAT("flat", "Flat"),
    RAISED("raised", "Raised 3D"),
    SUNKEN("sunken", "Sunken"),
    OUTLINE("outline", "Outline"),
    FLOATING("floating", "Floating");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: FLAT
    }
}

enum class KeyShape(val id: String, val title: String) {
    ROUNDED("rounded", "Rounded"),
    SQUARE("square", "Square"),
    PILL("pill", "Pill"),
    CIRCLE("circle", "Circle"),
    SQUIRCLE("squircle", "Squircle");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: ROUNDED
    }
}

enum class LabelCase(val id: String, val title: String) {
    AUTO("auto", "Follow Shift"),
    UPPER("upper", "Always ABC"),
    LOWER("lower", "Always abc");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: AUTO
    }
}

/**
 * The user's own choices on top of any theme (Settings → Themes → Keys & letters). Null
 * means "use the theme's". Multipliers are 1 when untouched.
 */
data class ThemeOverrides(
    val look: String? = null,
    val shape: String? = null,
    val font: String? = null,
    val labelCase: String? = null,
    val labelScale: Float = 1f,
    val keyOpacity: Float = 1f,
    val hints: Boolean = true,
    /** "Key borders" switch. */
    val keyShapes: Boolean = true,
    /** Settings → Themes → Background; null = the theme's own. */
    val backdrop: BackgroundSpec? = null,
) {
    /** Part of the IME's theme key, so any change re-themes an open keyboard. */
    fun key() = "$look/$shape/$font/$labelCase/$labelScale/$keyOpacity/$hints/$keyShapes/$backdrop"
}
