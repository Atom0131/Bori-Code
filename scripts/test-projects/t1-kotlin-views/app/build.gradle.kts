plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bori.test.t1"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.bori.test.t1"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
}
