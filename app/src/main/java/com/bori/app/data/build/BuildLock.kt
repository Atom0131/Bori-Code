package com.bori.app.data.build

import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One build at a time. The Build screen, the agent's build tool and the debug receiver all call
 * BuildEngine, which shares one Kotlin compiler and writes `files/builds/<project>/`; two builds at
 * once would overwrite each other's outputs (and could run the phone out of memory).
 */
@Singleton
class BuildLock @Inject constructor() {
    private val mutex = Mutex()

    /** Runs [block] once no other build is running; calls [onWait] first if it has to wait. */
    suspend fun <T> withLock(onWait: () -> Unit, block: suspend () -> T): T {
        if (!mutex.tryLock()) {
            onWait()
            mutex.lock()
        }
        try {
            return block()
        } finally {
            mutex.unlock()
        }
    }

    companion object {
        const val WAITING = "Waiting for another build to finish…"
    }
}
