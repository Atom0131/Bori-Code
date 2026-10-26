package com.bori.app.data.prefs

import com.bori.core.security.AiProvider
import com.bori.core.build.model.CompilerPerformanceProfile

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

data class UserPreferences(
    val chatProvider: AiProvider = AiProvider.ANTHROPIC,
    /** Empty string means "use the provider's default model". */
    val chatModel: String = "",
    val completionProvider: AiProvider = AiProvider.DEEPSEEK,
    /** Empty string means "use the provider's default model". */
    val completionModel: String = "",
    /** Kotlin compiler timeout in minutes. Higher values help complex files (e.g. GBA CPU decoder). */
    val kotlinTimeoutMinutes: Int = 2,
    /** Max auto-approved tool call turns per session. Low=50, Med=100, High=200. */
    val aiWorkTurns: Int = 50,
    val compilerPerformanceProfile: CompilerPerformanceProfile = CompilerPerformanceProfile.LOW,
    val hasSeenOnboarding: Boolean = false,
    val hasDismissedTutorial: Boolean = false,
    val experimentalFeaturesEnabled: Boolean = false,
    /** ARGB color (0xAARRGGBB) for the user's chat bubbles and send button. */
    val chatBubbleColor: Long = 0xFF1A56DB,
    /** Light, dark, or follow the system. */
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Model IDs last fetched from each provider's models endpoint. Absent = never fetched. */
    val cachedModels: Map<AiProvider, List<String>> = emptyMap(),
)
