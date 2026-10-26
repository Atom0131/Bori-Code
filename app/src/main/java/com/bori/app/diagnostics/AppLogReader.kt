package com.bori.app.diagnostics

import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppLogReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    data class LogEntry(
        val raw: String,
        val level: Char,
        val tag: String,
        val message: String,
    )

    fun isReadLogsGranted(): Boolean =
        context.checkSelfPermission("android.permission.READ_LOGS") == PackageManager.PERMISSION_GRANTED

    /** Captures the last [maxLines] logcat entries from this process (no READ_LOGS needed). */
    suspend fun capture(maxLines: Int = 600): List<LogEntry> = withContext(Dispatchers.IO) {
        val proc = ProcessBuilder("logcat", "-d", "-t", maxLines.toString())
            .redirectErrorStream(true)
            .start()
        val lines = proc.inputStream.bufferedReader().readLines()
        proc.waitFor()
        lines.map { parseLine(it) }
    }

    /** Streams all logcat in real time. Requires READ_LOGS permission. */
    fun streamAll(): Flow<LogEntry> = flow {
        val proc = ProcessBuilder("logcat", "-v", "time")
            .redirectErrorStream(true)
            .start()
        try {
            proc.inputStream.bufferedReader().lineSequence().forEach { emit(parseLine(it)) }
        } finally {
            proc.destroyForcibly()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Streams logcat for [packageName] in real time.
     * If READ_LOGS is not granted, emits a single advisory entry explaining how to grant it.
     */
    fun streamForPackage(packageName: String): Flow<LogEntry> = flow {
        if (!isReadLogsGranted()) {
            emit(LogEntry(
                raw = "⚠ READ_LOGS not granted — run: adb shell pm grant ${context.packageName} android.permission.READ_LOGS",
                level = 'W',
                tag = "Bori",
                message = "READ_LOGS not granted",
            ))
            return@flow
        }
        val uid = try {
            context.packageManager.getApplicationInfo(packageName, 0).uid
        } catch (_: PackageManager.NameNotFoundException) {
            emit(LogEntry(raw = "App not installed: $packageName", level = 'W', tag = "Bori", message = "not installed"))
            return@flow
        }
        val proc = ProcessBuilder("logcat", "--uid", uid.toString(), "-v", "time")
            .redirectErrorStream(true)
            .start()
        try {
            proc.inputStream.bufferedReader().lineSequence().forEach { emit(parseLine(it)) }
        } finally {
            proc.destroyForcibly()
        }
    }.flowOn(Dispatchers.IO)

    // Logcat format: "MM-DD HH:MM:SS.mmm  PID  TID LEVEL TAG: message"
    private val lineRegex = Regex(
        """^[\d-]+\s+[\d:.]+\s+\d+\s+\d+\s+([VDIWEF])\s+([^:]+):\s*(.*)$"""
    )

    private fun parseLine(line: String): LogEntry {
        val m = lineRegex.find(line)
        return if (m != null) {
            LogEntry(
                raw = line,
                level = m.groupValues[1][0],
                tag = m.groupValues[2].trim(),
                message = m.groupValues[3],
            )
        } else {
            LogEntry(raw = line, level = 'I', tag = "", message = line)
        }
    }
}
