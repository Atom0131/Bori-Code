package com.bori.core.keyboard.layout

/**
 * Built-in layouts. Every layout has four rows, plus an optional number row on the letters;
 * the keyboard view sizes itself for the tallest one so its height stays constant when
 * switching between letters, symbols and number pads.
 */
object Layouts {

    fun build(
        id: LayoutId,
        lang: Language,
        variant: FieldVariant,
        showGlobe: Boolean,
        numberRow: Boolean = false,
    ): KeyboardLayout =
        when (id) {
            LayoutId.ALPHA -> alpha(lang, variant, showGlobe, numberRow)
            LayoutId.SYMBOLS -> symbols(variant, showGlobe)
            LayoutId.SYMBOLS_MORE -> symbolsMore(variant, showGlobe)
            LayoutId.NUMBER -> number()
            LayoutId.PHONE -> phone()
        }

    private val digits = "1234567890"

    private val commonAccents = mapOf(
        'a' to "àáâäæãåā", 'e' to "èéêëē", 'i' to "îïíīì", 'o' to "ôöòóœøōõ",
        'u' to "ûüùúū", 'c' to "çćč", 'n' to "ñń", 's' to "ßśš", 'y' to "ÿý", 'z' to "žźż",
    )

    // Spanish puts its own accents first so the most likely choice is closest to the finger.
    private val spanishAccents = mapOf(
        'a' to "áàäâ", 'e' to "éèëê", 'i' to "íìïî", 'o' to "óòöô", 'u' to "úüùû",
        'n' to "ñ", 'c' to "ç",
    )

    private fun alpha(lang: Language, variant: FieldVariant, showGlobe: Boolean, numberRow: Boolean): KeyboardLayout {
        val rows = when (lang) {
            Language.EN -> listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
            Language.ES -> listOf("qwertyuiop", "asdfghjklñ", "zxcvbnm")
        }
        val accents = if (lang == Language.ES) spanishAccents else commonAccents
        fun letter(c: Char, digit: Char?): Key {
            val alts = buildList {
                if (digit != null) add(digit.toString())
                accents[c]?.forEach { add(it.toString()) }
            }
            return Key(c.toString(), KeyCode.Text(c.toString()), popup = alts, hint = digit?.toString())
        }
        // With a number row the digits have their own keys, so the top row loses them.
        val top = rows[0].mapIndexed { i, c -> letter(c, if (numberRow) null else digits[i]) }
        val middle = rows[1].map { letter(it, null) }
        val bottom = buildList {
            add(Key("⇧", KeyCode.Shift, 1.5f, KeyKind.FUNCTION))
            rows[2].forEach { add(letter(it, null)) }
            add(Key("⌫", KeyCode.Delete, 1.5f, KeyKind.FUNCTION))
        }
        val letters = listOf(top, middle, bottom, bottomRow("?123", LayoutId.SYMBOLS, variant, showGlobe, lang))
        return KeyboardLayout(
            LayoutId.ALPHA,
            if (numberRow) listOf(digits.map { sym(it.toString()) }) + letters else letters,
        )
    }

    private fun symbols(variant: FieldVariant, showGlobe: Boolean): KeyboardLayout {
        val r1 = digits.map { sym(it.toString()) }
        val r2 = listOf("@", "#", "$", "_", "&", "-", "+", "(", ")", "/").map { sym(it) }
        val r3 = buildList {
            add(Key("=\\<", KeyCode.Switch(LayoutId.SYMBOLS_MORE), 1.5f, KeyKind.FUNCTION))
            listOf("*", "\"", "'", ":", ";", "!", "?").forEach { add(sym(it)) }
            add(Key("⌫", KeyCode.Delete, 1.5f, KeyKind.FUNCTION))
        }
        return KeyboardLayout(LayoutId.SYMBOLS, listOf(r1, r2, r3, bottomRow("ABC", LayoutId.ALPHA, variant, showGlobe, null)))
    }

    private fun symbolsMore(variant: FieldVariant, showGlobe: Boolean): KeyboardLayout {
        val r1 = listOf("~", "`", "|", "•", "√", "π", "÷", "×", "¶", "∆").map { sym(it) }
        val r2 = listOf("£", "¢", "€", "¥", "^", "°", "=", "{", "}", "\\").map { sym(it) }
        val r3 = buildList {
            add(Key("?123", KeyCode.Switch(LayoutId.SYMBOLS), 1.5f, KeyKind.FUNCTION))
            listOf("%", "©", "®", "™", "✓", "[", "]").forEach { add(sym(it)) }
            add(Key("⌫", KeyCode.Delete, 1.5f, KeyKind.FUNCTION))
        }
        return KeyboardLayout(LayoutId.SYMBOLS_MORE, listOf(r1, r2, r3, bottomRow("ABC", LayoutId.ALPHA, variant, showGlobe, null)))
    }

    private val symbolAlts = mapOf(
        "$" to listOf("€", "£", "¥", "¢", "₹", "₩"),
        "!" to listOf("¡"), "?" to listOf("¿"),
        "\"" to listOf("“", "”", "„", "«", "»"), "'" to listOf("‘", "’", "‚", "‹", "›"),
        "-" to listOf("–", "—", "_"), "*" to listOf("†", "‡", "★"),
        "%" to listOf("‰"), "1" to listOf("¹", "½", "⅓", "¼"), "2" to listOf("²", "⅔"),
        "3" to listOf("³", "¾"), "0" to listOf("°", "ⁿ", "∅"),
        "(" to listOf("<", "[", "{"), ")" to listOf(">", "]", "}"),
        "&" to listOf("§"), "+" to listOf("±"), "/" to listOf("÷"), "=" to listOf("≠", "≈", "∞"),
    )

    private fun sym(s: String) = Key(s, KeyCode.Text(s), popup = symbolAlts[s].orEmpty())

    private fun bottomRow(
        switchLabel: String,
        switchTarget: LayoutId,
        variant: FieldVariant,
        showGlobe: Boolean,
        lang: Language?,
    ): List<Key> {
        val punctuation = when (lang) {
            Language.ES -> listOf(",", "?", "!", "¿", "¡", "'", "\"", "-", ":", ";", "@", "#", "/", "&")
            else -> listOf(",", "?", "!", "'", "\"", "-", ":", ";", "@", "#", "/", "&")
        }
        val left = when (variant) {
            FieldVariant.EMAIL -> Key("@", KeyCode.Text("@"), popup = listOf("_", "-", "+"))
            FieldVariant.URL -> Key("/", KeyCode.Text("/"), popup = listOf(":", "_", "-", "~"))
            FieldVariant.TEXT -> Key(",", KeyCode.Text(","), popup = listOf(";", ":"))
        }
        val period = when (variant) {
            FieldVariant.EMAIL, FieldVariant.URL ->
                Key(".", KeyCode.Text("."), popup = listOf(".com", ".org", ".net", ".edu", ".es", ".io"))
            FieldVariant.TEXT -> Key(".", KeyCode.Text("."), popup = punctuation)
        }
        return buildList {
            add(Key(switchLabel, KeyCode.Switch(switchTarget), 1.5f, KeyKind.FUNCTION))
            add(left)
            if (showGlobe) add(Key("🌐", KeyCode.Globe, 1f, KeyKind.FUNCTION))
            // Emoji only where people write messages, not in email or web address fields.
            val emoji = variant == FieldVariant.TEXT
            if (emoji) add(Key("😊", KeyCode.Emoji, 1f, KeyKind.FUNCTION))
            add(Key(" ", KeyCode.Space, 5f - (if (showGlobe) 1f else 0f) - (if (emoji) 1f else 0f), KeyKind.SPACE))
            add(period)
            add(Key("↵", KeyCode.Enter, 1.5f, KeyKind.ENTER))
        }
    }

    private fun digit(d: String, alts: List<String> = emptyList()) =
        Key(d, KeyCode.Text(d), 2.6f, popup = alts)

    private fun fn(label: String, code: KeyCode, alts: List<String> = emptyList()) =
        Key(label, code, 2.2f, KeyKind.FUNCTION, popup = alts)

    private fun number(): KeyboardLayout = KeyboardLayout(
        LayoutId.NUMBER,
        listOf(
            listOf(digit("1"), digit("2"), digit("3"), fn("-", KeyCode.Text("-"), listOf("+", "/", ":"))),
            listOf(digit("4"), digit("5"), digit("6"), fn("␣", KeyCode.Space)),
            listOf(digit("7"), digit("8"), digit("9"), fn("⌫", KeyCode.Delete)),
            listOf(digit(","), digit("0"), digit("."), Key("↵", KeyCode.Enter, 2.2f, KeyKind.ENTER)),
        ),
    )

    private fun phone(): KeyboardLayout = KeyboardLayout(
        LayoutId.PHONE,
        listOf(
            listOf(digit("1"), digit("2"), digit("3"), fn("-", KeyCode.Text("-"), listOf("(", ")", "/"))),
            listOf(digit("4"), digit("5"), digit("6"), fn("␣", KeyCode.Space)),
            listOf(digit("7"), digit("8"), digit("9"), fn("⌫", KeyCode.Delete)),
            listOf(digit("*", listOf("#", ",", ";")), digit("0", listOf("+")), digit("#"), Key("↵", KeyCode.Enter, 2.2f, KeyKind.ENTER)),
        ),
    )
}
