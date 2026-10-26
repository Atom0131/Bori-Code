package com.bori.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bori.app.data.build.KeystoreRepository
import com.bori.core.build.BuildEngine
import com.bori.core.build.model.BuildEvent
import com.bori.core.build.model.BuildVariant
import com.bori.core.build.model.KeystoreConfig
import com.bori.core.project.ProjectRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * Starts a build from adb, for testing the on-device builder (debug builds only):
 *
 *     adb shell am broadcast -a com.bori.app.debug.BUILD -n <app id>/com.bori.app.debug.DebugBuildReceiver \
 *         --es project t1-kotlin-views [--ez release true [--ez bundle true]] [--es ks <keystore> --es alias <alias> --es pass <password>]
 *
 * The log goes to `<external files>/build-out/<project>.log`, ending with `RESULT OK <apk>` or
 * `RESULT FAIL <message>`, and the APK is copied next to it as `<project>.apk`.
 */
@AndroidEntryPoint
class DebugBuildReceiver : BroadcastReceiver() {
    @Inject lateinit var buildEngine: BuildEngine
    @Inject lateinit var projectRepository: ProjectRepository
    @Inject lateinit var keystoreRepository: KeystoreRepository

    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.getStringExtra("project") ?: return
        val variant = if (intent.getBooleanExtra("release", false)) BuildVariant.RELEASE else BuildVariant.DEBUG
        val outDir = File(context.getExternalFilesDir(null), "build-out").also { it.mkdirs() }
        val log = File(outDir, "$name.log").also { it.writeText("") }
        scope.launch {
            val project = projectRepository.upsertFromPath(File(ProjectRepository.PROJECTS_ROOT, name).absolutePath)
            val keystore = intent.getStringExtra("ks")?.let { ks ->
                val pass = intent.getStringExtra("pass").orEmpty()
                KeystoreConfig(ks, intent.getStringExtra("alias").orEmpty(), pass, pass)
            } ?: if (variant == BuildVariant.RELEASE) keystoreRepository.get() else null
            val bundle = intent.getBooleanExtra("bundle", false)
            buildEngine.build(project, keystoreConfig = keystore, variant = variant, appBundle = bundle).collect { event ->
                when (event) {
                    is BuildEvent.Log -> log.appendText(event.message + "\n")
                    is BuildEvent.Success -> {
                        val apk = File(event.apkPath)
                        val copy = File(outDir, "$name.${apk.extension}")
                        apk.copyTo(copy, overwrite = true)
                        log.appendText("RESULT OK ${copy.absolutePath}\n")
                    }
                    is BuildEvent.Failure -> log.appendText("RESULT FAIL ${event.error}\n")
                    BuildEvent.Started -> Unit
                }
            }
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
