package com.bori.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import android.graphics.Color
import androidx.compose.runtime.DisposableEffect
import com.bori.app.data.build.LiveDiagnosticsController
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.bori.app.data.prefs.ThemeMode
import com.bori.app.ui.BoriNavHost
import com.bori.app.ui.theme.BoriTheme
import com.bori.core.ai.suggestion.InlineSuggestionEngine
import com.bori.core.editor.EditorController
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var editorController: EditorController

    @Inject
    lateinit var suggestionEngine: InlineSuggestionEngine

    @Inject
    lateinit var liveDiagnosticsController: LiveDiagnosticsController

    @Inject
    lateinit var debugTool: com.bori.core.build.CompilerDebugTool

    @Inject
    lateinit var prefsRepository: com.bori.app.data.prefs.UserPreferencesRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        liveDiagnosticsController.start()
        debugTool.debugResourceLookup()
        setContent {
            val prefs by prefsRepository.preferences.collectAsState(initial = null)
            val dark = when (prefs?.themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                else -> isSystemInDarkTheme()
            }
            // Status and navigation bar icons follow the app's theme, not only the system's.
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
                )
                onDispose {}
            }
            BoriTheme(darkTheme = dark) {
                BoriNavHost(
                    editorController = editorController,
                    suggestionEngine = suggestionEngine,
                )
            }
        }
    }

    private companion object {
        // The scrims enableEdgeToEdge uses by default for 3-button navigation.
        val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
