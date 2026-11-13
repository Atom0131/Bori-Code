package com.bori.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.bori.app.MainActivity
import com.bori.app.R

/**
 * Bori's one ongoing notification: what's running and what state it's in (building, an agent working,
 * waiting for you, done). [TerminalService] and [ChatForegroundService] both show it under the same id,
 * so there's one notification however many of them run; Android keeps it while either is in the
 * foreground. An agent that needs the user, or finishes, also gets a short heads-up ([alert]).
 */
object StatusNotifier {
    /** Celeste from the launcher icon; the shade tints the small icon with it. */
    const val BORI_CELESTE = 0xFF4FB4F2.toInt()
    const val CHANNEL_ID = "bori_status"
    const val NOTIFICATION_ID = 1002
    private const val ALERT_CHANNEL_ID = "agent_attention"
    private const val ALERT_ID = 1004
    /** Channels from before the two notifications were merged. */
    private val OLD_CHANNELS = listOf("chat_generation_channel", "terminal_sessions_channel")

    /** ERROR: its turn ended on an API error (Claude Code's StopFailure hook); it waits for the user again. */
    enum class AgentState(val label: String) { WORKING("working"), WAITING("needs you"), DONE("done"), ERROR("hit an error") }

    /** A terminal tab running a coding CLI. [state] is null until the CLI reports one. */
    data class Agent(val title: String, val state: AgentState?, val autoEnters: Int?)

    /** Coding CLIs in terminal tabs; set by TerminalSessionManager. */
    @Volatile var agents: List<Agent> = emptyList()
    @Volatile var terminalTabs: Int = 0

    /** The services showing the notification right now. */
    private val holders = mutableSetOf<String>()

    fun hold(who: String) {
        synchronized(holders) { holders += who }
    }

    fun release(who: String) {
        synchronized(holders) { holders -= who }
    }

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "What Bori is doing: builds, AI replies, terminal agents. Keeps them running in the background."
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL_ID, "Agent needs you", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A coding agent in the terminal is asking for approval, or has finished"
            },
        )
        OLD_CHANNELS.forEach { nm.deleteNotificationChannel(it) }
    }

    fun build(context: Context): Notification {
        val keepAlive = ChatForegroundService.statusLine()
        val agents = agents
        val mode = when {
            ChatForegroundService.isBuilding() -> "Building"
            agents.any { it.state == AgentState.WAITING } -> "Waiting for you"
            agents.any { it.state == AgentState.WORKING } || keepAlive != null -> "Working"
            agents.any { it.state == AgentState.ERROR } -> "Agent hit an error"
            agents.any { it.state == AgentState.DONE } -> "Done"
            terminalTabs > 0 -> "Terminal open"
            else -> "Ready"
        }
        val lines = buildList {
            keepAlive?.let(::add)
            agents.forEach { a ->
                add(buildString {
                    append(a.title)
                    a.state?.let { append(": ").append(it.label) }
                    a.autoEnters?.let { append(" · auto-enter on, ").append(it).append(if (it == 1) " Enter" else " Enters") }
                })
            }
            val shells = terminalTabs - agents.size
            if (shells > 0) add(if (shells == 1) "1 terminal tab" else "$shells terminal tabs")
            if (TerminalService.awake) add("Keeping the phone awake while it works")
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Bori - Code · $mode")
            .setContentText(lines.firstOrNull() ?: "Running")
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setSmallIcon(R.drawable.ic_stat_bori_code)
            .setColor(BORI_CELESTE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    /** Re-posts the notification with the current state, if a service is showing it. */
    fun refresh(context: Context) {
        if (synchronized(holders) { holders.isEmpty() }) return
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(context))
    }

    /** A heads-up that goes away on its own; a newer one replaces it. */
    fun alert(context: Context, title: String, text: String) {
        ensureChannels(context)
        val n = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_bori_code)
            .setColor(BORI_CELESTE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setTimeoutAfter(2 * 60 * 1000L)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(ALERT_ID, n) }
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )
}
