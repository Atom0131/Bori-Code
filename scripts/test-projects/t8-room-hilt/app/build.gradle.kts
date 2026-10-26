plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.bori.test.t8"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.bori.test.t8"
        minSdk = 24
        targetSdk = 35
        versionCode = 8
        versionName = "8.0"
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("com.google.dagger:hilt-android:2.52")
    // kapt here on purpose: the builder runs it through KSP.
    kapt("com.google.dagger:hilt-android-compiler:2.52")
    implementation("com.squareup.moshi:moshi:1.15.1")
    ksp("com.squareup.moshi:moshi-kotlin-codegen:1.15.1")
}
