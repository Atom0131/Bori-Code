plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    id("kotlin-parcelize")
}

android {
    namespace = "com.bori.test.t7"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.bori.test.t7"
        minSdk = 24
        targetSdk = 35
        versionCode = 7
        versionName = "7.0"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.kotlinx.serialization.json)
}
