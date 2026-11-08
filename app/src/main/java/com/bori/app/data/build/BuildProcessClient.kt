package com.bori.app.data.build

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import com.bori.core.build.model.BuildEvent
import com.bori.core.build.model.BuildVariant
import com.bori.core.build.model.CompilerPerformanceProfile
import com.bori.core.build.model.KeystoreConfig
import com.bori.core.project.model.Project
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Same call as `BuildEngine.build`, but the build runs in [BuildProcessService]'s separate
 * process, so a build that runs out of memory can't crash Bori Code. If that process dies before
 * answering, the flow ends with a [BuildEvent.Failure] instead.
 */
@Singleton
class BuildProcessClient @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val replyThread = HandlerThread("build-replies").also { it.start() }

    fun build(
        project: Project,
        kotlinTimeoutSeconds: Long = 120L,
        compilerProfile: CompilerPerformanceProfile = CompilerPerformanceProfile.LOW,
        keystoreConfig: KeystoreConfig? = null,
        variant: BuildVariant = BuildVariant.DEBUG,
        appBundle: Boolean = false,
    ): Flow<BuildEvent> = callbackFlow {
        val finished = AtomicBoolean(false)
        fun finish(event: BuildEvent) {
            if (finished.compareAndSet(false, true)) {
                trySend(event)
                close()
            }
        }
        // linkToDeath and onServiceDisconnected both fire when the process dies; only the first counts.
        fun died() {
            if (!finished.compareAndSet(false, true)) return
            trySend(BuildEvent.Log("✗ $PROCESS_DIED", BuildEvent.Log.Level.ERROR))
            trySend(BuildEvent.Failure(PROCESS_DIED))
            close()
        }

        val replies = Messenger(Handler(replyThread.looper) { msg ->
            val d = msg.data
            when (msg.what) {
                BuildProcessService.MSG_LOG -> if (!finished.get()) trySend(
                    BuildEvent.Log(
                        d.getString(BuildProcessService.KEY_MESSAGE).orEmpty(),
                        BuildEvent.Log.Level.entries.getOrElse(d.getInt(BuildProcessService.KEY_LEVEL)) { BuildEvent.Log.Level.INFO },
                    )
                )
                BuildProcessService.MSG_SUCCESS -> finish(
                    BuildEvent.Success(d.getString(BuildProcessService.KEY_APK).orEmpty(), d.getString(BuildProcessService.KEY_APP_ID))
                )
                BuildProcessService.MSG_FAILURE -> {
                    val error = d.getString(BuildProcessService.KEY_ERROR).orEmpty()
                    if (error == BuildProcessService.OUT_OF_MEMORY) trySend(BuildEvent.Log("✗ $error", BuildEvent.Log.Level.ERROR))
                    finish(BuildEvent.Failure(error))
                }
            }
            true
        })

        var remote: Messenger? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (remote != null) {
                    // Reconnected after the process died and Android restarted it: the build is lost.
                    died(); return
                }
                val messenger = Messenger(binder)
                remote = messenger
                runCatching { binder.linkToDeath({ died() }, 0) }
                val request = BuildProcessService.BuildRequest(project, kotlinTimeoutSeconds, compilerProfile, keystoreConfig, variant, appBundle)
                runCatching {
                    messenger.send(Message.obtain(null, BuildProcessService.MSG_BUILD).apply {
                        data = request.toBundle()
                        replyTo = replies
                    })
                }.onFailure { died() }
            }
            override fun onServiceDisconnected(name: ComponentName) = died()
            override fun onBindingDied(name: ComponentName) = died()
            override fun onNullBinding(name: ComponentName) = died()
        }

        trySend(BuildEvent.Started)
        val bound = runCatching {
            context.bindService(
                Intent(context, BuildProcessService::class.java),
                connection,
                Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT,
            )
        }.getOrDefault(false)
        if (!bound) finish(BuildEvent.Failure("Couldn't start Bori Code's build process."))

        awaitClose {
            if (!finished.get()) runCatching { remote?.send(Message.obtain(null, BuildProcessService.MSG_CANCEL)) }
            if (bound) runCatching { context.unbindService(connection) }
        }
    }

    private companion object {
        const val PROCESS_DIED =
            "The build process stopped unexpectedly (most likely it ran out of memory). Bori Code is fine; " +
                "try the build again, and remove large libraries such as material-icons-extended if it keeps happening."
    }
}
