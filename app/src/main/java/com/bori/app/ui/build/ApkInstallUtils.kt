package com.bori.app.ui.build

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/** Where BuildEngine leaves a project's signed APK: builds/<project>/<debug|release>/app-<variant>.apk. */
fun apkFileForProject(context: Context, projectName: String, release: Boolean = false): File {
    val variant = if (release) "release" else "debug"
    return File(context.filesDir, "builds/$projectName/$variant/app-$variant.apk")
}

/** The project's newest built APK, debug or release, or null when it has none. */
fun lastBuiltApk(context: Context, projectName: String): File? =
    listOf(apkFileForProject(context, projectName), apkFileForProject(context, projectName, release = true))
        .filter { it.isFile }
        .maxByOrNull { it.lastModified() }

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

/** Opens the share sheet for a built APK or app bundle (to save it to Files or Drive, or send it). */
fun shareBuildOutput(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val type = if (file.extension == "apk") "application/vnd.android.package-archive" else "application/octet-stream"
    val send = Intent(Intent.ACTION_SEND)
        .setType(type)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
