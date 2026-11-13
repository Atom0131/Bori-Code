package com.bori.app.ui.common

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Android 17's local network permission: an app targeting API 37 needs it for any connection to the
 * LAN or a VPN-free private range, Tailscale's 100.64.0.0/10 included. Bori Code needs it for a Local
 * server (Ollama / LM Studio on a PC) and for Linux programs reaching other machines (ssh, git, the
 * bori-net check over Tailscale). The phone itself (localhost) and DNS don't need it.
 */
object LocalNetworkAccess {
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    private const val API_ANDROID_17 = 37

    /** Whether this phone asks for it at all (Android 17+). */
    val applies: Boolean get() = Build.VERSION.SDK_INT >= API_ANDROID_17

    fun granted(context: Context): Boolean =
        !applies || ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED
}

/**
 * A short notice with an Allow button while the local network permission is missing; nothing once
 * it's granted or on Android 16 and older. A second refusal makes Android stop asking, so the button
 * then opens Bori Code's app settings instead.
 */
@Composable
fun LocalNetworkNotice(reason: String, modifier: Modifier = Modifier) {
    if (!LocalNetworkAccess.applies) return
    val context = LocalContext.current
    var granted by remember { mutableStateOf(LocalNetworkAccess.granted(context)) }
    var asked by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    // Re-checked on return from the app's settings page.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = LocalNetworkAccess.granted(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        asked = true
    }
    if (granted) return
    Column(modifier = modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Android 17 asks before apps reach other devices on your network. $reason",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        OutlinedButton(onClick = {
            if (asked) {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } else launcher.launch(LocalNetworkAccess.PERMISSION)
        }) { Text(if (asked) "Open app settings" else "Allow local network") }
    }
}
