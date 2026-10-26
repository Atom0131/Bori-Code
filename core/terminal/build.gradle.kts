// Vendored copy of Termux's terminal-emulator + terminal-view libraries (v0.118.3,
// Apache-2.0 per termux-app's LICENSE.md exception; see NOTICE). Rebuilt here so
// libtermux.so gets 16 KB page alignment: the JitPack AAR's copy is 4 KB aligned.
// The .so files in src/main/jniLibs are prebuilt by build-native.sh (NDK r28) and committed.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.termux.view"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
}
