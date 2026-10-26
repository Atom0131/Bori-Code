package com.bori.app.ui.common

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bori.app.data.prefs.SessionStateStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Whether the phone keeps Bori Code open on the cover screen when you close it is a system setting
 * (Samsung: "Continue apps on cover screen"; Pixel: "Continue using apps on fold"). Bori Code can't
 * change it, so on a foldable it says where it is, once in Chat and always in Appearance.
 */
@HiltViewModel
class FoldTipViewModel @Inject constructor(
    private val sessionState: SessionStateStore,
) : ViewModel() {
    /** Null while loading, so the banner doesn't flash. */
    val seen: StateFlow<Boolean?> = sessionState.foldTipSeen
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun dismiss() {
        viewModelScope.launch { sessionState.markFoldTipSeen() }
    }
}

/** True on a phone with a hinge sensor: a foldable. */
fun Context.isFoldable(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
        packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_HINGE_ANGLE)

private fun foldTipText(): String = when {
    Build.MANUFACTURER.equals("samsung", ignoreCase = true) ->
        "To keep Bori Code open when you close your phone, turn it on in Settings → Display → " +
            "Continue apps on cover screen."
    Build.MANUFACTURER.equals("google", ignoreCase = true) ->
        "To keep Bori Code open when you close your phone, set Settings → Display → " +
            "Continue using apps on fold to Always."
    else ->
        "To keep Bori Code open when you close your phone, look in your phone's Display settings " +
            "for the option to continue apps on the outer screen."
}

private fun Context.openDisplaySettings() {
    runCatching {
        startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** The one-time tip at the top of Chat. */
@Composable
fun FoldTipBanner(viewModel: FoldTipViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val foldable = remember { context.isFoldable() }
    val seen by viewModel.seen.collectAsStateWithLifecycle()
    if (!foldable || seen != false) return
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(foldTipText(), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { context.openDisplaySettings(); viewModel.dismiss() }) {
                    Text("Open Display settings")
                }
            }
            IconButton(onClick = viewModel::dismiss, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** The same tip for Settings → Appearance; shown only on a foldable. */
@Composable
fun FoldTipSection() {
    val context = LocalContext.current
    if (!remember { context.isFoldable() }) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Bori Code keeps your chat, files and terminals as they are when you fold or unfold. " +
                foldTipText(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { context.openDisplaySettings() }) { Text("Open Display settings") }
    }
}
