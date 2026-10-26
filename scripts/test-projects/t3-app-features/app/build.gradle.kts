plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bori.test.t3"
    compileSdk = 35
    defaultConfig {
        // Differs from the namespace on purpose: R and BuildConfig stay in com.bori.test.t3.
        applicationId = "com.bori.test.t3app"
        minSdk = 24
        targetSdk = 35
        versionCode = 7
        versionName = "3.1"
        buildConfigField("String", "GREETING", "\"hola\"")
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    // implementation("androidx.startup:startup-runtime:1.0.0")   // commented out: must be ignored
    implementation("androidx.startup:startup-runtime:1.1.1")
}
