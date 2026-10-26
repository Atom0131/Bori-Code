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
- Manifest: `android:icon` / `android:roundIcon` may point at `@mipmap/...` only when that file exists in res/mipmap-*/, and `android:dataExtractionRules` / `android:fullBackupContent` only when the res/xml file exists. Keep the manifest minimal.
- Layouts: use standard View-based XML layouts (LinearLayout, TextView, Button, etc.). Do not use Jetpack Compose unless the user explicitly requests it. If they do, use Compose 1.4.x (`androidx.compose.ui:ui:1.4.3`, `androidx.compose.material3:material3:1.1.1`, `androidx.activity:activity-compose:1.7.2`): the on-device Compose compiler is 1.4.8 (Kotlin 1.8.22).
- Kotlin is compiled with Kotlin 1.8.22, so don't use language features from Kotlin 1.9 or 2.x. Java sources are Java 8.
- Supported from build.gradle(.kts): `applicationId`, `versionCode`, `versionName`, `minSdk`, `targetSdk`, `buildConfigField(...)` (read them via `BuildConfig`), and `implementation` / `api` / `compileOnly` / `kapt` / `annotationProcessor` dependencies. Sources can be in src/main/java or src/main/kotlin; src/main/assets/ and src/main/jniLibs/<abi>/*.so are packaged.
- Multi-module projects work: list modules in settings.gradle(.kts) and depend on them with `implementation(project(":name"))`.
- Not supported on the phone: KSP (use kapt instead), ViewBinding and DataBinding (use findViewById), product flavors, NDK/CMake builds (prebuilt .so files in jniLibs are fine), unit tests and lint.

## Known-working library versions
Always use these exact versions — they have been tested with Bori Code's on-device build pipeline:
- Room: `androidx.room:room-runtime:2.6.1`, `androidx.room:room-ktx:2.6.1`, kapt `androidx.room:room-compiler:2.6.1`
- Retrofit2: `com.squareup.retrofit2:retrofit:2.11.0`, `com.squareup.retrofit2:converter-gson:2.11.0`
- OkHttp3: `com.squareup.okhttp3:okhttp:4.12.0`, `com.squareup.okhttp3:logging-interceptor:4.12.0`
- Gson: `com.google.code.gson:gson:2.10.1`
- Coroutines: `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3`, `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3`
- Coil: `io.coil-kt:coil:2.5.0`
- Moshi: `com.squareup.moshi:moshi-kotlin:1.15.0`, kapt `com.squareup.moshi:moshi-kotlin-codegen:1.15.0`
- Lifecycle ViewModel + LiveData: pre-bundled — no dependency needed, just `import androidx.lifecycle.*`"""
}
