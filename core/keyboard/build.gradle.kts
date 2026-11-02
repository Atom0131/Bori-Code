plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// Bori Keyboard's typing engine (dictionaries, spell correction, glide decoding, learning), copied
// from the bori-keyboard project for the terminal's keys. GPL-3.0-or-later; the dictionaries are
// CC BY-SA 4.0 (see ATTRIBUTION.md).
android {
    namespace = "com.bori.core.keyboard"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
}
