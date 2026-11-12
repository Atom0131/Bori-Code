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
    }

    /** A menu waiting for the user: [question], what it's about ([subject], e.g. the command), the [options]. */
    data class Menu(val question: String, val subject: String?, val options: List<Option>, val canCancel: Boolean)

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
    private val FOOTER = Regex("esc to cancel|enter to (select|confirm)|to navigate|tab to amend|ctrl\\+\\w+ to ", RegexOption.IGNORE_CASE)
    private val BORDER_CHARS = "│┃║╭╮╰╯─━═┌┐└┘├┤▐▌"

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
        val question = rows[i]
        // What it's about: the lines above the question, up to a blank line (the command, the file).
        val subject = ArrayList<String>()
        var j = i - 1
        while (j >= 0 && rows[j].isEmpty()) j--
        while (j >= 0 && rows[j].isNotEmpty() && subject.size < 4) {
            if (CLAUDE.containsMatchIn(rows[j]) || OPTION.containsMatchIn(rows[j])) break
            subject.add(0, rows[j]); j--
        }
        return Menu(question, subject.joinToString("\n").ifEmpty { null }, options, canCancel)
    }
}
