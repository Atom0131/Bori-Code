package com.bori.core.project

/**
 * What any AI writing code for a Bori Code project must know about the on-device build. One copy, used by
 * Bori Code's chat prompt (ContextManager) and by the rule files written for the coding CLIs (GuestPolicy),
 * so a version bump or a new constraint reaches both.
 */
object AndroidBuildRules {
    const val TEXT = """## Android project constraints (IMPORTANT)
Bori Code builds Android projects on-device without running Gradle. It reads the build files the way Gradle would, so write normal Android Gradle projects, with these limits:

- Kotlin is compiled with Kotlin 2.0.21 (K2), Java with ECJ at Java 8 level. Don't use language features newer than Kotlin 2.0 or Java 8 in .java files.
- Dependencies: `implementation` / `api` / `compileOnly` / `runtimeOnly` lines with full coordinates, version catalogs (gradle/libs.versions.toml, bundles too), `platform(...)` BOMs (so versionless coordinates work), `files(...)` / `fileTree("libs")` jars and AARs, and extra repositories such as JitPack in settings.gradle(.kts). They're downloaded at build time.
- Code generation: use `ksp(...)` for Room (`androidx.room:room-compiler`), Hilt (`com.google.dagger:hilt-compiler`, with the `com.google.dagger.hilt.android` plugin and `@AndroidEntryPoint` / `@HiltAndroidApp` as usual) and Moshi (`com.squareup.moshi:moshi-kotlin-codegen`). kapt and annotationProcessor can't run on the phone; Bori Code runs those three through KSP anyway, but other kapt-only processors are skipped. Room skips its build-time SQL check on the phone, so double-check query syntax.
- Compose: supported, including the Compose BOM (e.g. `platform("androidx.compose:compose-bom:2024.10.00")`) and Compose 1.7. Apply `org.jetbrains.kotlin.plugin.compose` (or set `buildFeatures { compose = true }`). Use Views when the user hasn't asked for Compose.
- Kotlin plugins: `kotlinx.serialization` (`org.jetbrains.kotlin.plugin.serialization`) and Parcelize (`kotlin-parcelize`) work.
- From build.gradle(.kts): `applicationId`, `versionCode`, `versionName`, `minSdk`, `targetSdk`, `buildConfigField(...)` (read via `BuildConfig`), `buildFeatures { viewBinding = true }` (ViewBinding classes are generated), `isCoreLibraryDesugaringEnabled` with `coreLibraryDesugaring(...)` (java.time on older Android), and the release build type's `isMinifyEnabled` / `proguardFiles`.
- Sources can be in src/main/java or src/main/kotlin; src/main/assets/ and src/main/jniLibs/<abi>/*.so are packaged. Multi-module projects work: list modules in settings.gradle(.kts) and depend on them with `implementation(project(":name"))`.
- Manifest: `android:icon` / `android:roundIcon` may point at `@mipmap/...` only when that file exists in res/mipmap-*/, and `android:dataExtractionRules` / `android:fullBackupContent` only when the res/xml file exists. Library manifests (WorkManager, FileProvider, Startup…) are merged automatically.
- Themes: framework themes (`android:Theme.Material.Light.NoActionBar`) always work. AppCompat (`Theme.AppCompat.*`) and Material Components / Material 3 themes need that library in the dependencies.
- Release builds can be an APK or an Android App Bundle (.aab) for Google Play, signed with the keystore from Settings → Release Signing.
- Not supported on the phone: DataBinding, product flavors, NDK/CMake builds (prebuilt .so files in jniLibs are fine), unit tests and lint. Don't run Gradle.

## Known-working library versions
These have been tested with Bori Code's on-device build:
- Room: `androidx.room:room-runtime:2.6.1`, `androidx.room:room-ktx:2.6.1`, ksp `androidx.room:room-compiler:2.6.1`
- Hilt: `com.google.dagger:hilt-android:2.52`, ksp `com.google.dagger:hilt-compiler:2.52`
- Retrofit2: `com.squareup.retrofit2:retrofit:2.11.0`, `com.squareup.retrofit2:converter-gson:2.11.0`
- OkHttp3: `com.squareup.okhttp3:okhttp:4.12.0`, `com.squareup.okhttp3:logging-interceptor:4.12.0`
- Moshi: `com.squareup.moshi:moshi:1.15.1`, ksp `com.squareup.moshi:moshi-kotlin-codegen:1.15.1`
- Compose: `platform("androidx.compose:compose-bom:2024.10.00")`, `androidx.activity:activity-compose:1.9.3`
- kotlinx.serialization: `org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3`
- Coroutines: `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3`
- Lifecycle ViewModel + LiveData, AppCompat, Material Components, ConstraintLayout, RecyclerView: built in, so they work offline at these versions (a newer version in the build file is downloaded instead)."""
}
