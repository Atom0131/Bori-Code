plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bori.test.t6"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.bori.test.t6"
        minSdk = 24
        targetSdk = 35
        versionCode = 6
        versionName = "6.0"
    }
    buildFeatures {
        viewBinding = true
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }
}

dependencies {
    // Newer than the built-in AndroidX set, so the whole graph comes from Maven.
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    // A BOM gives OkHttp its version.
    implementation(platform("com.squareup.okhttp3:okhttp-bom:4.12.0"))
    implementation("com.squareup.okhttp3:okhttp")
    // From JitPack (see settings.gradle.kts).
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")
    // A jar in libs/.
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")
}
