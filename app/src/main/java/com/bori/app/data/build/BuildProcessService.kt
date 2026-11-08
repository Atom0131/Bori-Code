package com.bori.app.data.build

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import android.util.Log
import com.bori.core.build.BuildEngine
import com.bori.core.build.model.BuildEvent
import com.bori.core.build.model.BuildVariant
import com.bori.core.build.model.CompilerPerformanceProfile
import com.bori.core.build.model.KeystoreConfig
import com.bori.core.project.model.Project
import com.bori.core.project.model.ProjectTemplate
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Runs [BuildEngine] in its own process (`:build` in the manifest). The compiler, D8 and big
 * libraries can fill the whole heap; in Bori Code's main process that took the UI down with it
 * (two OutOfMemoryError crashes on the Fold, 2026-11-07, while Antigravity built a Compose app
 * with material-icons-extended). Here an out-of-memory only ends this process, and
 * [BuildProcessClient] turns that into a failed build.
 *
 * The protocol is plain [Messenger] messages with primitive [Bundle]s: [MSG_BUILD] in, then
 * [MSG_LOG]… and exactly one [MSG_SUCCESS] or [MSG_FAILURE] back. [MSG_CANCEL] stops the build.
 */
@AndroidEntryPoint
class BuildProcessService : Service() {

    @Inject lateinit var buildEngine: BuildEngine

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private lateinit var ipcThread: HandlerThread
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        ipcThread = HandlerThread("build-ipc").also { it.start() }
        messenger = Messenger(Handler(ipcThread.looper) { msg -> handle(msg); true })
    }

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onDestroy() {
        job?.cancel()
        ipcThread.quitSafely()
        super.onDestroy()
    }

    private fun handle(msg: Message) {
        when (msg.what) {
            MSG_BUILD -> start(msg.data, msg.replyTo ?: return)
            MSG_CANCEL -> job?.cancel()
        }
    }

    private fun start(data: Bundle, reply: Messenger) {
        if (job?.isActive == true) {
            send(reply, MSG_FAILURE, Bundle().apply { putString(KEY_ERROR, "Another build is still running in the build process.") })
            return
        }
        val request = BuildRequest.fromBundle(data)
        job = scope.launch {
            var outOfMemory = false
            try {
                buildEngine.build(
                    project = request.project,
                    kotlinTimeoutSeconds = request.kotlinTimeoutSeconds,
                    compilerProfile = request.compilerProfile,
                    keystoreConfig = request.keystoreConfig,
                    variant = request.variant,
                    appBundle = request.appBundle,
                ).collect { event ->
                    when (event) {
                        is BuildEvent.Log -> send(reply, MSG_LOG, Bundle().apply {
                            putString(KEY_MESSAGE, event.message)
                            putInt(KEY_LEVEL, event.level.ordinal)
                        })
                        is BuildEvent.Success -> send(reply, MSG_SUCCESS, Bundle().apply {
                            putString(KEY_APK, event.apkPath)
                            putString(KEY_APP_ID, event.applicationId)
                        })
                        is BuildEvent.Failure -> {
                            outOfMemory = event.error.contains("Out of Memory", ignoreCase = true)
                            send(reply, MSG_FAILURE, Bundle().apply {
                                putString(KEY_ERROR, if (outOfMemory) OUT_OF_MEMORY else event.error)
                            })
                        }
                        BuildEvent.Started -> Unit
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                outOfMemory = t is OutOfMemoryError
                Log.e(TAG, "Build crashed", t)
                send(reply, MSG_FAILURE, Bundle().apply {
                    putString(KEY_ERROR, if (outOfMemory) OUT_OF_MEMORY else (t.message ?: t.toString()))
                })
            }
            // After an out-of-memory the heap is full of half-built state; the next build gets a
            // fresh process instead. The client already has its answer, so it ignores the death.
            if (outOfMemory) Process.killProcess(Process.myPid())
        }
    }

    private fun send(reply: Messenger, what: Int, data: Bundle) {
        try {
            reply.send(Message.obtain(null, what).apply { this.data = data })
        } catch (e: RemoteException) {
            // Bori Code's main process is gone; nobody is waiting for this build any more.
            job?.cancel()
        }
    }

    /** What the main process asks for: the same arguments as [BuildEngine.build]. */
    data class BuildRequest(
        val project: Project,
        val kotlinTimeoutSeconds: Long,
        val compilerProfile: CompilerPerformanceProfile,
        val keystoreConfig: KeystoreConfig?,
        val variant: BuildVariant,
        val appBundle: Boolean,
    ) {
        fun toBundle() = Bundle().apply {
            putLong("p.id", project.id)
            putString("p.name", project.name)
            putString("p.package", project.packageName)
            putInt("p.minSdk", project.minSdk)
            putInt("p.targetSdk", project.targetSdk)
            putString("p.root", project.rootPath)
            putString("p.template", project.template.name)
            putLong("p.created", project.createdAt)
            putLong("p.updated", project.updatedAt)
            putLong("timeout", kotlinTimeoutSeconds)
            putString("profile", compilerProfile.name)
            putString("variant", variant.name)
            putBoolean("bundle", appBundle)
            keystoreConfig?.let {
                putString("ks.path", it.keystorePath)
                putString("ks.alias", it.keyAlias)
                putString("ks.storePass", it.keystorePassword)
                putString("ks.keyPass", it.keyPassword)
            }
        }

        companion object {
            fun fromBundle(b: Bundle) = BuildRequest(
                project = Project(
                    id = b.getLong("p.id"),
                    name = b.getString("p.name").orEmpty(),
                    packageName = b.getString("p.package").orEmpty(),
                    minSdk = b.getInt("p.minSdk"),
                    targetSdk = b.getInt("p.targetSdk"),
                    rootPath = b.getString("p.root").orEmpty(),
                    template = ProjectTemplate.valueOf(b.getString("p.template")!!),
                    createdAt = b.getLong("p.created"),
                    updatedAt = b.getLong("p.updated"),
                ),
                kotlinTimeoutSeconds = b.getLong("timeout"),
                compilerProfile = CompilerPerformanceProfile.valueOf(b.getString("profile")!!),
                keystoreConfig = b.getString("ks.path")?.let {
                    KeystoreConfig(it, b.getString("ks.alias").orEmpty(), b.getString("ks.storePass").orEmpty(), b.getString("ks.keyPass").orEmpty())
                },
                variant = BuildVariant.valueOf(b.getString("variant")!!),
                appBundle = b.getBoolean("bundle"),
            )
        }
    }

    companion object {
        private const val TAG = "BuildProcess"
        const val MSG_BUILD = 1
        const val MSG_CANCEL = 2
        const val MSG_LOG = 10
        const val MSG_SUCCESS = 11
        const val MSG_FAILURE = 12
        const val KEY_MESSAGE = "message"
        const val KEY_LEVEL = "level"
        const val KEY_APK = "apk"
        const val KEY_APP_ID = "appId"
        const val KEY_ERROR = "error"

        const val OUT_OF_MEMORY =
            "Build ran out of memory. Bori Code is fine; only the build stopped. Large libraries " +
                "(for example material-icons-extended) are the usual cause: remove them or add only the icons you need."
    }
}
