package com.bori.app.ui.settings

import com.bori.theme.components.crystalScaffoldContentColor
import com.bori.theme.components.crystalScaffoldColor
import com.bori.app.ui.common.ShellContentInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bori.app.ui.common.BoriTopBar
import com.bori.app.ui.common.ReadableColumn

/** More → About & licenses. */
@Composable
fun AboutScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val prefs by viewModel.userPrefs.collectAsState()
    Scaffold(contentWindowInsets = ShellContentInsets, containerColor = crystalScaffoldColor(), contentColor = crystalScaffoldContentColor(), topBar = { BoriTopBar(title = "About & licenses", onBack = onBack) }) { padding ->
        ReadableColumn(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            AboutSection(theme = prefs.theme, onThemeChange = { viewModel.setTheme(it) })
        }
    }
}
