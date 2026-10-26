package com.bori.core.project

/**
 * What any AI writing code for a Bori Code project must know about the on-device build. One copy, used by
 * Bori Code's chat prompt (ContextManager) and by the rule files written for the coding CLIs (GuestPolicy),
 * so a version bump or a new constraint reaches both.
 */
object AndroidBuildRules {
    const val TEXT = """## Android project constraints (IMPORTANT)
Bori Code builds Android projects on-device without running Gradle. Follow these rules:

- Dependencies: You CAN add `implementation("group:artifact:version")` lines to app/build.gradle.kts. Bori Code downloads them automatically from Maven Central and Google Maven at build time. Common libraries like Retrofit, OkHttp, Room, Gson, Moshi, and Coil are all supported. For Kotlin annotation processors (Room compiler, Dagger, Hilt), use `kapt("group:artifact:version")`.
- Themes: use ONLY built-in Android framework themes such as `android:Theme.Material.Light.NoActionBar` or `android:Theme.Material.Light`. Never use `Theme.MaterialComponents`, `Theme.AppCompat`, or any theme requiring an external library.
- Theme attributes: use only `android:` prefixed attributes (e.g. `android:colorPrimary`). Never use bare Material/AppCompat attributes like `colorPrimary` without the `android:` prefix.
- Manifest: do NOT include `android:icon`, `android:roundIcon`, `android:dataExtractionRules`, `android:fullBackupContent`, or `tools:targetApi`. Keep the manifest minimal.
- No mipmap resources: do not reference `@mipmap/ic_launcher` or any mipmap drawable — no mipmap directory is scaffolded.
- Layouts: use standard View-based XML layouts (LinearLayout, TextView, Button, etc.). Do not use Jetpack Compose unless the user explicitly requests it.

## Known-working library versions
Always use these exact versions — they have been tested with Bori Code's on-device build pipeline:
- Room: `androidx.room:room-runtime:2.6.1`, `androidx.room:room-ktx:2.6.1`, kapt `androidx.room:room-compiler:2.6.1`
- Retrofit2: `com.squareup.retrofit2:retrofit:2.11.0`, `com.squareup.retrofit2:converter-gson:2.11.0`
- OkHttp3: `com.squareup.okhttp3:okhttp:4.12.0`, `com.squareup.okhttp3:logging-interceptor:4.12.0`
- Gson: `com.google.code.gson:gson:2.10.1`
- Coroutines: `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3`, `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3`
- Coil: `io.coil-kt:coil:2.5.0`
- Moshi: `com.squareup.moshi:moshi-kotlin:1.15.0`, kapt `com.squareup.moshi:moshi-kotlin-codegen:1.15.0`
- Lifecycle ViewModel + LiveData: pre-bundled — no dependency needed, just `import androidx.lifecycle.*`
- ViewBinding: enable with `buildFeatures { viewBinding = true }` in the android {} block of build.gradle.kts"""
}
