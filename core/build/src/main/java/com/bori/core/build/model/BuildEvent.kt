package com.bori.core.build.model

sealed class BuildEvent {
    data object Started : BuildEvent()

    data class Log(
        val message: String,
        val level: Level = Level.INFO,
    ) : BuildEvent() {
        enum class Level { INFO, WARN, ERROR }
    }

    data class Success(val apkPath: String) : BuildEvent()

    data class Failure(val error: String) : BuildEvent()
}
