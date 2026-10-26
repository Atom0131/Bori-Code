package com.bori.core.build.model

sealed class BuildEvent {
    data object Started : BuildEvent()

    data class Log(
        val message: String,
        val level: Level = Level.INFO,
    ) : BuildEvent() {
        enum class Level { INFO, WARN, ERROR }
    }

    /** [applicationId] is the built app's installed package name (null from older callers). */
    data class Success(val apkPath: String, val applicationId: String? = null) : BuildEvent()

    data class Failure(val error: String) : BuildEvent()
}
