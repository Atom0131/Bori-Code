package com.bori.app.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bori.app.flavor.FloatingStats
import com.bori.app.stats.StatKind
import com.bori.app.stats.StatsPrefs
import com.bori.app.stats.StatsSampler
import com.bori.app.stats.StatsSettings
import com.bori.app.stats.TokenStats
import com.bori.app.ui.common.CollapsibleSection
import com.bori.theme.components.crystalFilterChipColors
import com.bori.theme.components.crystalSwitchColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Settings → Appearance → Stats: what the stats overlay and the notification show. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatsSection() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { StatsPrefs.init(context) }
    val s by StatsPrefs.settings.collectAsStateWithLifecycle()
    val tokens by TokenStats.state.collectAsStateWithLifecycle()
    // What this phone lets an app read, from one reading when the section opens.
    var snapshot by remember { mutableStateOf<com.bori.app.stats.StatsSnapshot?>(null) }
    LaunchedEffect(Unit) { snapshot = withContext(Dispatchers.IO) { StatsSampler.readOnce(context) } }
    // Back from Android's "Display over other apps" page: start the panel if it was allowed.
    var canDraw by remember { mutableStateOf(FloatingStats.canDraw(context)) }
    LifecycleResumeEffect(Unit) {
        canDraw = FloatingStats.canDraw(context)
        apply(context) { it }
        onPauseOrDispose {}
    }

    val summary = when {
        !s.overlay && !s.inNotification -> "Off"
        else -> listOfNotNull(
            if (s.overlay) (if (s.overApps && canDraw) "Over other apps" else "Overlay") else null,
            if (s.inNotification) "Notification" else null,
        ).joinToString(" · ") + " · ${s.shown.size} stats"
    }
    CollapsibleSection(title = "Stats", summary = summary, icon = Icons.Default.Speed) {
        Text(
            "Temperatures, load, memory and AI token speed, live. Handy while an agent runs a long job: " +
                "the phone gets hot.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        SwitchRow("Show stats overlay", "A small panel you can drag anywhere. Tap it for more; long-press for these settings.", s.overlay) { on ->
            apply(context) { it.copy(overlay = on) }
        }
        if (FloatingStats.AVAILABLE && s.overlay) {
            SwitchRow(
                "Float over other apps",
                if (canDraw) "Keeps the panel on screen in any app, like a game's FPS counter."
                else "Needs Android's \"Display over other apps\" permission for Bori Code.",
                s.overApps && canDraw,
            ) { on ->
                if (on && !FloatingStats.canDraw(context)) {
                    StatsPrefs.update { it.copy(overApps = true) }
                    FloatingStats.permissionIntent(context)?.let { runCatching { context.startActivity(it) } }
                } else {
                    apply(context) { it.copy(overApps = on) }
                }
            }
        }
        SwitchRow("Show stats in the notification", "Bori's notification, while a terminal, build or reply is running.", s.inNotification) { on ->
            apply(context) { it.copy(inNotification = on) }
        }
        Text("Stats to show", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StatKind.entries.forEach { kind ->
                val tokenStat = kind == StatKind.TOKEN_SPEED || kind == StatKind.TOKENS
                // Token stats appear once a reply has run; phone stats this phone hides can't be picked.
                val available = tokenStat || snapshot?.has(kind, tokens) != false
                FilterChip(
                    selected = kind in s.shown && available,
                    onClick = { apply(context) { it.copy(shown = if (kind in it.shown) it.shown - kind else it.shown + kind) } },
                    enabled = available,
                    label = { Text(kind.label) },
                    colors = crystalFilterChipColors(),
                )
            }
        }
        val missing = snapshot?.let { snap -> StatKind.entries.filter { k -> k != StatKind.TOKEN_SPEED && k != StatKind.TOKENS && !snap.has(k, tokens) } }
        if (!missing.isNullOrEmpty()) {
            Text(
                "Not available on this phone: " + missing.joinToString(", ") { it.label.lowercase() } +
                    ". Android doesn't let apps read them here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Text(
            "Bori + agents CPU counts Bori Code and everything running in its terminals; Android doesn't let " +
                "apps see the whole phone's CPU. Claude Code's tokens come from its own log after each step.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** Saves [change] and brings the notification and the floating panel in line with it. */
private fun apply(context: Context, change: (StatsSettings) -> StatsSettings) {
    StatsPrefs.update(change)
    StatsSampler.syncNotification(context)
    com.bori.app.service.StatusNotifier.refresh(context)
    FloatingStats.sync(context)
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, colors = crystalSwitchColors())
    }
}
