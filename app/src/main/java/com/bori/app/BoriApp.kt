package com.bori.app

import android.annotation.SuppressLint
import android.app.Application
import android.util.Log
import com.bori.app.service.ChatForegroundService
import com.bori.app.flavor.startLinuxJobs
import com.bori.core.ai.local.InferenceState
import com.bori.core.ai.local.LocalGemmaClient
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class BoriApp : Application() {

    @Inject lateinit var localGemmaClient: LocalGemmaClient

    private val appScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onCreate() {
        // Before super.onCreate(): Hilt may create things that read the projects folder.
        com.bori.core.project.ProjectRepository.attach(this)
        super.onCreate()
        applyHiddenApiBypass()
        observeGemmaState()
        startLinuxJobs(this, appScope)
    }

    private fun observeGemmaState() {
        appScope.launch {
            localGemmaClient.inferenceState.collect { state ->
                when (state) {
                    is InferenceState.Ready -> ChatForegroundService.start(this@BoriApp)
                    is InferenceState.Idle  -> ChatForegroundService.stop(this@BoriApp)
                    else -> {}
                }
            }
        }
    }

    @SuppressLint("BlockedPrivateApi")
    private fun applyHiddenApiBypass() {
        try {
            val clazz = Class.forName("dalvik.system.VMRuntime")
            val getRuntime = clazz.getDeclaredMethod("getRuntime").also { it.isAccessible = true }
            val vmRuntime = getRuntime.invoke(null)
            val setExemptions = clazz.getDeclaredMethod(
                "setHiddenApiExemptions", Array<String>::class.java
            ).also { it.isAccessible = true }
            setExemptions.invoke(vmRuntime, arrayOf("Lsun/", "Ljdk/", "Ldalvik/system/VMRuntime;"))
            Log.d(TAG, "Hidden API bypass applied at startup")

            // Lower the runtime target SDK so ART's max-targetSdk tier doesn't block
            // sun.misc.Unsafe and other restricted APIs that the Kotlin compiler needs.
            // Safe: framework behavior (Compose, insets, storage) reads applicationInfo.targetSdkVersion
            // from the manifest, not VMRuntime.getTargetSdkVersion().
            try {
                val setTargetSdk = clazz.getDeclaredMethod("setTargetSdkVersion", Int::class.javaPrimitiveType)
                    .also { it.isAccessible = true }
                setTargetSdk.invoke(vmRuntime, 26)
                Log.d(TAG, "Hidden API bypass: setTargetSdkVersion(26) applied")
            } catch (e: Exception) {
                Log.w(TAG, "setTargetSdkVersion(26) failed (non-critical): $e")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Hidden API bypass failed at startup", e)
        }
    }

    companion object {
        private const val TAG = "BoriApp"
    }
}
