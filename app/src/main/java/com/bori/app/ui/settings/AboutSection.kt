package com.bori.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp

/** One open-source component Bori Code ships or downloads, with where its source lives. */
private data class Notice(val name: String, val license: String, val url: String, val note: String? = null)

// A function, not a val, so R8 drops it from the Play build, which never calls it.
private fun linuxNotices() = listOf(
    Notice(
        "proot 5.1.107.101 (Termux's fork)", "GPL-2.0", "https://github.com/termux/proot",
        "Shipped as a separate program (libproot.so, libproot-loader.so), built from the " +
            "source attached to each Bori Code release on GitHub, with talloc and libandroid-shmem linked in.",
    ),
    Notice("talloc 2.5.0", "LGPL-3.0-or-later", "https://talloc.samba.org", "Linked into proot; its source and build script come with each release."),
    Notice("libandroid-shmem 0.7", "BSD-3-Clause", "https://github.com/termux/libandroid-shmem", "Linked into proot."),
    Notice("Apache Commons Compress 1.27.1", "Apache-2.0", "https://commons.apache.org/proper/commons-compress/"),
    Notice("XZ for Java 1.10", "0BSD", "https://tukaani.org/xz/java.html"),
    Notice(
        "Void Linux, Alpine Linux", "Each package's own license", "https://voidlinux.org",
        "Downloaded from the distros' official mirrors when you install one; not part of Bori Code.",
    ),
    Notice(
        "Claude Code, Antigravity CLI", "Their makers' terms", "https://docs.anthropic.com/en/docs/claude-code",
        "Installed from their official sources when you choose to; not part of Bori Code. You sign in to them " +
            "directly, and Bori Code never reads their sign-in files.",
    ),
)

private val appNotices = listOf(
    Notice("Termux terminal-emulator and terminal-view 0.118.3", "Apache-2.0", "https://github.com/termux/termux-app"),
    Notice(
        "SCOWL word lists 2020.12.07", "SCOWL license (MIT-like), © 2000-2018 Kevin Atkinson", "http://wordlist.aspell.net",
        "The terminal keyboard's English words; the full notice is in the app's assets (keyboard/SCOWL-NOTICE.txt).",
    ),
    Notice("AndroidX, Jetpack Compose, Room, Hilt", "Apache-2.0", "https://developer.android.com/jetpack"),
    Notice("Kotlin, kotlinx.coroutines, kotlinx.serialization", "Apache-2.0", "https://github.com/JetBrains/kotlin"),
    Notice("Kotlin compiler 2.0.21 and its Compose, serialization and Parcelize plugins (bundled for on-device builds, patched)", "Apache-2.0", "https://github.com/JetBrains/kotlin"),
    Notice("Kotlin Symbol Processing (KSP) 2.0.21-1.0.28", "Apache-2.0", "https://github.com/google/ksp"),
    Notice(
        "OpenJDK java.compiler and StAX API classes", "GPL-2.0 with the Classpath Exception", "https://openjdk.org",
        "javax.lang.model, javax.annotation.processing, javax.tools and javax.xml.stream, bundled for the on-device compiler and symbol processors.",
    ),
    Notice("Android build tools (aapt2, d8, R8, L8) and apksig", "Apache-2.0", "https://source.android.com"),
    Notice("ASM 9.7", "BSD-3-Clause", "https://asm.ow2.io"),
    Notice("Eclipse Compiler for Java (ECJ) 3.32", "EPL-2.0", "https://www.eclipse.org/jdt/core/"),
    Notice("Sora Editor 0.23.4", "LGPL-2.1", "https://github.com/Rosemoe/sora-editor"),
    Notice("JGit 6.10", "EDL-1.0 (BSD-3-Clause)", "https://www.eclipse.org/jgit/"),
    Notice("OkHttp 4.12", "Apache-2.0", "https://square.github.io/okhttp/"),
    Notice("Bouncy Castle 1.78", "MIT (Bouncy Castle License)", "https://www.bouncycastle.org"),
    Notice("LiteRT-LM 0.16", "Apache-2.0", "https://github.com/google-ai-edge/LiteRT-LM"),
    Notice("compose-markdown 0.5.4", "MIT", "https://github.com/jeziellago/compose-markdown"),
    Notice("trove4j", "LGPL-2.1", "https://github.com/JetBrains/intellij-deps-trove4j"),
)

/** Settings → About: the app version and the open-source notices, Linux environment first (full build only). */
@Composable
internal fun AboutSection() {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    Text("Bori - Code $version", style = MaterialTheme.typography.bodyMedium)
    Text(
        "Open-source software in Bori Code. Tap one to open its source.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
    if (com.bori.app.flavor.HAS_LINUX) {
        NoticeGroup("Linux environment", linuxNotices())
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    }
    NoticeGroup("App", appNotices)
}

@Composable
private fun NoticeGroup(title: String, notices: List<Notice>) {
    val uriHandler = LocalUriHandler.current
    Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 4.dp))
    notices.forEach { notice ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { runCatching { uriHandler.openUri(notice.url) } }
                .padding(vertical = 6.dp),
        ) {
            Text(notice.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                notice.license,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            notice.note?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
