package com.bori.app.ui.build

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

fun apkFileForProject(context: Context, projectName: String, release: Boolean = false): File =
    File(context.filesDir, "builds/$projectName/${if (release) "app-release.apk" else "app-debug.apk"}")

fun formatApkSize(bytes: Long): String = when {
    bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
    else -> "${bytes / 1024} KB"
}

fun canInstallPackages(context: Context): Boolean =
    context.packageManager.canRequestPackageInstalls()

fun openInstallPermissionSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

fun launchApkInstaller(context: Context, apkFile: File) {
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        apkFile,
    )
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    // Go straight to the system installer; other APK handlers (e.g. SideQuest) made Android ask "Open with"
    context.packageManager.queryIntentActivities(intent, 0)
        .firstOrNull { it.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 }
        ?.let { intent.setPackage(it.activityInfo.packageName) }
    context.startActivity(intent)
}
