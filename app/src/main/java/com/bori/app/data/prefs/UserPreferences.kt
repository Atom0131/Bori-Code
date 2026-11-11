package com.bori.app.data.prefs

import com.bori.core.security.AiProvider
import com.bori.core.build.model.CompilerPerformanceProfile
import com.bori.theme.settings.BoriThemeSettings
import com.bori.theme.settings.ThemeStyle

/** Bori Code's look for a new install: Crystal, like the rest of the Bori family. */
val DefaultBoriCodeTheme = BoriThemeSettings(themeStyle = ThemeStyle.CRYSTAL)

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
    /** Every theme choice (style, light/dark, accent, Crystal's field and glass, Borikén). */
    val theme: BoriThemeSettings = DefaultBoriCodeTheme,
    /** The user's saved colours, shared by the accent and room pickers (newest first, up to six). */
    val customColors: List<Int?> = emptyList(),
    /** Model IDs last fetched from each provider's models endpoint. Absent = never fetched. */
    val cachedModels: Map<AiProvider, List<String>> = emptyMap(),
)
