package com.bori.core.ai.provider

/**
 * A coding CLI (Claude Code, Antigravity) that runs in the Linux environment and is the agent
 * itself. Implemented in :core:linux, which only the full build includes; ModelRouter gets
 * whichever are bound (none in the Play build).
 */
interface CliProviderClient : AiProviderClient {
    /** Whether the CLI is installed in a Linux distro, so a chat turn can start it. */
    fun isAvailable(): Boolean
}
