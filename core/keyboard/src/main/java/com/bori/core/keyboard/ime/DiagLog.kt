package com.bori.core.keyboard.ime

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Small diagnostics log for problems that need the phone to reproduce (e.g. the keyboard not
 * opening with two apps side by side). Keeps the last [MAX_LINES] lifecycle events in memory
 * and saves them, plus the stack trace of any crash, to files/diag_log.txt. It never records
 * what is typed: only app package names, field types, sizes and lifecycle calls.
 * Settings → Diagnostics copies it.
 */
object DiagLog {
    private const val FILE = "diag_log.txt"
    private const val MAX_LINES = 300
    private val lines = ArrayDeque<String>()
    private var installed = false

    @Synchronized
    fun event(msg: String) {
        lines.addLast("${SystemClock.uptimeMillis()} $msg")
        while (lines.size > MAX_LINES) lines.removeFirst()
    }

    /** Saves the recent events, keeping a crash report that is already in the file. */
    @Synchronized
    fun save(context: Context) {
        runCatching {
            val f = File(context.filesDir, FILE)
            val crash = f.takeIf { it.exists() }?.readText()?.substringAfter(CRASH_MARK, "")
                ?.let { if (it.isEmpty()) "" else CRASH_MARK + it }.orEmpty()
            f.writeText(lines.joinToString("\n") + "\n" + crash)
        }
    }

    /** Records crashes of the keyboard process, then lets Android handle them as usual. */
    fun installCrashHandler(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                synchronized(this) {
                    File(app.filesDir, FILE).writeText(
                        lines.joinToString("\n") + "\n$CRASH_MARK on ${t.name}\n$sw",
                    )
                }
            }
            previous?.uncaughtException(t, e)
        }
    }

    fun export(context: Context): String =
        runCatching { File(context.filesDir, FILE).readText() }.getOrDefault("")

    fun clear(context: Context) {
        synchronized(this) { lines.clear() }
        File(context.filesDir, FILE).delete()
    }

    private const val CRASH_MARK = "=== CRASH"
}
