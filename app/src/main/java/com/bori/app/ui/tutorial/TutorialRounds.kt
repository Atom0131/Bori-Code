package com.bori.app.ui.tutorial

import com.bori.core.project.model.Project
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The calculator tutorial as three chat rounds: build it, add color and show the sum as you type,
 * then make it adapt when the phone unfolds.
 *
 * Each round shows the user's own request in chat. Gemma 4 E2B/E4B can't reliably turn a one-line
 * request into a working app, so every file also gets a spec: plain-language rules (names, colors,
 * sizes and what each function does), never code to copy. Gemma writes each file on its own, with
 * greedy decoding, so a rehearsed run writes the same files again on camera.
 */
object TutorialRounds {

    /** Round 3's question: what changes when the phone unfolds. */
    enum class UnfoldChoice(val label: String) {
        BIGGER("Bigger buttons and text"),
        SIDE_BY_SIDE("Display beside the keypad"),
    }

    /**
     * @param path file path inside the project, e.g. `app/src/main/res/layout/activity_main.xml`
     * @param spec builds the rules for this file from the file's current content
     * @param problem what would stop the build or crash the app, or null when the file is usable
     */
    class RoundFile(
        val path: (Project) -> String,
        val lang: String,
        val spec: (current: String) -> String,
        val problem: (String) -> String?,
    ) {
        fun name(project: Project) = path(project).substringAfterLast('/')
    }

    class Round(
        val number: Int,
        /** Shown in the chat box, ready to send; the user may type their own words instead. */
        val request: String,
        val files: List<RoundFile>,
        /** What to try once the app is installed. */
        val tryIt: String,
        /** Folder under `assets/safety_net/` with known-good files for this round. */
        val safetyNetDir: String,
    )

    const val ROUND_COUNT = 3

    fun round(number: Int, choice: UnfoldChoice?): Round = when (number) {
        1 -> Round(
            number = 1,
            request = "Please build me a calculator app for Android: just the 4 basic functions " +
                "(+, −, ×, ÷) and a number keypad.",
            files = listOf(kotlinFile(::round1Kotlin, mustContain = emptyList()), layoutFile(::round1Layout)),
            tryIt = "Open the calculator and try 7 × 6 =. It should show 42.",
            safetyNetDir = "round1",
        )
        2 -> Round(
            number = 2,
            request = "Now add some color to the buttons and the display, and show the numbers as I type, " +
                "so it shows 5 + 5 = 10 and I can spot a mistake.",
            files = listOf(
                kotlinFile(::round2Kotlin, mustContain = listOf("expression")),
                stylesFile(sizes = PLAIN_SIZES),
                layoutFile(::round2Layout, mustContain = listOf("@style/CalcDisplay", "@style/DigitButton")),
            ),
            tryIt = "Open the calculator and type 5 + 5 =. The display should show 5 + 5 = 10.",
            safetyNetDir = "round2",
        )
        else -> when (choice ?: UnfoldChoice.BIGGER) {
            UnfoldChoice.BIGGER -> Round(
                number = 3,
                request = UNFOLD_REQUEST,
                files = listOf(
                    dimensFile("values", DIMENS_FOLDED),
                    dimensFile("values-w600dp", DIMENS_UNFOLDED),
                    stylesFile(sizes = DIMEN_SIZES),
                    kotlinFile(::round3Kotlin, mustContain = listOf("onSaveInstanceState")),
                ),
                tryIt = UNFOLD_TRY_IT,
                safetyNetDir = "round3a",
            )
            UnfoldChoice.SIDE_BY_SIDE -> Round(
                number = 3,
                request = UNFOLD_REQUEST,
                files = listOf(
                    RoundFile(
                        path = { "app/src/main/res/layout-w600dp/activity_main.xml" },
                        lang = "xml",
                        spec = ::round3SideBySideLayout,
                        problem = { layoutProblem(it) ?: missing(it, listOf("horizontal", "@style/DigitButton")) },
                    ),
                    kotlinFile(::round3Kotlin, mustContain = listOf("onSaveInstanceState")),
                ),
                tryIt = UNFOLD_TRY_IT,
                safetyNetDir = "round3b",
            )
        }
    }

    private const val UNFOLD_REQUEST = "Make the calculator scale to the phone's screen like Google Clock and " +
        "Samsung Clock do. It should adapt when I unfold my phone."
    private const val UNFOLD_TRY_IT = "Open the calculator, type a number, then unfold the phone. " +
        "The layout should change and your number should still be there."

    // ── Files ─────────────────────────────────────────────────────────────────

    private fun kotlinFile(spec: (String) -> String, mustContain: List<String>) = RoundFile(
        path = { "app/src/main/java/${it.packageName.replace('.', '/')}/MainActivity.kt" },
        lang = "kotlin",
        spec = spec,
        problem = { kotlinProblem(it) ?: missing(it, mustContain) },
    )

    private fun layoutFile(spec: (String) -> String, mustContain: List<String> = emptyList()) = RoundFile(
        path = { "app/src/main/res/layout/activity_main.xml" },
        lang = "xml",
        spec = spec,
        problem = { layoutProblem(it) ?: missing(it, mustContain) },
    )

    private fun stylesFile(sizes: StyleSizes) = RoundFile(
        path = { "app/src/main/res/values/styles.xml" },
        lang = "xml",
        spec = { stylesSpec(sizes) },
        problem = { stylesProblem(it, sizes) },
    )

    private fun dimensFile(folder: String, values: Map<String, String>) = RoundFile(
        path = { "app/src/main/res/$folder/dimens.xml" },
        lang = "xml",
        spec = { dimensSpec(folder, values) },
        problem = { dimensProblem(it, values.keys) },
    )

    private fun missing(code: String, required: List<String>): String? =
        required.firstOrNull { it !in code }?.let { "doesn't contain $it" }

    // ── Specs: rules in words, no code to copy ────────────────────────────────

    private fun StringBuilder.currentFile(lang: String, current: String) {
        if (current.isBlank()) return
        appendLine("Current file:")
        appendLine("```$lang")
        appendLine(current.trimEnd())
        appendLine("```")
        appendLine()
    }

    private const val FUNCTION_RULES = """- appendDigit(value: String): if value is "." and currentInput already contains ".", return.
  If newInput is true, clear currentInput and set newInput to false.
  Append value to currentInput."""

    /** Spelled out rule by rule: with only a list of requirements, E2B stored 0 for every operand. */
    private fun round1Kotlin(current: String) = buildString {
        appendLine("Turn this Android Activity into a working calculator.")
        appendLine()
        currentFile("kotlin", current)
        appendLine("Write the complete file:")
        appendLine("- Keep the package line unchanged.")
        appendLine("- Imports: android.os.Bundle, android.widget.Button, android.widget.TextView,")
        appendLine("  androidx.appcompat.app.AppCompatActivity, java.text.DecimalFormat.")
        appendLine("- class MainActivity extends AppCompatActivity and has five private fields:")
        appendLine("  a lateinit TextView named display, a StringBuilder named currentInput,")
        appendLine("  a String named operator (empty), a nullable Double named firstOperand (null)")
        appendLine("  and a Boolean named newInput (true).")
        appendLine("- onCreate: call super.onCreate(savedInstanceState) first, then setContentView(R.layout.activity_main),")
        appendLine("  then find display by R.id.display, then give every button its own click listener,")
        appendLine("  one findViewById<Button> line per button (never build an id in a loop):")
        appendLine("  btn0 to btn9 call appendDigit with their digit, btnDot calls appendDigit(\".\"),")
        appendLine("  btnAdd, btnSub, btnMul and btnDiv call setOperator with \"+\", \"-\", \"*\" and \"/\",")
        appendLine("  btnEquals calls calculate(), btnClear calls clear().")
        appendLine()
        appendLine("Each function does exactly this:")
        appendLine(FUNCTION_RULES)
        appendLine("  Then show currentInput on the display.")
        appendLine("- setOperator(op: String): firstOperand becomes currentInput as a Double, or keeps its old value")
        appendLine("  if currentInput isn't a number. operator becomes op and newInput becomes true.")
        appendLine("- calculate(): read currentInput as a Double (second) and firstOperand (first); return if either")
        appendLine("  is missing. Work out the result for the operator; dividing by 0 gives Double.NaN.")
        appendLine("  Format the Double result with DecimalFormat(\"#.##########\"), show it on the display,")
        appendLine("  put it in currentInput, set firstOperand to null, operator to \"\" and newInput to true.")
        appendLine("- clear(): empty currentInput, firstOperand null, operator \"\", newInput true, display shows \"0\".")
        appendLine()
        append("Output ONLY the complete file as a ```kotlin code block```. No explanation.")
    }

    /**
     * Rounds 2 and 3 write each file from complete rules instead of editing the current one: when
     * E4B copied and edited a long file, it slipped late in the reply ("0.0.0", "#16113e6").
     */
    private fun StringBuilder.kotlinBase(intro: String, keepSum: Boolean) {
        appendLine(intro)
        appendLine()
        appendLine("Write the complete file:")
        appendLine("- Start with a package line.")
        appendLine("- Imports: android.os.Bundle, android.widget.Button, android.widget.TextView,")
        appendLine("  androidx.appcompat.app.AppCompatActivity, java.text.DecimalFormat.")
        appendLine("- class MainActivity extends AppCompatActivity and has six private fields:")
        appendLine("  a lateinit TextView named display, a StringBuilder named currentInput,")
        appendLine("  a String named operator (empty), a nullable Double named firstOperand (null),")
        appendLine("  a Boolean named newInput (true) and a String named expression (empty).")
        appendLine("- onCreate: call super.onCreate(savedInstanceState) first, then setContentView(R.layout.activity_main),")
        appendLine("  then find display by R.id.display, then give every button its own click listener,")
        appendLine("  one findViewById<Button> line per button (never build an id in a loop):")
        appendLine("  btn0 to btn9 call appendDigit with their digit, btnDot calls appendDigit(\".\"),")
        appendLine("  btnAdd, btnSub, btnMul and btnDiv call setOperator with \"+\", \"-\", \"*\" and \"/\",")
        appendLine("  btnEquals calls calculate(), btnClear calls clear().")
        appendLine()
        appendLine("Each function does exactly this:")
        appendLine("- appendDigit(value: String): if value is \".\" and currentInput already contains \".\", return.")
        appendLine("  If newInput is true: clear currentInput, set newInput to false, and if expression")
        appendLine("  contains \"=\" set expression to \"\". Append value to currentInput, and also add value")
        appendLine("  to the end of expression. Then set display.text to expression (not currentInput).")
        appendLine("- setOperator(op: String): firstOperand becomes currentInput as a Double, or keeps its old value")
        appendLine("  if currentInput isn't a number. If expression contains \"=\", set expression to currentInput as a String.")
        appendLine("  Pick the symbol for op: + for \"+\", − for \"-\", × for \"*\" and ÷ for \"/\". Add a space,")
        appendLine("  the symbol and a space to the end of expression (only the symbol, never op itself).")
        appendLine("  operator becomes op and newInput becomes true. Then set display.text to expression.")
        appendLine("- calculate(): read currentInput as a Double (second) and firstOperand (first); return if either")
        appendLine("  is missing. Work out the result for the operator; dividing by 0 gives Double.NaN.")
        appendLine("  Format the Double result with DecimalFormat(\"#.##########\") into a String named text.")
        appendLine("  Add \" = \" and text to expression and show expression on the display. currentInput becomes")
        appendLine("  a StringBuilder holding text, firstOperand null, operator \"\" and newInput true.")
        appendLine("- clear(): empty currentInput and expression, firstOperand null, operator \"\", newInput true,")
        appendLine("  and the display shows \"0\".")
        if (keepSum) {
            appendLine("- override fun onSaveInstanceState(outState: Bundle), which returns nothing (no return type,")
            appendLine("  no return statement): call super first, then put currentInput as a String")
            appendLine("  under \"currentInput\", expression under \"expression\", operator under \"operator\",")
            appendLine("  newInput under \"newInput\", and firstOperand under \"firstOperand\" only when it isn't null.")
            appendLine("- At the end of onCreate, if savedInstanceState isn't null, read those five values back")
            appendLine("  (firstOperand only if the Bundle contains the key) and show expression on the display,")
            appendLine("  or \"0\" when expression is empty.")
        }
        appendLine()
        append("Output ONLY the complete file as a ```kotlin code block```. No explanation.")
    }

    @Suppress("UNUSED_PARAMETER")
    private fun round2Kotlin(current: String) = buildString {
        kotlinBase("Write an Android calculator Activity that shows the whole sum as the user types, like 5 + 5 = 10.", keepSum = false)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun round3Kotlin(current: String) = buildString {
        kotlinBase(
            "Write an Android calculator Activity that shows the whole sum as the user types, like 5 + 5 = 10,\n" +
                "and keeps the sum when the phone folds or unfolds (Android recreates the Activity then).",
            keepSum = true,
        )
    }

    private const val KEYPAD_ROWS = """- Row 1: btnClear "C", btnDiv "÷", btnMul "×", btnSub "−"
- Row 2: btn7 "7", btn8 "8", btn9 "9", btnAdd "+"
- Row 3: btn4 "4", btn5 "5", btn6 "6", btnEquals "="
- Row 4: btn1 "1", btn2 "2", btn3 "3", btnDot "."
- Row 5: btn0 "0" on its own, so it fills the row"""

    /** Plain on purpose, so round 2's colors are a visible change. */
    private fun round1Layout(current: String) = buildString {
        appendLine("Replace this Android layout with a calculator layout.")
        appendLine()
        currentFile("xml", current)
        appendLine("Rules (use LinearLayout only, no GridLayout or ConstraintLayout):")
        appendLine("- The root is a vertical LinearLayout that fills the screen, with padding 8dp.")
        appendLine("- First a TextView with id display: full width, height 0dp with layout_weight 2,")
        appendLine("  gravity end|center_vertical, padding 16dp, text \"0\", textSize 48sp.")
        appendLine("- Then exactly 5 rows. Each row is a horizontal LinearLayout: full width, height 0dp,")
        appendLine("  layout_weight 1. Each Button in a row has width 0dp, height match_parent, layout_weight 1,")
        appendLine("  layout_margin 4dp and textSize 24sp. No colors yet.")
        appendLine(KEYPAD_ROWS)
        appendLine("- Every attribute starts with android: and every size has a number and a unit, like 8dp or 24sp.")
        appendLine()
        append("Output ONLY the complete file as a ```xml code block```. No explanation.")
    }

    /** Every Button only has an id, a style and a label; the look lives in styles.xml (short files stay correct). */
    private const val STYLED_ROWS = """  Every Button has only three attributes: android:id, style and android:text.
  The style is @style/DigitButton for the digits and the dot, @style/OperatorButton for ÷ × − +,
  and @style/ActionButton for C and =.
- Row 1: btnClear "C", btnDiv "÷", btnMul "×", btnSub "−"
- Row 2: btn7 "7", btn8 "8", btn9 "9", btnAdd "+"
- Row 3: btn4 "4", btn5 "5", btn6 "6", btnEquals "="
- Row 4: btn1 "1", btn2 "2", btn3 "3", btnDot "."
- Row 5: btn0 "0" on its own, so it fills the row"""

    @Suppress("UNUSED_PARAMETER")
    private fun round2Layout(current: String) = buildString {
        appendLine("Write an Android calculator layout that takes its look from styles.xml.")
        appendLine()
        appendLine("Rules (use LinearLayout only, no GridLayout or ConstraintLayout):")
        appendLine("- The root is a vertical LinearLayout: layout_width and layout_height match_parent,")
        appendLine("  padding 8dp, background #1a1a2e.")
        appendLine("- First a TextView with only android:id display, style @style/CalcDisplay and android:text \"0\".")
        appendLine("- Then exactly 5 rows. Each row is a horizontal LinearLayout: layout_width match_parent,")
        appendLine("  layout_height 0dp, layout_weight 1.")
        appendLine(STYLED_ROWS)
        appendLine()
        append("Output ONLY the complete file as a ```xml code block```. No explanation.")
    }

    /** Sizes in styles.xml: plain values in round 2, @dimen resources in round 3 so they grow when unfolded. */
    class StyleSizes(val displayText: String, val buttonText: String, val buttonMargin: String)

    private val PLAIN_SIZES = StyleSizes("48sp", "24sp", "4dp")
    private val DIMEN_SIZES = StyleSizes("@dimen/display_text", "@dimen/button_text", "@dimen/button_margin")
    val STYLE_NAMES = listOf("CalcDisplay", "DigitButton", "OperatorButton", "ActionButton")

    private fun stylesSpec(sizes: StyleSizes) = buildString {
        appendLine("Write the Android resource file res/values/styles.xml for a calculator.")
        if (sizes === DIMEN_SIZES) appendLine("The sizes come from dimen resources, so the calculator grows when the phone unfolds.")
        appendLine()
        appendLine("Rules: the root element is resources, holding exactly four style elements. Each item's name")
        appendLine("starts with android:, and every color has exactly 6 hex digits.")
        appendLine("- CalcDisplay: layout_width match_parent, layout_height 0dp, layout_weight 2, layout_marginBottom 8dp,")
        appendLine("  gravity end|center_vertical, padding 16dp, textSize ${sizes.displayText}, background #16213e,")
        appendLine("  textColor #FFFFFF, fontFamily monospace, maxLines 2, autoSizeTextType uniform.")
        appendLine("- DigitButton: layout_width 0dp, layout_height match_parent, layout_weight 1,")
        appendLine("  layout_margin ${sizes.buttonMargin}, textSize ${sizes.buttonText}, textColor #FFFFFF, backgroundTint #16213e.")
        appendLine("- OperatorButton, with parent DigitButton: only backgroundTint #0f3460.")
        appendLine("- ActionButton, with parent DigitButton: only backgroundTint #e94560.")
        appendLine()
        append("Output ONLY the complete file as a ```xml code block```. No explanation.")
    }

    private val DIMENS_FOLDED = linkedMapOf("display_text" to "56sp", "button_text" to "24sp", "button_margin" to "4dp")
    private val DIMENS_UNFOLDED = linkedMapOf("display_text" to "96sp", "button_text" to "40sp", "button_margin" to "8dp")

    private fun dimensSpec(folder: String, values: Map<String, String>) = buildString {
        appendLine("Write the Android resource file res/$folder/dimens.xml for a calculator.")
        appendLine(
            if (folder == "values") "These sizes are used on a normal phone screen."
            else "These sizes are used when the screen is at least 600dp wide, like an unfolded phone.",
        )
        appendLine()
        appendLine("Rules:")
        appendLine("- The root element is resources.")
        values.forEach { (name, value) -> appendLine("- A dimen named $name with the value $value.") }
        appendLine()
        append("Output ONLY the complete file as a ```xml code block```. No explanation.")
    }

    @Suppress("UNUSED_PARAMETER")
    private fun round3SideBySideLayout(current: String) = buildString {
        appendLine("Write a calculator layout for wide screens (an unfolded phone), saved as")
        appendLine("res/layout-w600dp/activity_main.xml. Android picks it automatically when the screen is wide.")
        appendLine("It takes its look from the styles in styles.xml.")
        appendLine()
        appendLine("Rules (use LinearLayout only, no GridLayout or ConstraintLayout):")
        appendLine("- The root is a horizontal LinearLayout: layout_width and layout_height match_parent,")
        appendLine("  padding 8dp, background #1a1a2e.")
        appendLine("- Left: a TextView with android:id display, style @style/CalcDisplay, android:text \"0\",")
        appendLine("  and these to place it beside the keypad: layout_width 0dp, layout_height match_parent,")
        appendLine("  layout_weight 1, layout_marginEnd 8dp.")
        appendLine("- Right: a vertical LinearLayout with layout_width 0dp, layout_height match_parent and")
        appendLine("  layout_weight 1, holding exactly 5 rows. Each row is a horizontal LinearLayout:")
        appendLine("  layout_width match_parent, layout_height 0dp, layout_weight 1.")
        appendLine(STYLED_ROWS)
        appendLine()
        append("Output ONLY the complete file as a ```xml code block```. No explanation.")
    }

    // ── Checks: what stops the on-device build or crashes the app ─────────────

    /** Every id MainActivity looks up; a missing one crashes the app on launch. */
    val LAYOUT_IDS = listOf("display", "btnClear", "btnDiv", "btnMul", "btnSub", "btnAdd", "btnEquals", "btnDot") +
        (0..9).map { "btn$it" }

    /** Catches the Kotlin slips that stop the on-device compile: a missing class, cut-off braces, unknown ids. */
    fun kotlinProblem(code: String): String? {
        if ("class MainActivity" !in code) return "no MainActivity class"
        if ("setContentView(R.layout.activity_main)" !in code) return "no setContentView(R.layout.activity_main)"
        if (code.count { it == '{' } != code.count { it == '}' }) return "unbalanced braces"
        if (Regex("""fun onSaveInstanceState\([^)]*\)\s*:""").containsMatchIn(code)) return "onSaveInstanceState must not return a value"
        val unknown = Regex("""R\.id\.(\w+)""").findAll(code).map { it.groupValues[1] }.firstOrNull { it !in LAYOUT_IDS }
        return unknown?.let { "unknown id R.id.$it" }
    }

    private fun xmlParseError(xml: String): String? = runCatching {
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(InputSource(StringReader(xml)))
    }.exceptionOrNull()?.let { "not well-formed XML: ${it.message}" }

    /**
     * aapt2 rejects the whole build over one bad attribute. On the Fold, E4B wrote `android:text="`
     * with no closing quote, `layout_height="dp"`, `#162133e` and `androidbackgroundTint`.
     */
    fun layoutProblem(xml: String): String? {
        xmlParseError(xml)?.let { return it }
        LAYOUT_IDS.firstOrNull { "@+id/$it\"" !in xml }?.let { return "missing @+id/$it" }
        if ("GridLayout" in xml) return "uses GridLayout"
        val dimension = Regex("""android:(?:layout_(?:width|height|margin\w*)|textSize|padding\w*)="([^"]*)"""")
        dimension.findAll(xml).firstOrNull { !VALID_SIZE.matches(it.groupValues[1]) }?.let { return "bad size ${it.value}" }
        val color = Regex(""""(#[0-9A-Fa-f]*)"""")
        color.findAll(xml).firstOrNull { it.groupValues[1].length - 1 !in setOf(3, 4, 6, 8) }?.let { return "bad color ${it.value}" }
        val unprefixedAttr = Regex("""\s(?!style=)[A-Za-z_]+="""")
        return unprefixedAttr.find(xml.substringAfter("?>"))?.let { "attribute without android: ${it.value.trim()}" }
    }

    private fun stylesProblem(xml: String, sizes: StyleSizes): String? {
        xmlParseError(xml)?.let { return it }
        STYLE_NAMES.firstOrNull { "name=\"$it\"" !in xml }?.let { return "missing style $it" }
        val items = Regex("""<item\s+name="([^"]+)"\s*>\s*([^<]*?)\s*</item>""").findAll(xml).map { it.groupValues[1] to it.groupValues[2] }.toList()
        if (items.isEmpty()) return "no style items"
        items.firstOrNull { !it.first.startsWith("android:") }?.let { return "item without android: ${it.first}" }
        val sizeName = Regex("""android:(?:layout_(?:width|height|margin\w*)|textSize|padding\w*)""")
        items.firstOrNull { sizeName.matches(it.first) && !VALID_SIZE.matches(it.second) }?.let { return "bad size ${it.first}=${it.second}" }
        items.firstOrNull { it.second.startsWith("#") && it.second.length - 1 !in setOf(3, 4, 6, 8) }?.let { return "bad color ${it.second}" }
        if (sizes.displayText.startsWith("@") && "@dimen/display_text" !in xml) return "doesn't use @dimen/display_text"
        return null
    }

    private fun dimensProblem(xml: String, names: Set<String>): String? {
        xmlParseError(xml)?.let { return it }
        val dimens = Regex("""<dimen\s+name="(\w+)"\s*>\s*([^<\s]+)\s*</dimen>""").findAll(xml)
            .associate { it.groupValues[1] to it.groupValues[2] }
        names.firstOrNull { it !in dimens }?.let { return "missing dimen $it" }
        return dimens.entries.firstOrNull { !VALID_SIZE.matches(it.value) }?.let { "bad size ${it.key}=${it.value}" }
    }

    private val VALID_SIZE = Regex("""match_parent|wrap_content|fill_parent|-?\d+(?:\.\d+)?(?:dp|dip|sp|px|pt|mm|in)|[@?].+""")

    // ── Small, safe repairs ───────────────────────────────────────────────────

    /** Forces the project's package; E2B renamed it, which broke every `R` reference. */
    fun withPackage(code: String, packageName: String): String {
        val packageLine = Regex("""^\s*package\s+[\w.]+;?""", RegexOption.MULTILINE)
        return if (packageLine.containsMatchIn(code)) packageLine.replaceFirst(code, "package $packageName")
        else "package $packageName\n\n$code"
    }

    /** Without super.onCreate the app crashes on launch (SuperNotCalledException); E2B dropped it. */
    fun withSuperOnCreate(code: String): String {
        if ("super.onCreate(" in code) return code
        val onCreate = Regex("""override fun onCreate\(\s*(\w+)\s*:\s*Bundle\?\s*\)\s*\{""")
        val match = onCreate.find(code) ?: return code
        val end = match.range.last + 1
        return code.substring(0, end) + "\n        super.onCreate(${match.groupValues[1]})" + code.substring(end)
    }

    /** `R.id.btn${i}` doesn't compile (E4B wired the digits in a loop this way); look the id up by name instead. */
    fun withLiteralIds(code: String): String =
        Regex("""R\.id\.(\w+)\$\{?(\w+)\}?""").replace(code) { m ->
            val (prefix, variable) = m.destructured
            "resources.getIdentifier(\"$prefix\$$variable\", \"id\", packageName)"
        }

    /** `style` is the one layout attribute without a namespace; E4B wrote `android:style`, which aapt2 rejects. */
    fun withPlainStyle(xml: String): String = xml.replace("android:style=", "style=")

    fun extractCodeBlock(text: String, lang: String): String? {
        val fence = "```$lang"
        val fenceStart = text.indexOf(fence)
        if (fenceStart == -1) return null
        val contentStart = text.indexOf('\n', fenceStart + fence.length)
        if (contentStart == -1) return null
        val closeStart = text.indexOf("```", contentStart)
        if (closeStart == -1) return null
        return text.substring(contentStart + 1, closeStart).trim().takeIf { it.isNotEmpty() }
    }
}
