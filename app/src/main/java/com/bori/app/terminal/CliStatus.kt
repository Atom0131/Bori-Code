package com.bori.app.terminal

/**
 * Reads a coding CLI's live screen for what its transcript doesn't hold: the status line while it
 * works (Claude Code's `✻ Flabbergasting… (12s · ↑ 1.2k tokens · esc to interrupt)`) and the numbered
 * menu it shows for an approval or a question (`❯ 1. Yes`). Pure text in, so Chat can mirror the
 * terminal exactly and tests can feed it screens.
 */
object CliStatus {
    /** What the CLI is doing: [verb] ("Flabbergasting…"), [detail] ("12s · ↑ 1.2k tokens"), [tip] (the line under it). */
    data class Status(val verb: String, val detail: String? = null, val tip: String? = null)

    /** One choice of a menu: press [number] to pick it. */
    data class Option(val number: Int, val label: String, val description: String? = null, val selected: Boolean = false) {
        /** "Type something" / "No, and tell Claude…": picking it opens a text box, answered by the next Chat send. */
        val asksText: Boolean get() = TEXT_OPTION.containsMatchIn(label)
        /** "Type something.": the text is typed into the menu itself, on this option's row (AskUserQuestion). */
        val inline: Boolean get() = INLINE_OPTION.containsMatchIn(label)
    }

    /** A menu waiting for the user: [question], what it's about ([subject], e.g. the command), the [options]. */
    data class Menu(
        val question: String,
        val subject: String?,
        val options: List<Option>,
        val canCancel: Boolean,
        /** One of several questions asked together (tabs "☐ Bugs ☐ Next ✔ Submit"): ← goes back a question. */
        val steps: Boolean = false,
    )

    /** Spinner characters Claude Code cycles through. Not ● / ⏺, which mark messages. */
    const val SPINNER = "·✢✳✶✻✽*✺✹✸✷"

    /** A Claude Code status line: glyph, a short phrase ending in an ellipsis, maybe "( … )". */
    private val CLAUDE = Regex(
        "^[" + Regex.escape(SPINNER) + "]\\s+([A-Z][\\w'’-]*(?: [\\w'’-]+){0,3}(?:…|\\.\\.\\.))\\s*(?:\\((.*)\\))?\\s*$",
    )

    /** Busy words of other CLIs (and "esc to interrupt" anywhere), from AutoEnter's port of the Windows helper. */
    val BUSY = Regex("Running command\\.\\.\\.|Thinking\\.\\.\\.|Working\\.\\.\\.|esc to interrupt", RegexOption.IGNORE_CASE)

    /** Key hints inside the status parens, not worth showing in Chat. */
    private val HINT = Regex("^(esc|ctrl|shift|alt|tab)\\b|\\bto (interrupt|cancel|show|hide|expand)\\b", RegexOption.IGNORE_CASE)

    private val OPTION = Regex("^(?:([❯›>▶])\\s*)?(\\d{1,2})[.)]\\s+(.+)$")
    private val TEXT_OPTION = Regex("type something|tell claude|something else|other\\b|\\(esc\\)", RegexOption.IGNORE_CASE)
    private val INLINE_OPTION = Regex("type something|something else|^other\\b", RegexOption.IGNORE_CASE)
    /** The tab row of a multi-question form: "← ☐ Bugs  ☐ Next  ✔ Submit →". */
    private val STEPS = Regex("[☐☒✔✓].*\\bSubmit\\b|\\bSubmit\\b.*→")
    private val FOOTER = Regex("esc to cancel|enter to (select|confirm)|to navigate|tab to amend|ctrl\\+\\w+ to ", RegexOption.IGNORE_CASE)
    private val BORDER_CHARS = "│┃║╭╮╰╯─━═┌┐└┘├┤▐▌"

    /** Claude Code's mode line under its input ("⏸ plan mode on (shift+tab to cycle)"). */
    private val MODE = Regex("(plan mode|accept edits|auto-accept edits|bypass permissions|auto mode) on", RegexOption.IGNORE_CASE)

    /** The mode the CLI is in ("Plan mode", "Accept edits", "Bypass permissions"), or null in the default mode. */
    fun mode(lines: List<String>): String? {
        val m = lines.map(::clean).filter { it.isNotEmpty() }.takeLast(MODE_SCAN).firstNotNullOfOrNull { MODE.find(it) } ?: return null
        return when (m.groupValues[1].lowercase()) {
            "plan mode" -> "Plan mode"
            "accept edits", "auto-accept edits" -> "Accept edits"
            "bypass permissions" -> "Bypass permissions"
            else -> "Auto mode"
        }
    }

    private const val MODE_SCAN = 6

    /** The CLI's own input line ("> text", "❯ text"), not a menu's cursor ("❯ 1. Yes"). */
    private val PROMPT = Regex("^[>❯](?:\\s|$)(?!\\s*\\d{1,2}[.)]\\s)")

    /**
     * Whether the CLI shows its input line near the bottom (it's ready for a message, not asking
     * anything), so a WAITING state left over from an answered approval isn't taken for a question.
     */
    fun atPrompt(lines: List<String>): Boolean =
        menu(lines) == null && lines.map(::clean).filter { it.isNotEmpty() }.takeLast(PROMPT_SCAN).any { PROMPT.containsMatchIn(it) }

    private const val PROMPT_SCAN = 8

    /** Claude Code's turn-ending errors: `⎿ API Error: 529 …`, `API Error (Connection error.)`, a usage limit. */
    private val API_ERROR = Regex(
        "API Error\\b.*|\\b(?:usage limit reached|hit your limit|rate limit(?:ed)?|overloaded_error|Overloaded)\\b.*",
        RegexOption.IGNORE_CASE,
    )
    /** A message mark (`⏺ text`): an error above the newest one belongs to an older turn. */
    private val MESSAGE_MARK = Regex("^[⏺●]\\s")
    private const val ERROR_SCAN = 20

    /**
     * The API error that ended the CLI's last turn, as the screen shows it, or null. Only while it isn't
     * working (a "Retrying in 5 s…" error has the spinner under it) and no menu is up, and only below the
     * newest message, so an error from an earlier turn isn't shown again.
     */
    fun apiError(lines: List<String>): String? {
        if (menu(lines) != null || parse(lines) != null) return null
        val rows = lines.map(::clean).filter { it.isNotEmpty() }.takeLast(ERROR_SCAN)
        for (row in rows.reversed()) {
            if (MESSAGE_MARK.containsMatchIn(row)) return null
            val m = API_ERROR.find(row) ?: continue
            return m.value.trim().take(ERROR_CHARS)
        }
        return null
    }

    private const val ERROR_CHARS = 300

    /** Words a question on screen has; a screen with none of them isn't asking anything. */
    private val ASK_CUE = Regex("\\?|\\b(yes|no|allow|deny|approve|proceed|permission|continue)\\b", RegexOption.IGNORE_CASE)

    /**
     * Whether the bottom of the screen could be a question the menu parser couldn't read. Used to tell a
     * real approval from a WAITING the CLI's hooks left behind (no hook fires when an approval is turned
     * down or a turn ends on an API error).
     */
    fun mayBeAsking(lines: List<String>): Boolean =
        apiError(lines) == null && !atPrompt(lines) &&
            lines.map(::clean).filter { it.isNotEmpty() }.takeLast(PROMPT_SCAN)
                .any { ASK_CUE.containsMatchIn(it) && !INTERRUPTED.containsMatchIn(it) }

    /** "⎿ Interrupted · What should Claude do instead?": a turned-down approval, answered at the input line. */
    private val INTERRUPTED = Regex("\\bInterrupted\\b", RegexOption.IGNORE_CASE)

    private const val STEPS_SCAN = 8
    private const val QUESTION_LINES = 4

    /** Strips a TUI box's side borders and padding from one screen line. */
    fun clean(line: String): String = line.trim().trim { it in BORDER_CHARS || it.isWhitespace() }

    /** The status line in the bottom [lines] of the screen, or null when the CLI isn't working. */
    fun parse(lines: List<String>): Status? {
        val rows = lines.map(::clean)
        for (i in rows.indices.reversed()) {
            val m = CLAUDE.find(rows[i]) ?: continue
            val detail = m.groupValues[2].split('·', '•')
                .map { it.trim() }
                .filter { it.isNotEmpty() && !HINT.containsMatchIn(it) }
                .joinToString(" · ")
                .ifEmpty { null }
            val tip = rows.drop(i + 1).firstOrNull { it.isNotEmpty() }
                ?.takeIf { it.startsWith("⎿") }
                ?.removePrefix("⎿")?.trim()?.ifEmpty { null }
            return Status(m.groupValues[1], detail, tip)
        }
        // Other CLIs: their busy line, as it reads.
        val busy = rows.lastOrNull { BUSY.containsMatchIn(it) } ?: return null
        val text = busy.replace(Regex("\\(?\\s*esc to interrupt\\s*\\)?", RegexOption.IGNORE_CASE), "").trim(' ', '·', '-', '(', ')')
        return Status(text.ifEmpty { "Working…" })
    }

    /**
     * The menu at the bottom of the screen, or null. It needs a cursor (`❯`) on one option, numbered
     * 1, 2, 3… in order, and nothing but descriptions and key hints after it: a numbered list in the
     * agent's text has no cursor, and an answered menu is gone from the screen.
     */
    fun menu(lines: List<String>): Menu? {
        val rows = lines.map(::clean)
        // Skip blank lines and key hints at the very bottom.
        var end = rows.size - 1
        while (end >= 0 && (rows[end].isEmpty() || FOOTER.containsMatchIn(rows[end]))) end--
        if (end < 0) return null
        val canCancel = rows.drop(end + 1).any { it.contains("esc to cancel", ignoreCase = true) } ||
            rows.subList(0, end + 1).takeLast(3).any { it.contains("esc to cancel", ignoreCase = true) }
        // Walk up through the option block: options and their indented descriptions.
        val options = ArrayList<Option>()
        var pendingDescription = ArrayList<String>()
        var i = end
        var gap = 0
        while (i >= 0) {
            val row = rows[i]
            val m = OPTION.find(row)
            if (m != null) {
                options.add(0, Option(
                    number = m.groupValues[2].toInt(),
                    label = m.groupValues[3].trim(),
                    description = pendingDescription.reversed().joinToString(" ").ifEmpty { null },
                    selected = m.groupValues[1].isNotEmpty(),
                ))
                pendingDescription = ArrayList()
                gap = 0
                if (m.groupValues[2] == "1") { i--; break }
            } else if (row.isEmpty()) {
                if (options.isEmpty() || ++gap > 2) return null
            } else if (FOOTER.containsMatchIn(row)) {
                // A hint line between options (rare): ignore it.
            } else {
                // A description under an option (or a line of the question, if no option yet).
                if (options.isEmpty() && pendingDescription.size >= 3) return null
                pendingDescription.add(row)
            }
            i--
        }
        if (options.isEmpty() || options.first().number != 1) return null
        if (options.withIndex().any { (k, o) -> o.number != k + 1 }) return null
        if (options.none { it.selected }) return null
        // The question: the nearest text line above option 1.
        while (i >= 0 && rows[i].isEmpty()) i--
        if (i < 0) return null
        // The question, with the lines it wrapped onto (a long one on a narrow screen): the text block
        // right above option 1.
        val questionLines = arrayListOf(rows[i])
        while (i - 1 >= 0 && rows[i - 1].isNotEmpty() && questionLines.size < QUESTION_LINES &&
            !CLAUDE.containsMatchIn(rows[i - 1]) && !OPTION.containsMatchIn(rows[i - 1]) && !STEPS.containsMatchIn(rows[i - 1])
        ) questionLines.add(0, rows[--i])
        val question = questionLines.joinToString(" ")
        // What it's about: the lines above the question, up to a blank line (the command, the file).
        val subject = ArrayList<String>()
        var j = i - 1
        while (j >= 0 && rows[j].isEmpty()) j--
        while (j >= 0 && rows[j].isNotEmpty() && subject.size < 4) {
            if (CLAUDE.containsMatchIn(rows[j]) || OPTION.containsMatchIn(rows[j]) || STEPS.containsMatchIn(rows[j])) break
            subject.add(0, rows[j]); j--
        }
        val steps = rows.subList(maxOf(0, i - STEPS_SCAN), i).any { STEPS.containsMatchIn(it) }
        return Menu(question, subject.joinToString("\n").ifEmpty { null }, options, canCancel, steps)
    }
}
