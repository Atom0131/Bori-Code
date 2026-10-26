package com.bori.core.build.model

enum class CompilerPerformanceProfile {
    /** Created from scratch every build; lowest RAM usage. */
    LOW,
    /** Persistent compiler instance; faster builds, uses ~200MB RAM. */
    MEDIUM,
    /** Persistent instance + ADPF CPU performance boosting; fastest builds, uses RAM + battery. */
    HIGH
}
