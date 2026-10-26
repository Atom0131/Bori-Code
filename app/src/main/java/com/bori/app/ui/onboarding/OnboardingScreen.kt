package com.bori.app.ui.onboarding

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private data class OnboardingPageData(
    val icon: ImageVector,
    val title: String,
    val body: String,
)

private val pages = listOf(
    OnboardingPageData(
        icon = Icons.Default.PhoneAndroid,
        title = "Your phone is now an IDE",
        body = "Write, build, and install real Android apps — entirely on this device. No laptop. No cables. No compromise.",
    ),
    OnboardingPageData(
        icon = Icons.Default.AutoAwesome,
        title = "AI does the heavy lifting",
        body = "Just describe what you want to build. Bring your own AI API key (Claude, OpenAI, Gemini, Kimi, DeepSeek, or OpenRouter), run models on your own PC with Ollama, or run Gemma right on this phone, offline. Bori Code writes the Kotlin, fixes errors, and keeps iterating until it's done.",
    ),
    OnboardingPageData(
        icon = Icons.Default.RocketLaunch,
        title = "Ready to launch",
        body = "Tap Start Tutorial in Chat and watch Gemma build a calculator right on this phone. For cloud models, add an API key in Settings.",
    ),
)

@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val pagerState = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val isLastPage = pagerState.currentPage == pages.lastIndex

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Skip button — hidden on last page
            if (!isLastPage) {
                TextButton(
                    onClick = { scope.launch { pagerState.animateScrollToPage(pages.lastIndex) } },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(top = 8.dp, end = 8.dp),
                ) {
                    Text("Skip")
                }
            }

            // Swipeable page content
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { index ->
                PageContent(data = pages[index])
            }

            // Dots + button anchored to bottom
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                DotIndicators(
                    pageCount = pages.size,
                    current = pagerState.currentPage,
                )
                Button(
                    onClick = {
                        if (isLastPage) onFinish()
                        else scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp),
                ) {
                    Text(if (isLastPage) "Get Started" else "Next")
                }
            }
        }
    }
}

@Composable
private fun PageContent(data: OnboardingPageData) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = data.icon,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(32.dp))
        Text(
            text = data.title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = data.body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DotIndicators(pageCount: Int, current: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(pageCount) { index ->
            val isCurrent = index == current
            val width by animateDpAsState(
                targetValue = if (isCurrent) 24.dp else 8.dp,
                label = "dot_width",
            )
            Box(
                modifier = Modifier
                    .size(width = width, height = 8.dp)
                    .background(
                        color = if (isCurrent)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                        shape = RoundedCornerShape(4.dp),
                    ),
            )
        }
    }
}
